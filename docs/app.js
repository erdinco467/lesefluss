'use strict';
/* Lesefluss web: speed reader (RSVP) and focus reader. Everything stays in this browser (localStorage + IndexedDB). */

// ================= storage =================
const LS = {
  get(k, d) { try { const v = localStorage.getItem('lf.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('lf.' + k, JSON.stringify(v)); } catch { /* storage full or blocked */ } },
};

let dbPromise = null;
function db() {
  if (!dbPromise) dbPromise = new Promise((res, rej) => {
    const r = indexedDB.open('lesefluss', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('texts');
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
  return dbPromise;
}
async function idb(mode, fn) {
  const d = await db();
  return new Promise((res, rej) => {
    const t = d.transaction('texts', mode);
    const req = fn(t.objectStore('texts'));
    t.oncomplete = () => res(req && req.result);
    t.onerror = () => rej(t.error);
  });
}
const putText = (id, text) => idb('readwrite', s => s.put(text, id));
const getText = id => idb('readonly', s => s.get(id));
const delText = id => idb('readwrite', s => s.delete(id));

// ================= settings =================
const DEFAULTS = {
  theme: 0, accent: 0, font: 0,
  wpm: 300, chunk: 1, rsvpSize: 64, pause: 2, longSlow: true, softStart: true, orp: true, guides: true, context: true, rewind: true,
  fWpm: 220, fSize: 24, fSpacing: 16, fBlock: 60, bionic: true, bionicPct: 45, fDim: 65,
  sprint: 5, goal: 2500,
};
const P = Object.assign({}, DEFAULTS, LS.get('prefs', {}));
const saveP = () => LS.set('prefs', P);

const THEMES = [['night', 'Nacht'], ['black', 'Schwarz'], ['sepia', 'Sepia'], ['light', 'Hell']];
const ACCENTS = ['#ff7a45', '#ff5470', '#ffc53d', '#4fd69c', '#4da3ff', '#b08cff'];
const FONTS = [
  ['Sans', 'Inter, system-ui, sans-serif'],
  ['Lexend', 'Lexend, system-ui, sans-serif'],
  ['Atkinson', '"Atkinson Hyperlegible", system-ui, sans-serif'],
  ['Serif', 'Literata, Georgia, serif'],
  ['Mono', 'ui-monospace, Menlo, Consolas, monospace'],
];

function mixHex(a, b, t) {
  const p = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
  const x = p(a), y = p(b);
  return '#' + x.map((v, i) => Math.round(v * (1 - t) + y[i] * t).toString(16).padStart(2, '0')).join('');
}
function applyTheme() {
  const theme = THEMES[P.theme] ? P.theme : 0;
  const root = document.documentElement;
  root.dataset.theme = THEMES[theme][0];
  const raw = ACCENTS[P.accent] || ACCENTS[0];
  root.style.setProperty('--accent', theme >= 2 ? mixHex(raw, '#000000', 0.22) : raw);
  root.style.setProperty('--font', (FONTS[P.font] || FONTS[0])[1]);
  const meta = document.querySelector('meta[name=theme-color]');
  if (meta) meta.content = getComputedStyle(root).getPropertyValue('--bg').trim();
}

// ================= stats =================
const Stats = {
  buf: 0,
  day() { const d = new Date(); return Math.floor((d - d.getTimezoneOffset() * 60000) / 86400000); },
  add(n) { if (n > 0) { this.buf += n; if (this.buf >= 40) this.flush(); } },
  flush() {
    if (!this.buf) return;
    const s = LS.get('stats', { day: -1, today: 0, streak: 0, total: 0 });
    const day = this.day();
    if (s.day !== day) { s.streak = s.day === day - 1 ? s.streak + 1 : 1; s.today = 0; s.day = day; }
    s.today += this.buf; s.total += this.buf; this.buf = 0;
    LS.set('stats', s);
  },
  today() { const s = LS.get('stats', { day: -1 }); return (s.day === this.day() ? s.today : 0) + this.buf; },
  streak() { const s = LS.get('stats', { day: -1 }); const d = this.day(); return s.day === d || s.day === d - 1 ? s.streak : (this.buf ? 1 : 0); },
};

// ================= text =================
const CLOSERS = '"\'”“„»«›‹)]’';
const ABBR = new Set(['z.b.', 'd.h.', 'u.a.', 'o.ä.', 'u.ä.', 'z.t.', 's.o.', 's.u.', 'bzw.', 'ca.', 'dr.', 'prof.', 'nr.', 'st.', 'vgl.', 'evtl.',
  'ggf.', 'inkl.', 'exkl.', 'bspw.', 'sog.', 'mr.', 'mrs.', 'ms.', 'vs.', 'etc.', 'abb.', 'kap.', 's.', 'e.g.', 'i.e.']);
const LD = /[\p{L}\p{N}]/u;

function core(w) { let e = w.length; while (e > 0 && CLOSERS.includes(w[e - 1])) e--; return w.slice(0, e); }
function endsSentence(w) {
  const c = core(w);
  if (!c) return false;
  const l = c[c.length - 1];
  if (l === '!' || l === '?' || l === '…') return true;
  if (l !== '.') return false;
  if (c.endsWith('...')) return true;
  if (ABBR.has(c.toLowerCase())) return false;
  const b = c.slice(0, -1);
  if (b.length === 1 && /\p{L}/u.test(b)) return false;
  if (b.length > 0 && b.length <= 2 && /^\d+$/.test(b)) return false;
  return true;
}
/** Focus letter (optimal recognition point): a little left of the middle. */
function orp(w) {
  let s = 0; while (s < w.length && !LD.test(w[s])) s++;
  if (s >= w.length) return 0;
  let e = w.length; while (e > s && !LD.test(w[e - 1])) e--;
  const n = e - s;
  return s + (n <= 1 ? 0 : n <= 5 ? 1 : n <= 9 ? 2 : n <= 13 ? 3 : 4);
}

class Doc {
  constructor(words, paraEnd) { this.words = words; this.paraEnd = paraEnd; this.size = words.length; }
  isEnd(i) { return this.paraEnd[i] === 1 || endsSentence(this.words[i]); }
  sentenceStart(i) { let k = Math.max(0, Math.min(i, this.size - 1)), n = 0; while (k > 0 && !this.isEnd(k - 1) && n++ < 300) k--; return k; }
  sentenceEnd(i) { let k = Math.max(0, Math.min(i, this.size - 1)), n = 0; while (k < this.size - 1 && !this.isEnd(k) && n++ < 300) k++; return k + 1; }
}

function parse(raw) {
  const text = raw.replace(/\r\n?/g, '\n').replace(/[­​﻿]/g, '').replace(/ /g, ' ');
  const blank = /\n[ \t]*\n\s*/;
  const paras = blank.test(text) ? text.split(blank) : text.split('\n');
  const words = [], ends = [];
  for (const p of paras) {
    const before = words.length;
    for (const w of p.trim().split(/\s+/)) if (w) words.push(w);
    if (words.length > before) ends.push(words.length - 1);
  }
  const paraEnd = new Uint8Array(words.length);
  for (const i of ends) paraEnd[i] = 1;
  return new Doc(words, paraEnd);
}

/** How much longer than normal word i stays (1 = normal). */
function factor(doc, i) {
  const w = doc.words[i];
  let f = 1;
  if (P.longSlow) {
    if (w.length > 8) f += Math.min(0.6, (w.length - 8) * 0.045);
    if (/\d/.test(w)) f += 0.3;
  }
  const s = P.pause * 0.5;
  if (s > 0) {
    const c = core(w), l = c[c.length - 1] || ' ';
    f += s * (doc.paraEnd[i] ? 1.6 : endsSentence(w) ? 1.2 : ',;:'.includes(l) ? 0.55 : (l === '–' || l === '—' || w === '-') ? 0.4 : 0);
  }
  return f;
}

/** Start indexes of the focus-mode blocks: about `target` words, never cut inside a sentence. */
function blocks(doc, target) {
  const starts = [0];
  let s = 0;
  for (let i = 0; i < doc.size - 1; i++) {
    const n = i - s + 1;
    if ((doc.paraEnd[i] && n >= target / 3) || (doc.isEnd(i) && n >= target) || n >= target * 2) { s = i + 1; starts.push(s); }
  }
  return starts;
}

// ================= library =================
const books = () => LS.get('books', []);
const saveBooks = b => LS.set('books', b);
const findBook = id => books().find(b => b.id === id);
function updateBook(id, patch) { const all = books(); const b = all.find(x => x.id === id); if (b) { Object.assign(b, patch); saveBooks(all); } }

let docCache = { id: null, doc: null };
async function loadDoc(id) {
  if (docCache.id === id) return docCache.doc;
  const text = await getText(id);
  const doc = parse(text && text.trim() ? text : 'Dieser Text ist leer.');
  docCache = { id, doc };
  return doc;
}

async function addBook(title, text) {
  const doc = parse(text);
  if (!doc.size) return null;
  const now = Date.now();
  const book = { id: now.toString(36) + Math.random().toString(36).slice(2, 6), title: (title.trim() || doc.words.slice(0, 6).join(' ')).slice(0, 90), words: doc.size, pos: 0, added: now, last: now };
  await putText(book.id, text);
  saveBooks([...books(), book]);
  docCache = { id: book.id, doc };
  return book;
}

const SAMPLE = `Willkommen bei Lesefluss. Diese App hat zwei Arten zu lesen, und du kannst jederzeit zwischen ihnen wechseln.

Der Speed-Modus zeigt dir immer nur ein Wort, immer an derselben Stelle. Deine Augen müssen nicht mehr über die Zeile springen, und genau das spart die meiste Zeit. Ein Buchstabe ist farbig: Schau nur auf ihn, dann erkennt dein Gehirn das ganze Wort von selbst. Tippe in die Mitte für Start und Pause. Tippe links, um einen Satz zurückzuspringen, und rechts, um einen Satz zu überspringen. Am Computer geht das auch mit der Leertaste und den Pfeiltasten.

Fang langsam an. 250 bis 300 Wörter pro Minute sind ein guter Start. Wenn es sich leicht anfühlt, geh in kleinen Schritten höher. Nach Punkten und Kommas macht die App von selbst eine kurze Pause, damit der Satz bei dir ankommt.

Der Fokus-Modus ist für Tage, an denen die Konzentration schnell weg ist. Du siehst nur einen kleinen Abschnitt, nie eine ganze volle Seite. Ein Satz ist hell, der Rest ist gedimmt. Tippe einfach, und der nächste Satz wird hell. Die fetten Wortanfänge geben deinen Augen einen Halt, damit sie nicht in der Zeile verrutschen. Wenn du magst, läuft ein Lesezeiger in deinem Tempo durch den Text und zieht dich mit.

Lies in Sprints. Fünf Minuten sind kurz genug, um einfach anzufangen. Danach sagt dir die App, wie viel du geschafft hast, und du entscheidest, ob du weitermachst. Kleine Etappen sind leichter als ein großer Berg.

In den Einstellungen kannst du alles an dich anpassen: Farben, Schrift, Größe, Tempo, Pausen und dein Tagesziel. Eigene Texte fügst du in der Bibliothek ein, oder du öffnest eine Datei als TXT, EPUB oder PDF. Alles bleibt in deinem Browser, nichts wird hochgeladen. Viel Spaß beim Lesen.`;

// ================= import =================
const MAX_BYTES = 60 * 1024 * 1024;

async function importFile(file) {
  if (file.size > MAX_BYTES) throw new Error('Die Datei ist zu groß');
  const name = file.name || 'Text';
  const lower = name.toLowerCase();
  const title = name.replace(/\.[^.]+$/, '');
  const buf = await file.arrayBuffer();
  if (lower.endsWith('.pdf') || file.type === 'application/pdf') return { title, text: await pdfText(buf) };
  if (lower.endsWith('.epub') || file.type === 'application/epub+zip') return epubText(buf, title);
  const txt = decode(new Uint8Array(buf));
  if (/\.html?$/.test(lower) || file.type === 'text/html') return { title, text: htmlText(txt) };
  return { title, text: txt };
}

function decode(bytes) {
  const utf8 = new TextDecoder('utf-8').decode(bytes);
  // Old German text files are often Windows-1252: UTF-8 decoding then leaves replacement characters.
  return (utf8.match(/�/g) || []).length > 2 ? new TextDecoder('windows-1252').decode(bytes) : utf8;
}

/** HTML to plain text that keeps paragraphs. Parsed in an inert document: scripts never run. */
function htmlText(src) {
  const d = new DOMParser().parseFromString(src, 'text/html');
  d.querySelectorAll('script,style,head,noscript,svg').forEach(e => e.remove());
  d.querySelectorAll('br').forEach(e => e.replaceWith('\n'));
  d.querySelectorAll('p,div,h1,h2,h3,h4,h5,h6,li,blockquote,tr,section,article,pre,dd,dt,figcaption').forEach(e => e.append('\n\n'));
  return (d.body ? d.body.textContent : '').replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n');
}

/** Minimal ZIP reader (central directory + deflate via DecompressionStream), enough for EPUB. */
function unzip(buf) {
  const dv = new DataView(buf);
  let eocd = -1;
  for (let i = buf.byteLength - 22; i >= Math.max(0, buf.byteLength - 65557); i--) if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  if (eocd < 0) throw new Error('Das ist kein lesbares EPUB');
  const n = dv.getUint16(eocd + 10, true);
  let off = dv.getUint32(eocd + 16, true);
  const files = new Map();
  const td = new TextDecoder();
  for (let k = 0; k < n; k++) {
    if (dv.getUint32(off, true) !== 0x02014b50) break;
    const method = dv.getUint16(off + 10, true), size = dv.getUint32(off + 20, true);
    const nl = dv.getUint16(off + 28, true), xl = dv.getUint16(off + 30, true), cl = dv.getUint16(off + 32, true);
    const local = dv.getUint32(off + 42, true);
    files.set(td.decode(new Uint8Array(buf, off + 46, nl)), { method, size, local });
    off += 46 + nl + xl + cl;
  }
  return {
    names: [...files.keys()],
    async read(name) {
      const f = files.get(name);
      if (!f) return null;
      const start = f.local + 30 + dv.getUint16(f.local + 26, true) + dv.getUint16(f.local + 28, true);
      const data = new Uint8Array(buf, start, f.size);
      if (f.method === 0) return td.decode(data);
      if (f.method !== 8) return null;
      const stream = new Blob([data]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
      return td.decode(await new Response(stream).arrayBuffer());
    },
  };
}

async function epubText(buf, fallback) {
  const zip = unzip(buf);
  const container = await zip.read('META-INF/container.xml');
  const xml = s => new DOMParser().parseFromString(s, 'application/xml');
  let opfPath = container ? xml(container).querySelector('rootfile')?.getAttribute('full-path') : null;
  if (!opfPath) opfPath = zip.names.find(n => n.toLowerCase().endsWith('.opf'));
  const opfSrc = opfPath && await zip.read(opfPath);
  if (!opfSrc) throw new Error('Das ist kein lesbares EPUB');
  const opf = xml(opfSrc);
  const base = opfPath.includes('/') ? opfPath.slice(0, opfPath.lastIndexOf('/') + 1) : '';
  const items = new Map();
  for (const it of opf.getElementsByTagNameNS('*', 'item')) items.set(it.getAttribute('id'), it.getAttribute('href'));
  const parts = [];
  for (const ref of opf.getElementsByTagNameNS('*', 'itemref')) {
    const href = items.get(ref.getAttribute('idref'));
    if (!href) continue;
    const path = normPath(base + decodeURIComponent(href.split('#')[0]));
    const src = await zip.read(path);
    if (src) { const t = htmlText(src).trim(); if (t) parts.push(t); }
  }
  if (!parts.length) throw new Error('In diesem EPUB wurde kein Text gefunden (vielleicht kopiergeschützt)');
  const t = opf.getElementsByTagNameNS('*', 'title')[0]?.textContent?.trim();
  return { title: t || fallback, text: parts.join('\n\n') };
}
function normPath(p) {
  const out = [];
  for (const s of p.split('/')) { if (s === '..') out.pop(); else if (s && s !== '.') out.push(s); }
  return out.join('/');
}

const PDFJS = 'https://cdn.jsdelivr.net/npm/pdfjs-dist@4.4.168/build/';
async function pdfText(buf) {
  const pdfjs = await import(PDFJS + 'pdf.min.mjs');
  pdfjs.GlobalWorkerOptions.workerSrc = PDFJS + 'pdf.worker.min.mjs';
  const pdf = await pdfjs.getDocument({ data: buf, isEvalSupported: false }).promise;
  const lines = [];
  for (let p = 1; p <= pdf.numPages; p++) {
    const content = await (await pdf.getPage(p)).getTextContent();
    let line = '';
    for (const it of content.items) {
      line += it.str;
      if (it.hasEOL) { lines.push(line.trim()); line = ''; }
    }
    if (line.trim()) lines.push(line.trim());
    lines.push('');
  }
  const text = unwrap(lines);
  if (!text.trim()) throw new Error('In diesem PDF steht kein Text (vermutlich nur Bilder)');
  return text;
}
/** PDF lines back into paragraphs: a paragraph ends at an empty line or a clearly short line. */
function unwrap(lines) {
  const lens = lines.map(l => l.length).filter(n => n > 20).sort((a, b) => a - b);
  const full = lens.length ? lens[Math.floor(lens.length * 3 / 4)] : 60;
  let out = '', open = false;
  for (const line of lines) {
    if (!line) { if (open) { out += '\n\n'; open = false; } continue; }
    if (open) {
      if (/\p{L}-$/u.test(out) && /^\p{Ll}/u.test(line)) out = out.slice(0, -1); else out += ' ';
    }
    out += line; open = true;
    if (line.length < full * 0.72 && (endsSentence(line.split(' ').pop()) || line.length < full * 0.4)) { out += '\n\n'; open = false; }
  }
  return out;
}

// ================= ui helpers =================
const app = document.getElementById('app');
function el(tag, props, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'html') e.innerHTML = v;
    else if (k === 'style') e.style.cssText = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const k of kids.flat()) if (k != null && k !== false) e.append(k.nodeType ? k : String(k));
  return e;
}
const svg = d => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${d}</svg>`;
const IC = {
  play: svg('<path d="M8 4.5 20 12 8 19.5z" fill="currentColor" stroke="none"/>'),
  pause: svg('<rect x="6" y="5" width="4.5" height="14" rx="1.2" fill="currentColor" stroke="none"/><rect x="13.5" y="5" width="4.5" height="14" rx="1.2" fill="currentColor" stroke="none"/>'),
  prev: svg('<rect x="5" y="6" width="2.4" height="12" rx="1" fill="currentColor" stroke="none"/><path d="M19 6 9.5 12 19 18z" fill="currentColor" stroke="none"/>'),
  next: svg('<rect x="16.6" y="6" width="2.4" height="12" rx="1" fill="currentColor" stroke="none"/><path d="M5 6 14.5 12 5 18z" fill="currentColor" stroke="none"/>'),
  back: svg('<path d="M14.5 5 7.5 12l7 7"/>'),
  tune: svg('<path d="M4 7h16M4 12h16M4 17h16" stroke-width="1.9"/><circle cx="9" cy="7" r="2.5" fill="currentColor" stroke="none"/><circle cx="15.5" cy="12" r="2.5" fill="currentColor" stroke="none"/><circle cx="8" cy="17" r="2.5" fill="currentColor" stroke="none"/>'),
  plus: svg('<path d="M12 5.5v13M5.5 12h13"/>'),
  minus: svg('<path d="M5.5 12h13"/>'),
  more: svg('<circle cx="5.5" cy="12" r="1.9" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.9" fill="currentColor" stroke="none"/><circle cx="18.5" cy="12" r="1.9" fill="currentColor" stroke="none"/>'),
};
const iconBtn = (icon, label, onclick) => el('button', { class: 'icon-btn', 'aria-label': label, title: label, html: IC[icon], onclick });
const num = n => n.toLocaleString('de-DE');
function minutes(words, wpm) { const m = Math.ceil(words / Math.max(1, wpm)); return m >= 60 ? `${Math.floor(m / 60)} Std ${m % 60} Min` : `${m} Min`; }

function toast(msg) {
  document.querySelectorAll('.toast').forEach(t => t.remove());
  const t = el('div', { class: 'toast', role: 'status' }, msg);
  document.body.append(t);
  setTimeout(() => t.remove(), 3500);
}

function overlay(title, body, primary, onPrimary, secondary, onSecondary) {
  const o = el('div', { class: 'overlay', role: 'dialog', 'aria-modal': 'true' },
    el('div', { class: 'panel' },
      el('h2', {}, title), el('p', {}, body),
      el('div', { class: 'row' },
        secondary ? el('button', { class: 'btn', onclick: () => { o.remove(); onSecondary(); } }, secondary) : null,
        el('button', { class: 'btn primary', onclick: () => { o.remove(); onPrimary(); } }, primary))));
  document.body.append(o);
  o.querySelector('.btn.primary').focus();
  return o;
}

let wakeLock = null;
async function keepAwake(on) {
  try {
    if (on && !wakeLock && navigator.wakeLock) { wakeLock = await navigator.wakeLock.request('screen'); wakeLock.addEventListener('release', () => { wakeLock = null; }); }
    else if (!on && wakeLock) { await wakeLock.release(); wakeLock = null; }
  } catch { /* not allowed here */ }
}

/** A reading sprint: counts reading time and words until the set minutes are over. */
class Sprint {
  constructor(min) { this.min = min; this.sec = 0; this.words = 0; }
  get on() { return this.min > 0; }
  get done() { return this.on && this.sec >= this.min * 60; }
  label() { const l = Math.max(0, this.min * 60 - this.sec); return this.on ? `${Math.floor(l / 60)}:${String(l % 60).padStart(2, '0')}` : ''; }
  summary() { return `${this.min} Minuten konzentriert gelesen.\n${num(this.words)} Wörter geschafft.`; }
}

// ================= router =================
let cleanup = null;
async function route() {
  if (cleanup) { const c = cleanup; cleanup = null; c(); }
  document.querySelectorAll('.overlay,.menu').forEach(e => e.remove());
  applyTheme();
  const [, page, id] = (location.hash.slice(1) || '/').split('/');
  window.scrollTo(0, 0);
  if (page === 'speed' || page === 'focus') {
    const book = findBook(id);
    if (!book) { location.replace('#/'); return; }
    app.replaceChildren(el('div', { class: 'reader' }));
    const doc = await loadDoc(id);
    if (page === 'speed') speedScreen(book, doc); else focusScreen(book, doc);
  } else if (page === 'settings') settingsScreen();
  else if (page === 'new') newScreen();
  else libraryScreen();
}
window.addEventListener('hashchange', route);
const go = h => { location.hash = h; };
const goBack = () => { if (history.length > 1 && sessionStorage.getItem('lf.nav')) history.back(); else go('#/'); };
window.addEventListener('hashchange', () => { try { sessionStorage.setItem('lf.nav', '1'); } catch { /* ignore */ } });

// ================= library screen =================
function libraryScreen() {
  const today = Stats.today(), streak = Stats.streak(), goal = P.goal;
  let stat = today ? `Heute ${num(today)} Wörter` : 'Heute noch nichts gelesen';
  if (streak > 1) stat += `  ·  ${streak} Tage in Folge`;
  const list = books().sort((a, b) => b.last - a.last);

  const card = b => {
    const done = b.words > 0 && b.pos >= b.words - 1;
    const pct = b.words <= 1 ? 0 : Math.round(b.pos * 100 / (b.words - 1));
    const left = Math.max(0, b.words - b.pos);
    const meta = done ? `${num(b.words)} Wörter  ·  fertig gelesen`
      : b.pos === 0 ? `${num(b.words)} Wörter  ·  etwa ${minutes(left, P.wpm)}`
        : `${pct} %  ·  noch etwa ${minutes(left, P.wpm)}`;
    const open = mode => () => { updateBook(b.id, { last: Date.now(), pos: done ? 0 : b.pos }); go(`#/${mode}/${b.id}`); };
    return el('article', { class: 'card' },
      el('div', { class: 'card-top' }, el('h2', {}, b.title), iconBtn('more', 'Mehr', e => bookMenu(b, e.currentTarget))),
      el('div', { class: 'meta' }, meta),
      el('div', { class: 'bar' }, el('i', { style: `width:${done ? 100 : pct}%` })),
      el('div', { class: 'row' },
        el('button', { class: 'btn primary', onclick: open('speed') }, 'Speed'),
        el('button', { class: 'btn', onclick: open('focus') }, 'Fokus')));
  };

  app.replaceChildren(el('main', { class: 'page' },
    el('header', { class: 'lib-head' },
      el('div', {}, el('h1', {}, 'Lesefluss'), el('p', { class: 'dim' }, stat)),
      iconBtn('tune', 'Einstellungen', () => go('#/settings'))),
    goal ? el('div', { class: 'goal' },
      el('div', { class: 'bar' }, el('i', { style: `width:${Math.min(100, today * 100 / goal)}%` })),
      el('p', { class: 'small ' + (today >= goal ? 'acc' : 'dim') },
        today >= goal ? `Tagesziel geschafft: ${num(goal)} Wörter` : `Tagesziel: noch ${num(goal - today)} von ${num(goal)} Wörtern`)) : null,
    el('div', { class: 'actions' },
      el('button', { class: 'btn primary', onclick: () => go('#/new') }, 'Text einfügen'),
      el('button', { class: 'btn', onclick: () => document.getElementById('file').click() }, 'Datei öffnen'),
      el('span', { class: 'small dim hide-sm' }, 'TXT · EPUB · PDF, oder Datei hierher ziehen')),
    list.length ? el('section', { class: 'grid' }, list.map(card))
      : el('p', { class: 'empty' }, 'Noch keine Texte. Füge einen Text ein oder öffne eine Datei.'),
    el('p', { class: 'foot' }, 'Deine Texte bleiben nur in diesem Browser auf diesem Gerät. Nichts wird hochgeladen. ',
      el('a', { href: 'https://github.com/erdinco467/lesefluss', target: '_blank', rel: 'noopener' }, 'Code auf GitHub'))),
  el('div', { class: 'drop-hint' }, 'Datei loslassen zum Öffnen'));
}

function bookMenu(b, anchor) {
  document.querySelectorAll('.menu').forEach(m => m.remove());
  const close = () => { m.remove(); document.removeEventListener('pointerdown', outside, true); };
  const outside = e => { if (!m.contains(e.target)) close(); };
  const m = el('div', { class: 'menu', role: 'menu' },
    el('button', { onclick: () => { close(); updateBook(b.id, { pos: 0 }); route(); } }, 'Von vorn beginnen'),
    el('button', { onclick: () => { close(); const t = prompt('Neuer Titel', b.title); if (t && t.trim()) { updateBook(b.id, { title: t.trim().slice(0, 90) }); route(); } } }, 'Umbenennen'),
    el('button', { class: 'danger', onclick: async () => {
      close();
      if (!confirm(`„${b.title}“ wirklich löschen? Das lässt sich nicht rückgängig machen.`)) return;
      saveBooks(books().filter(x => x.id !== b.id));
      await delText(b.id);
      if (docCache.id === b.id) docCache = { id: null, doc: null };
      route();
    } }, 'Löschen'));
  document.body.append(m);
  const r = anchor.getBoundingClientRect();
  m.style.top = Math.min(r.bottom + 4, innerHeight - m.offsetHeight - 8) + 'px';
  m.style.left = Math.max(8, Math.min(r.right - m.offsetWidth, innerWidth - m.offsetWidth - 8)) + 'px';
  setTimeout(() => document.addEventListener('pointerdown', outside, true));
}

async function handleFile(file) {
  if (!file) return;
  const busy = el('div', { class: 'overlay' }, el('div', { class: 'panel' }, el('h2', {}, 'Text wird eingelesen …')));
  document.body.append(busy);
  try {
    const r = await importFile(file);
    const book = await addBook(r.title, r.text);
    busy.remove();
    if (!book) { toast('Darin steht kein Text'); return; }
    toast(`„${book.title}“ hinzugefügt`);
    if (location.hash && location.hash !== '#/') go('#/'); else route();
  } catch (e) {
    busy.remove();
    toast(e && e.message && /[äöüß]|Datei|EPUB|PDF|Text/.test(e.message) ? e.message : 'Die Datei konnte nicht gelesen werden');
    console.error(e);
  }
}
document.getElementById('file').addEventListener('change', e => { handleFile(e.target.files[0]); e.target.value = ''; });
let dragDepth = 0;
addEventListener('dragenter', e => { if (e.dataTransfer?.types?.includes('Files')) { dragDepth++; document.body.classList.add('dragging'); } });
addEventListener('dragleave', () => { if (--dragDepth <= 0) { dragDepth = 0; document.body.classList.remove('dragging'); } });
addEventListener('dragover', e => e.preventDefault());
addEventListener('drop', e => { e.preventDefault(); dragDepth = 0; document.body.classList.remove('dragging'); handleFile(e.dataTransfer?.files?.[0]); });

// ================= new text =================
function newScreen() {
  const title = el('input', { class: 'field', placeholder: 'Titel (kann leer bleiben)', value: sessionStorage.getItem('lf.draftTitle') || '' });
  const body = el('textarea', { class: 'field', placeholder: 'Text hier einfügen oder schreiben' });
  body.value = sessionStorage.getItem('lf.draft') || '';
  const keep = () => { try { sessionStorage.setItem('lf.draft', body.value); sessionStorage.setItem('lf.draftTitle', title.value); } catch { /* ignore */ } };
  title.addEventListener('input', keep); body.addEventListener('input', keep);
  app.replaceChildren(el('div', { class: 'editor' },
    el('div', { class: 'bar-row' },
      iconBtn('back', 'Zurück', goBack),
      el('div', { class: 'title' }, 'Neuer Text'),
      navigator.clipboard?.readText ? el('button', { class: 'btn', onclick: async () => {
        try { const t = await navigator.clipboard.readText(); if (t.trim()) { body.value = t; keep(); } else toast('Die Zwischenablage ist leer'); }
        catch { toast('Kein Zugriff auf die Zwischenablage. Füge den Text mit Strg+V ein.'); }
      } }, 'Aus Zwischenablage') : null,
      el('button', { class: 'btn primary', onclick: async () => {
        const b = await addBook(title.value, body.value);
        if (!b) { toast('Der Text ist noch leer'); return; }
        try { sessionStorage.removeItem('lf.draft'); sessionStorage.removeItem('lf.draftTitle'); } catch { /* ignore */ }
        go('#/');
      } }, 'Speichern')),
    title, body));
  body.focus();
}

// ================= speed mode =================
function speedScreen(book, doc) {
  let pos = Math.min(book.pos, doc.size - 1), end = pos + 1, playing = false, ramp = 0, timer = 0, secTimer = 0;
  let sprint = new Sprint(P.sprint);

  const word = el('div', { class: 'word' });
  const gTop = el('div', { class: 'guide' }), gBot = el('div', { class: 'guide' }), tTop = el('div', { class: 'tick' }), tBot = el('div', { class: 'tick' });
  const stage = el('div', { class: 'stage' }, gTop, gBot, tTop, tBot, word);
  const clock = el('span', { class: 'clock' }, sprint.label());
  const ctx = el('div', { class: 'context' });
  const seek = el('input', { class: 'seek', type: 'range', min: 0, max: Math.max(1, doc.size - 1), value: pos, 'aria-label': 'Position im Text' });
  const left = el('span', { class: 'left' });
  const wpmEl = el('b', {});
  const playBtn = el('button', { class: 'play', 'aria-label': 'Start oder Pause', html: IC.play, onclick: () => toggle() });
  const thinBar = el('i', {});
  const root = el('div', { class: 'reader' },
    stage,
    el('div', { class: 'hints hide-sm' }, el('span', {}, 'Satz zurück'), el('span', {}, 'Tippen: Start und Pause'), el('span', {}, 'Satz vor')),
    el('div', { class: 'top' },
      iconBtn('back', 'Zurück zur Bibliothek', () => go('#/')),
      el('span', { class: 'name' }, book.title), clock,
      el('button', { class: 'btn', onclick: () => { save(); location.replace(`#/focus/${book.id}`); } }, 'Fokus-Modus'),
      iconBtn('tune', 'Einstellungen', () => go('#/settings'))),
    el('div', { class: 'bottom' },
      P.context ? ctx : null, seek,
      el('div', { class: 'controls' }, left,
        el('div', { class: 'mid' }, iconBtn('prev', 'Satz zurück', () => back()), playBtn, iconBtn('next', 'Satz vor', () => fwd())),
        el('div', { class: 'speed' }, iconBtn('minus', 'Langsamer', () => wpm(-25)), wpmEl, iconBtn('plus', 'Schneller', () => wpm(25))))),
    el('div', { class: 'thin' }, thinBar));
  app.replaceChildren(root);

  let size = 64, pivot = 0;
  function layout() {
    const w = stage.clientWidth, h = stage.clientHeight;
    size = Math.min(P.rsvpSize * 1.33, w / 7.5);
    pivot = P.orp ? w * 0.42 : w / 2;
    word.style.fontSize = size + 'px';
    const cy = h * 0.46, gap = size * 0.95, tick = size * 0.22;
    const show = P.guides ? '' : 'none';
    gTop.style.cssText = `top:${cy - gap}px;display:${show}`;
    gBot.style.cssText = `top:${cy + gap}px;display:${show}`;
    tTop.style.cssText = `top:${cy - gap}px;height:${tick}px;left:${pivot - 1}px;display:${show}`;
    tBot.style.cssText = `top:${cy + gap - tick + 2}px;height:${tick}px;left:${pivot - 1}px;display:${show}`;
    show1();
  }

  function chunkEnd(from) {
    let e = from + 1, chars = doc.words[from].length;
    while (e < doc.size && e - from < P.chunk && !doc.isEnd(e - 1)) { chars += 1 + doc.words[e].length; if (chars > 22) break; e++; }
    return e;
  }
  function delay() {
    let f = 0;
    for (let i = pos; i < end; i++) f = Math.max(f, factor(doc, i));
    let ms = 60000 / P.wpm * (0.35 + 0.65 * (end - pos)) * f;
    if (P.softStart && ramp < 8) ms *= 1 + 0.7 * (1 - ramp / 8);
    return ms;
  }
  function show1() {
    end = chunkEnd(pos);
    let text, o;
    if (end - pos === 1) { text = doc.words[pos]; o = orp(text); }
    else {
      const mid = pos + Math.floor((end - pos - 1) / 2);
      text = ''; o = 0;
      for (let i = pos; i < end; i++) { if (i > pos) text += ' '; if (i === mid) o = text.length + orp(doc.words[i]); text += doc.words[i]; }
    }
    word.style.fontSize = size + 'px';
    if (P.orp) word.replaceChildren(text.slice(0, o), el('span', { class: 'o' }, text[o] || ''), text.slice(o + 1));
    else word.replaceChildren(text);
    // Place the focus letter on the pivot; shrink words that do not fit.
    const w = stage.clientWidth, margin = w * 0.04;
    let pre = 0, mid = 0, total = word.offsetWidth;
    if (P.orp) { const s = word.querySelector('.o'); pre = s.offsetLeft; mid = s.offsetWidth; }
    let leftPx = P.orp ? pivot - pre - mid / 2 : (w - total) / 2;
    const needL = pivot - leftPx, needR = total - needL;
    const scale = Math.min(1, (pivot - margin) / Math.max(1, needL), (w - pivot - margin) / Math.max(1, needR));
    if (scale < 1) {
      word.style.fontSize = size * scale + 'px';
      total = word.offsetWidth;
      if (P.orp) { const s = word.querySelector('.o'); pre = s.offsetLeft; mid = s.offsetWidth; }
      leftPx = P.orp ? pivot - pre - mid / 2 : (w - total) / 2;
    }
    word.style.left = leftPx + 'px';
    thinBar.style.width = (doc.size <= 1 ? 100 : pos * 100 / (doc.size - 1)) + '%';
    if (!playing) info();
  }
  function info() {
    seek.value = pos;
    left.textContent = `noch ${minutes(doc.size - pos, P.wpm)}`;
    wpmEl.textContent = `${P.wpm} WpM`;
    if (P.context) {
      const s = Math.max(doc.sentenceStart(pos), pos - 30), e = Math.min(doc.sentenceEnd(pos), pos + 40);
      const parts = [];
      for (let i = s; i < e; i++) { parts.push(i >= pos && i < end ? el('b', {}, doc.words[i]) : doc.words[i]); if (i < e - 1) parts.push(' '); }
      ctx.replaceChildren(...parts);
    }
  }
  function step() {
    if (!playing) return;
    const read = end - pos;
    Stats.add(read); sprint.words += read;
    if (end >= doc.size) { pos = doc.size - 1; finished(); return; }
    pos = end;
    if (ramp < 8) ramp++;
    show1();
    if (pos % 50 < read) save();
    timer = setTimeout(step, delay());
  }
  function tick() {
    if (!playing) return;
    sprint.sec++; clock.textContent = sprint.label();
    if (sprint.done) sprintOver(); else secTimer = setTimeout(tick, 1000);
  }
  function play() {
    if (playing || document.querySelector('.overlay')) return;
    if (pos >= doc.size - 1 && doc.size > 1) pos = 0;
    if (P.rewind) { const s = doc.sentenceStart(pos); if (pos - s >= 1 && pos - s <= 14) pos = s; }
    playing = true; ramp = 0;
    root.classList.add('playing'); playBtn.innerHTML = IC.pause;
    show1();
    timer = setTimeout(step, delay());
    if (sprint.on) secTimer = setTimeout(tick, 1000);
    keepAwake(true);
  }
  function pause() {
    if (!playing) return;
    playing = false;
    clearTimeout(timer); clearTimeout(secTimer);
    root.classList.remove('playing'); playBtn.innerHTML = IC.play;
    Stats.flush(); save(); info(); keepAwake(false);
  }
  const toggle = () => (playing ? pause() : play());
  function jump(to) {
    pos = Math.max(0, Math.min(to, doc.size - 1)); ramp = 0; show1();
    if (playing) { clearTimeout(timer); timer = setTimeout(step, delay()); }
  }
  function back() { const s = doc.sentenceStart(pos); jump(pos - s <= 2 && s > 0 ? doc.sentenceStart(s - 1) : s); }
  function fwd() { jump(doc.sentenceEnd(pos)); }
  function wpm(by) { P.wpm = Math.max(100, Math.min(1000, P.wpm + by)); saveP(); wpmEl.textContent = `${P.wpm} WpM`; left.textContent = `noch ${minutes(doc.size - pos, P.wpm)}`; }
  function save() { updateBook(book.id, { pos, last: Date.now() }); }
  function sprintOver() {
    pause();
    overlay('Sprint geschafft', sprint.summary() + '\nGönn deinen Augen einen Moment.',
      'Nächster Sprint', () => { sprint = new Sprint(P.sprint); clock.textContent = sprint.label(); play(); },
      'Schluss für jetzt', () => go('#/'));
  }
  function finished() {
    pause(); save();
    overlay('Fertig gelesen', `„${book.title}“\n${num(doc.size)} Wörter. Stark.`,
      'Zur Bibliothek', () => go('#/'), 'Noch einmal', () => jump(0));
  }

  stage.addEventListener('click', e => {
    const x = e.clientX / stage.clientWidth;
    if (x < 0.24) back(); else if (x > 0.76) fwd(); else toggle();
  });
  seek.addEventListener('input', () => jump(+seek.value));
  const keys = e => {
    if (e.target.closest && e.target.closest('input[type=range]') && e.key.startsWith('Arrow')) return;
    if (document.querySelector('.overlay')) return;
    if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); toggle(); }
    else if (e.key === 'ArrowLeft') back();
    else if (e.key === 'ArrowRight') fwd();
    else if (e.key === 'ArrowUp') { e.preventDefault(); wpm(25); }
    else if (e.key === 'ArrowDown') { e.preventDefault(); wpm(-25); }
    else if (e.key === 'Escape') { if (playing) pause(); else go('#/'); }
  };
  const vis = () => { if (document.hidden) pause(); };
  addEventListener('keydown', keys);
  addEventListener('resize', layout);
  document.addEventListener('visibilitychange', vis);
  cleanup = () => { pause(); save(); Stats.flush(); removeEventListener('keydown', keys); removeEventListener('resize', layout); document.removeEventListener('visibilitychange', vis); };
  if (document.fonts) document.fonts.ready.then(() => { if (root.isConnected) layout(); });
  layout();
}

// ================= focus mode =================
function focusScreen(book, doc) {
  let pos = Math.min(book.pos, doc.size - 1), playing = false, timer = 0, secTimer = 0;
  let sprint = new Sprint(P.sprint);
  const starts = blocks(doc, P.fBlock);
  let block = -1, spans = [], lit = [0, 0], cur = null;

  const prose = el('div', { class: 'prose' });
  prose.style.setProperty('--fdim', P.fDim + '%');
  prose.style.fontSize = P.fSize + 'px';
  prose.style.lineHeight = P.fSpacing / 10;
  const scroll = el('div', { class: 'scroll' }, prose);
  const clock = el('span', { class: 'clock' }, sprint.label());
  const bar = el('i', {});
  const infoEl = el('span', { class: 'left' });
  const wpmEl = el('b', {}, `${P.fWpm} WpM`);
  const playBtn = el('button', { class: 'play', style: 'width:62px;height:62px', 'aria-label': 'Lesezeiger starten oder anhalten', html: IC.play, onclick: () => (playing ? pause() : play()) });
  app.replaceChildren(el('div', { class: 'reader' },
    el('div', { class: 'top' },
      iconBtn('back', 'Zurück zur Bibliothek', () => go('#/')),
      el('span', { class: 'name' }, book.title), clock,
      el('button', { class: 'btn', onclick: () => { save(); location.replace(`#/speed/${book.id}`); } }, 'Speed-Modus'),
      iconBtn('tune', 'Einstellungen', () => go('#/settings'))),
    scroll,
    el('div', { class: 'progress' }, bar),
    el('div', { class: 'controls' }, infoEl,
      el('div', { class: 'mid' }, iconBtn('prev', 'Satz zurück', () => prev()), playBtn, iconBtn('next', 'Nächster Satz', () => next())),
      el('div', { class: 'speed' }, iconBtn('minus', 'Langsamer', () => wpm(-20)), wpmEl, iconBtn('plus', 'Schneller', () => wpm(20))))));

  const bStart = b => starts[b];
  const bEnd = b => (b + 1 < starts.length ? starts[b + 1] : doc.size);
  function blockOf(i) { let lo = 0, hi = starts.length - 1; while (lo < hi) { const m = (lo + hi + 1) >> 1; if (starts[m] <= i) lo = m; else hi = m - 1; } return lo; }

  function wordNode(w, i) {
    const s = el('span', { 'data-i': i });
    if (P.bionic) {
      let a = 0; while (a < w.length && !LD.test(w[a])) a++;
      let z = w.length; while (z > a && !LD.test(w[z - 1])) z--;
      const n = z - a;
      if (n > 0) {
        const k = Math.min(n, Math.max(1, Math.ceil(n * P.bionicPct / 100)));
        s.append(w.slice(0, a), el('b', {}, w.slice(a, a + k)), w.slice(a + k));
        return s;
      }
    }
    s.textContent = w;
    return s;
  }
  function buildBlock(b) {
    block = b; spans = []; lit = [0, 0]; cur = null;
    const s = bStart(b), e = bEnd(b);
    const paras = [];
    let p = el('p', {});
    for (let i = s; i < e; i++) {
      const sp = wordNode(doc.words[i], i);
      spans.push(sp); p.append(sp);
      if (doc.paraEnd[i] && i < e - 1) { paras.push(p); p = el('p', {}); } else if (i < e - 1) p.append(' ');
    }
    paras.push(p);
    prose.replaceChildren(...paras);
    scroll.scrollTop = 0;
  }
  function render() {
    const b = blockOf(pos);
    if (b !== block) buildBlock(b);
    const bs = bStart(block), be = bEnd(block);
    const s = Math.max(doc.sentenceStart(pos), bs), e = Math.min(doc.sentenceEnd(pos), be);
    if (lit[0] !== s || lit[1] !== e) {
      for (let i = lit[0]; i < lit[1]; i++) spans[i - bs]?.classList.remove('on');
      for (let i = s; i < e; i++) spans[i - bs].classList.add('on');
      lit = [s, e];
      const first = spans[s - bs], last = spans[e - 1 - bs];
      const top = first.offsetTop, bottom = last.offsetTop + last.offsetHeight, view = scroll.clientHeight;
      if (top < scroll.scrollTop + view * 0.1 || bottom > scroll.scrollTop + view * 0.9) scroll.scrollTo({ top: Math.max(0, top - view * 0.3), behavior: 'smooth' });
    }
    if (cur) cur.classList.remove('cur');
    cur = playing ? spans[pos - bs] : null;
    if (cur) cur.classList.add('cur');
    bar.style.width = (doc.size <= 1 ? 100 : pos * 100 / (doc.size - 1)) + '%';
    infoEl.textContent = `Abschnitt ${block + 1} von ${starts.length}  ·  noch ${minutes(doc.size - pos, P.fWpm)}`;
  }
  const count = n => { Stats.add(n); sprint.words += n; };
  const delay = () => 60000 / P.fWpm * factor(doc, pos);
  function step() {
    if (!playing) return;
    count(1);
    if (pos >= doc.size - 1) { finished(); return; }
    pos++; render();
    if (pos % 50 === 0) save();
    timer = setTimeout(step, delay());
  }
  function moveTo(i) {
    pos = Math.max(0, Math.min(i, doc.size - 1)); render();
    if (playing) { clearTimeout(timer); timer = setTimeout(step, delay()); }
  }
  function next() {
    const e = doc.sentenceEnd(pos);
    count(e - pos);
    if (e >= doc.size) { pos = doc.size - 1; finished(); return; }
    moveTo(e);
  }
  function prev() { const s = doc.sentenceStart(pos); moveTo(pos === s && s > 0 ? doc.sentenceStart(s - 1) : s); }
  function play() {
    if (playing || document.querySelector('.overlay')) return;
    if (pos >= doc.size - 1 && doc.size > 1) pos = 0;
    playing = true; playBtn.innerHTML = IC.pause; render();
    timer = setTimeout(step, delay()); keepAwake(true);
  }
  function pause() {
    if (!playing) return;
    playing = false; clearTimeout(timer); playBtn.innerHTML = IC.play;
    render(); save(); Stats.flush(); keepAwake(false);
  }
  function wpm(by) { P.fWpm = Math.max(80, Math.min(700, P.fWpm + by)); saveP(); wpmEl.textContent = `${P.fWpm} WpM`; render(); }
  function save() { updateBook(book.id, { pos, last: Date.now() }); }
  // In this mode the sprint clock runs while the screen is open: you also read without the pointer.
  function tick() {
    if (document.hidden || document.querySelector('.overlay')) { secTimer = setTimeout(tick, 1000); return; }
    sprint.sec++; clock.textContent = sprint.label();
    if (sprint.done) {
      pause();
      overlay('Sprint geschafft', sprint.summary() + '\nSteh kurz auf oder trink einen Schluck.',
        'Nächster Sprint', () => { sprint = new Sprint(P.sprint); clock.textContent = sprint.label(); secTimer = setTimeout(tick, 1000); },
        'Schluss für jetzt', () => go('#/'));
    } else secTimer = setTimeout(tick, 1000);
  }
  function finished() {
    pause(); save(); clearTimeout(secTimer);
    overlay('Fertig gelesen', `„${book.title}“\n${num(doc.size)} Wörter. Stark.`,
      'Zur Bibliothek', () => go('#/'),
      'Noch einmal', () => { moveTo(0); if (sprint.on) secTimer = setTimeout(tick, 1000); });
  }

  scroll.addEventListener('click', e => {
    if (playing) { pause(); return; }
    if (getSelection && String(getSelection())) return;
    const sp = e.target.closest('span[data-i]');
    if (!sp) { next(); return; }
    const i = +sp.dataset.i;
    if (i >= lit[0] && i < lit[1]) next(); else moveTo(Math.max(doc.sentenceStart(i), bStart(block)));
  });
  const keys = e => {
    if (document.querySelector('.overlay')) return;
    if (e.key === ' ') { e.preventDefault(); playing ? pause() : play(); }
    else if (e.key === 'ArrowRight' || e.key === 'Enter') { e.preventDefault(); next(); }
    else if (e.key === 'ArrowLeft') prev();
    else if (e.key === 'ArrowUp') { e.preventDefault(); wpm(20); }
    else if (e.key === 'ArrowDown') { e.preventDefault(); wpm(-20); }
    else if (e.key === 'Escape') { if (playing) pause(); else go('#/'); }
  };
  const vis = () => { if (document.hidden) pause(); };
  addEventListener('keydown', keys);
  document.addEventListener('visibilitychange', vis);
  cleanup = () => { pause(); save(); Stats.flush(); clearTimeout(secTimer); removeEventListener('keydown', keys); document.removeEventListener('visibilitychange', vis); };
  render();
  if (sprint.on) secTimer = setTimeout(tick, 1000);
}

// ================= settings =================
function settingsScreen() {
  const page = el('main', { class: 'page settings' });
  const rebuild = () => { const y = scrollY; route().then(() => scrollTo(0, y)); };
  const section = (title, sub) => { const s = el('section', { class: 'section' }, el('h2', {}, title), sub ? el('p', {}, sub) : null); page.append(s); return s; };
  const chips = (sec, label, opts, sel, set) => {
    const btns = opts.map((o, i) => el('button', { class: 'chip' + (i === sel ? ' on' : ''), 'aria-pressed': i === sel ? 'true' : 'false', onclick: () => {
      btns.forEach((b, k) => { b.classList.toggle('on', k === i); b.setAttribute('aria-pressed', k === i); });
      set(i); saveP();
    } }, o));
    sec.append(el('div', { class: 'label' }, label), el('div', { class: 'chips' }, btns));
  };
  const slider = (sec, label, min, max, step, val, fmt, set) => {
    const v = el('span', {}, fmt(val));
    const r = el('input', { type: 'range', min, max, step, value: val, 'aria-label': label });
    r.addEventListener('input', () => { v.textContent = fmt(+r.value); set(+r.value); saveP(); });
    sec.append(el('label', { class: 'label' }, label, v), r);
  };
  const toggle = (sec, label, hint, val, set) => {
    const c = el('input', { type: 'checkbox', class: 'switch', role: 'switch' });
    c.checked = val;
    c.addEventListener('change', () => { set(c.checked); saveP(); });
    sec.append(el('label', { class: 'toggle' }, el('div', {}, el('strong', {}, label), el('small', {}, hint)), c));
  };

  page.append(el('div', { class: 'head' }, iconBtn('back', 'Zurück', goBack), el('h1', {}, 'Einstellungen')));

  const look = section('Aussehen');
  chips(look, 'Farbschema', THEMES.map(t => t[1]), P.theme, i => { P.theme = i; saveP(); rebuild(); });
  look.append(el('div', { class: 'label' }, 'Akzentfarbe'), el('div', { class: 'swatches' }, ACCENTS.map((c, i) =>
    el('button', { class: 'swatch' + (i === P.accent ? ' on' : ''), 'aria-label': 'Farbe ' + (i + 1), onclick: () => { P.accent = i; saveP(); rebuild(); } },
      el('i', { style: `background:${P.theme >= 2 ? mixHex(c, '#000000', 0.22) : c}` })))));
  chips(look, 'Schrift beim Lesen', FONTS.map(f => f[0]), P.font, i => { P.font = i; saveP(); applyTheme(); });

  const sp = section('Speed-Modus', 'Ein Wort nach dem anderen, immer an derselben Stelle.');
  slider(sp, 'Tempo', 100, 1000, 10, P.wpm, v => `${v} Wörter pro Minute`, v => { P.wpm = v; });
  slider(sp, 'Schriftgröße', 32, 140, 2, P.rsvpSize, v => `${v}`, v => { P.rsvpSize = v; });
  chips(sp, 'Wörter auf einmal', ['1', '2', '3'], P.chunk - 1, i => { P.chunk = i + 1; });
  chips(sp, 'Pause bei Satzzeichen', ['Aus', 'Leicht', 'Normal', 'Stark'], P.pause, i => { P.pause = i; });
  toggle(sp, 'Fokus-Buchstabe', 'Ein Buchstabe ist farbig. Schau nur auf ihn.', P.orp, v => { P.orp = v; });
  toggle(sp, 'Hilfslinien', 'Zwei feine Linien zeigen, wo der Blick bleibt.', P.guides, v => { P.guides = v; });
  toggle(sp, 'Lange Wörter langsamer', 'Lange Wörter und Zahlen bleiben etwas länger stehen.', P.longSlow, v => { P.longSlow = v; });
  toggle(sp, 'Sanfter Start', 'Die ersten Wörter kommen langsamer, dann wird es schneller.', P.softStart, v => { P.softStart = v; });
  toggle(sp, 'Zurück zum Satzanfang', 'Nach einer Pause geht es am Anfang des Satzes weiter.', P.rewind, v => { P.rewind = v; });
  toggle(sp, 'Satz bei Pause zeigen', 'In der Pause siehst du den ganzen Satz zum Wort.', P.context, v => { P.context = v; });

  const fo = section('Fokus-Modus', 'Für wenig Konzentration: kleine Abschnitte, ein heller Satz, der Rest gedimmt.');
  const preview = el('div', { class: 'preview' });
  const updatePreview = () => {
    preview.replaceChildren();
    for (const w of 'Die fetten Wortanfänge geben deinen Augen einen Halt beim Lesen.'.split(' ')) {
      const k = Math.min(w.length, Math.max(1, Math.ceil(w.replace(/\P{L}/gu, '').length * P.bionicPct / 100)));
      preview.append(P.bionic ? el('b', {}, w.slice(0, k)) : '', P.bionic ? w.slice(k) : w, ' ');
    }
    preview.style.fontSize = P.fSize + 'px';
    preview.style.lineHeight = P.fSpacing / 10;
  };
  updatePreview();
  fo.append(preview);
  toggle(fo, 'Fette Wortanfänge', 'Anker für die Augen (Bionic Reading).', P.bionic, v => { P.bionic = v; updatePreview(); });
  slider(fo, 'Wie viel vom Wort fett ist', 25, 70, 5, P.bionicPct, v => `${v} %`, v => { P.bionicPct = v; updatePreview(); });
  slider(fo, 'Schriftgröße', 16, 48, 1, P.fSize, v => `${v}`, v => { P.fSize = v; updatePreview(); });
  slider(fo, 'Zeilenabstand', 11, 24, 1, P.fSpacing, v => (v / 10).toFixed(1).replace('.', ','), v => { P.fSpacing = v; updatePreview(); });
  slider(fo, 'Länge eines Abschnitts', 20, 150, 10, P.fBlock, v => `etwa ${v} Wörter`, v => { P.fBlock = v; });
  slider(fo, 'Andere Sätze abdunkeln', 0, 85, 5, P.fDim, v => (v ? `${v} %` : 'Aus'), v => { P.fDim = v; });
  slider(fo, 'Tempo des Lesezeigers', 80, 700, 10, P.fWpm, v => `${v} Wörter pro Minute`, v => { P.fWpm = v; });

  const ha = section('Sprints und Tagesziel', 'Kurze Etappen machen das Anfangen leicht.');
  const SPR = [0, 3, 5, 10, 15, 25], GOALS = [0, 1000, 2500, 5000, 10000];
  chips(ha, 'Länge eines Lese-Sprints', SPR.map(v => (v ? `${v} Min` : 'Aus')), SPR.indexOf(P.sprint), i => { P.sprint = SPR[i]; });
  chips(ha, 'Tagesziel in Wörtern', GOALS.map(v => (v ? num(v) : 'Aus')), GOALS.indexOf(P.goal), i => { P.goal = GOALS[i]; });

  page.append(el('div', { class: 'danger-zone' }, el('button', { class: 'btn', onclick: () => {
    if (!confirm('Alle Einstellungen auf Standard zurücksetzen? Deine Texte bleiben.')) return;
    Object.assign(P, DEFAULTS); saveP(); rebuild();
  } }, 'Einstellungen zurücksetzen')));
  app.replaceChildren(page);
}

// ================= start =================
(async function start() {
  if (!LS.get('sampleDone', false)) {
    LS.set('sampleDone', true);
    if (!books().length) { try { await addBook('Willkommen bei Lesefluss', SAMPLE); } catch (e) { console.error(e); } }
  }
  if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
  addEventListener('pagehide', () => Stats.flush());
  await route();
  if ('serviceWorker' in navigator && location.protocol === 'https:') navigator.serviceWorker.register('sw.js').catch(() => {});
})();
