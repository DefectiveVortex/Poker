// botcam renderer: draws a scene captured by capture.js from one viewer's eye, the way the
// Minecraft client would, and writes a PNG. Pure node (a z-buffered software rasteriser), no deps.
//
// What it reproduces: blocks from the vanilla block models (26.3 client assets), item_display cards
// from the Playing Cards pack (item/generated quads, both faces), block_display chips, text_display
// labels in the Minecraft bitmap font with their backgrounds, other players as simple seated figures
// with name tags, and the winner glow outline. The matrices follow the client's DisplayRenderer:
//   world = T(pos) * orientation(billboard, yaw, pitch) * T(translation) * left * S(scale) * right
// then, for items, RotY(pi) * T(-0.5) around the item model. It is NOT a real client: no lighting
// beyond Minecraft's fixed face shading, no smooth lighting/AO, no entity shadows, no HUD.
//
// Usage: node render.js scene.json out.png   (or require('./render').render(scene, out))
const fs = require('fs');
const path = require('path');
const { decode, encodeRGB } = require('./png');

const VANILLA = process.env.BOTCAM_ASSETS || '/home/vortex/Poker-ops/botcam/assets';
const PACK = process.env.BOTCAM_PACK || '/home/vortex/Blackjack/resourcepack/pack/assets';
const W = Number(process.env.BOTCAM_W || 1280);
const H = Number(process.env.BOTCAM_H || 720);
const SKY_TOP = [120, 167, 255], SKY_FOG = [192, 216, 255];
const GRASS_TINT = [0x91, 0xbd, 0x59];

// ---------- vectors / quaternions ----------
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const mul = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const norm = (a) => mul(a, 1 / Math.hypot(a[0], a[1], a[2]));
function qrot(q, v) { // rotate v by unit quaternion q = [x,y,z,w]
  const [x, y, z, w] = q;
  const t = mul(cross([x, y, z], v), 2);
  return add(add(v, mul(t, w)), cross([x, y, z], t));
}
const rotX = (a) => (v) => [v[0], v[1] * Math.cos(a) - v[2] * Math.sin(a), v[1] * Math.sin(a) + v[2] * Math.cos(a)];
const rotY = (a) => (v) => [v[0] * Math.cos(a) + v[2] * Math.sin(a), v[1], -v[0] * Math.sin(a) + v[2] * Math.cos(a)];
const rotZ = (a) => (v) => [v[0] * Math.cos(a) - v[1] * Math.sin(a), v[0] * Math.sin(a) + v[1] * Math.cos(a), v[2]];

/** Look direction for Minecraft yaw/pitch in degrees (yaw 0 = +z/south, pitch > 0 = down). */
function lookDir(yaw, pitch) {
  const y = (yaw * Math.PI) / 180, p = (pitch * Math.PI) / 180;
  return [-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)];
}

// ---------- textures ----------
const texCache = new Map();
function texture(id) { // "minecraft:block/stone" or "playing_cards:item/card/hk"
  if (texCache.has(id)) return texCache.get(id);
  const [ns, p] = id.includes(':') ? id.split(':') : ['minecraft', id];
  const root = ns === 'minecraft' ? path.join(VANILLA, 'minecraft') : path.join(PACK, ns);
  let t;
  try {
    t = decode(path.join(root, 'textures', p + '.png'));
    if (t.height > t.width) t = { width: t.width, height: t.width, data: t.data.subarray(0, t.width * t.width * 4) }; // animated strip: frame 0
  } catch (e) {
    t = { width: 2, height: 2, data: new Uint8Array([255, 0, 255, 255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 0, 255, 255]), missing: id };
  }
  texCache.set(id, t);
  return t;
}
function solid(rgb, a = 255) {
  return { width: 1, height: 1, data: new Uint8Array([rgb[0], rgb[1], rgb[2], a]) };
}

// ---------- raster ----------
class Raster {
  constructor(cam) {
    this.rgb = new Float32Array(W * H * 3);
    this.depth = new Float32Array(W * H); // 1/z, 0 = far
    this.eye = cam.eye;
    this.f = norm(lookDir(cam.yaw, cam.pitch));
    this.r = norm(cross(this.f, [0, 1, 0]));
    this.u = cross(this.r, this.f);
    this.focal = H / 2 / Math.tan(((cam.fov || 70) * Math.PI) / 360);
    this.translucent = [];
    this.glowMask = new Int32Array(W * H).fill(-1);
    this.glowColors = [];
    // occlusion measurement: which tracked object owns each final pixel, and how many pixels each would cover
    this.idBuf = new Int32Array(W * H).fill(-1);
    this.ids = [];
    this.cover = [];
    for (let y = 0; y < H; y++) { // sky: fog colour at the horizon, sky colour above
      for (let x = 0; x < W; x++) {
        const d = norm(add(add(this.f, mul(this.r, (x - W / 2) / this.focal)), mul(this.u, (H / 2 - y) / this.focal)));
        const k = Math.min(1, Math.max(0, d[1] * 2.5));
        const o = (y * W + x) * 3;
        for (let c = 0; c < 3; c++) this.rgb[o + c] = SKY_FOG[c] + (SKY_TOP[c] - SKY_FOG[c]) * k;
      }
    }
  }

  toCam(p) {
    const d = sub(p, this.eye);
    return [dot(d, this.r), dot(d, this.u), dot(d, this.f)];
  }

  /**
   * A textured quad: 4 world corners in order, with uv (0..1, may exceed 1 to tile) per corner.
   * opts: shade (0..1), tint [r,g,b], blend (translucent), cull (skip if facing away), glow [r,g,b].
   */
  quad(pts, uvs, tex, opts = {}) {
    const cam = pts.map((p) => this.toCam(p));
    if (opts.cull) {
      const n = cross(sub(cam[1], cam[0]), sub(cam[2], cam[0]));
      if (dot(n, cam[0]) >= 0) return; // back face
    }
    if (opts.glow && opts.glowIndex === undefined) {
      opts = { ...opts, glowIndex: this.glowColors.length };
      this.glowColors.push(opts.glow);
    }
    const verts = cam.map((c, i) => ({ c, uv: uvs[i] }));
    if (opts.blend) {
      const zc = cam.reduce((s, c) => s + c[2], 0) / 4;
      this.translucent.push({ verts, tex, opts, zc });
      return;
    }
    this.poly(verts, tex, opts);
  }

  poly(verts, tex, opts) {
    // clip against the near plane, then fan into triangles
    const NEAR = 0.05;
    const out = [];
    for (let i = 0; i < verts.length; i++) {
      const a = verts[i], b = verts[(i + 1) % verts.length];
      const ain = a.c[2] > NEAR, bin = b.c[2] > NEAR;
      if (ain) out.push(a);
      if (ain !== bin) {
        const t = (NEAR - a.c[2]) / (b.c[2] - a.c[2]);
        out.push({ c: add(a.c, mul(sub(b.c, a.c), t)), uv: [a.uv[0] + (b.uv[0] - a.uv[0]) * t, a.uv[1] + (b.uv[1] - a.uv[1]) * t] });
      }
    }
    if (out.length < 3) return;
    const s = out.map((v) => {
      const iz = 1 / v.c[2];
      return { x: W / 2 + v.c[0] * iz * this.focal, y: H / 2 - v.c[1] * iz * this.focal, iz, u: v.uv[0] * iz, v: v.uv[1] * iz };
    });
    for (let i = 1; i + 1 < s.length; i++) this.tri(s[0], s[i], s[i + 1], tex, opts);
  }

  tri(a, b, c, tex, opts) {
    const area = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    if (Math.abs(area) < 1e-9) return;
    const x0 = Math.max(0, Math.floor(Math.min(a.x, b.x, c.x))), x1 = Math.min(W - 1, Math.ceil(Math.max(a.x, b.x, c.x)));
    const y0 = Math.max(0, Math.floor(Math.min(a.y, b.y, c.y))), y1 = Math.min(H - 1, Math.ceil(Math.max(a.y, b.y, c.y)));
    const shade = opts.shade ?? 1, tint = opts.tint, blend = !!opts.blend;
    const tw = tex.width, th = tex.height, td = tex.data;
    for (let y = y0; y <= y1; y++) {
      const py = y + 0.5;
      for (let x = x0; x <= x1; x++) {
        const px = x + 0.5;
        let w0 = ((b.x - px) * (c.y - py) - (b.y - py) * (c.x - px)) / area;
        let w1 = ((c.x - px) * (a.y - py) - (c.y - py) * (a.x - px)) / area;
        let w2 = 1 - w0 - w1;
        if (w0 < -1e-7 || w1 < -1e-7 || w2 < -1e-7) continue;
        const iz = w0 * a.iz + w1 * b.iz + w2 * c.iz;
        const o = y * W + x;
        const u = (w0 * a.u + w1 * b.u + w2 * c.u) / iz, v = (w0 * a.v + w1 * b.v + w2 * c.v) / iz;
        const tx = Math.floor((u - Math.floor(u)) * tw), ty = Math.floor((v - Math.floor(v)) * th);
        const t = (Math.min(th - 1, ty) * tw + Math.min(tw - 1, tx)) * 4;
        // the glow outline is drawn through everything, like the client's outline pass
        if (opts.glowIndex !== undefined && td[t + 3] >= 26) this.glowMask[o] = opts.glowIndex;
        if (opts.id !== undefined && td[t + 3] >= 26) this.cover[opts.id]++;
        if (iz <= this.depth[o]) continue;
        let alpha = td[t + 3] / 255;
        if (!blend && alpha >= 0.1) this.idBuf[o] = opts.id ?? -1;
        else if (blend && alpha >= 0.5) this.idBuf[o] = -1; // opaque text glyphs hide what's behind
        if (!blend && alpha < 0.1) continue; // cutout
        if (!blend) alpha = 1;
        if (alpha <= 0) continue;
        for (let k = 0; k < 3; k++) {
          const col = td[t + k] * shade * (tint ? tint[k] / 255 : 1);
          this.rgb[o * 3 + k] = this.rgb[o * 3 + k] * (1 - alpha) + col * alpha;
        }
        if (!blend) this.depth[o] = iz;
      }
    }
  }

  /** Register an object whose visibility is measured; returns its id for quad opts. */
  track(name) { this.ids.push(name); this.cover.push(0); return this.ids.length - 1; }

  visibility() {
    const seen = new Array(this.ids.length).fill(0);
    for (const id of this.idBuf) if (id >= 0) seen[id]++;
    return this.ids.map((name, i) => ({ name, cover: this.cover[i], visible: this.cover[i] ? seen[i] / this.cover[i] : null }));
  }

  finish() {
    this.translucent.sort((p, q) => q.zc - p.zc); // back to front
    for (const t of this.translucent) this.poly(t.verts, t.tex, t.opts);
    this.outline();
  }

  outline() { // pixels just outside a glowing silhouette (2 px, scaled from the client's ~1 px at 854x480)
    const m = this.glowMask;
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      if (m[y * W + x] >= 0) continue;
      let hit = -1;
      for (let dy = -2; dy <= 2 && hit < 0; dy++) for (let dx = -2; dx <= 2; dx++) {
        const X = x + dx, Y = y + dy;
        if (X >= 0 && Y >= 0 && X < W && Y < H && m[Y * W + X] >= 0) { hit = m[Y * W + X]; break; }
      }
      if (hit >= 0) for (let k = 0; k < 3; k++) this.rgb[(y * W + x) * 3 + k] = this.glowColors[hit][k];
    }
  }

  /** Paint an RGBA image into screen space at (x, y), integer scale. */
  blit(img, x, y, scale = 1) {
    for (let j = 0; j < img.height * scale; j++) for (let i = 0; i < img.width * scale; i++) {
      const X = x + i, Y = y + j;
      if (X < 0 || Y < 0 || X >= W || Y >= H) continue;
      const t = (Math.floor(j / scale) * img.width + Math.floor(i / scale)) * 4, a = img.data[t + 3] / 255;
      for (let k = 0; k < 3; k++) this.rgb[(Y * W + X) * 3 + k] = this.rgb[(Y * W + X) * 3 + k] * (1 - a) + img.data[t + k] * a;
    }
  }

  save(file) {
    const out = new Uint8Array(W * H * 3);
    for (let i = 0; i < out.length; i++) out[i] = Math.max(0, Math.min(255, Math.round(this.rgb[i])));
    encodeRGB(file, W, H, out);
  }
}

// ---------- block models ----------
const FACE_SHADE = { up: 1, down: 0.5, north: 0.8, south: 0.8, east: 0.6, west: 0.6 };
const DIRS = { up: [0, 1, 0], down: [0, -1, 0], north: [0, 0, -1], south: [0, 0, 1], west: [-1, 0, 0], east: [1, 0, 0] };
const modelCache = new Map();
function readJson(file) { return JSON.parse(fs.readFileSync(file, 'utf8')); }
function loadModel(id) { // -> {elements, textures}
  id = id.replace(/^minecraft:/, '');
  if (modelCache.has(id)) return modelCache.get(id);
  let m = { textures: {}, elements: null };
  try {
    const j = readJson(path.join(VANILLA, 'minecraft/models', id + '.json'));
    const parent = j.parent ? loadModel(j.parent) : { textures: {}, elements: null };
    m = { textures: { ...parent.textures, ...(j.textures || {}) }, elements: j.elements || parent.elements };
  } catch (e) { /* unknown model: nothing drawn */ }
  modelCache.set(id, m);
  return m;
}
function resolveTex(textures, ref, depth = 0) {
  if (!ref) return null;
  if (ref.startsWith('#')) return depth > 8 ? null : resolveTex(textures, textures[ref.slice(1)], depth + 1);
  return ref.includes(':') ? ref : 'minecraft:' + ref;
}
function matchWhen(when, props) {
  if (!when) return true;
  if (when.OR) return when.OR.some((w) => matchWhen(w, props));
  if (when.AND) return when.AND.every((w) => matchWhen(w, props));
  return Object.entries(when).every(([k, v]) => String(v).split('|').includes(String(props[k])));
}
const stateCache = new Map();
/** The models (with x/y rotation) a block state renders. */
function blockModels(name, props = {}) {
  name = name.replace(/^minecraft:/, '');
  let bs = stateCache.get(name);
  if (bs === undefined) {
    try { bs = readJson(path.join(VANILLA, 'minecraft/blockstates', name + '.json')); } catch (e) { bs = null; }
    stateCache.set(name, bs);
  }
  if (!bs) return [];
  const pick = (v) => (Array.isArray(v) ? v[0] : v);
  if (bs.variants) {
    for (const [key, v] of Object.entries(bs.variants)) {
      const ok = key === '' || key.split(',').every((kv) => { const [k, val] = kv.split('='); return String(props[k]) === val; });
      if (ok) return [pick(v)];
    }
    return [pick(Object.values(bs.variants)[0])];
  }
  return (bs.multipart || []).filter((p) => matchWhen(p.when, props)).map((p) => pick(p.apply));
}
function isFullCube(name, props) {
  const ms = blockModels(name, props);
  if (ms.length !== 1) return false;
  const els = loadModel(ms[0].model).elements;
  return !!els && els.length === 1 && els[0].from.join() === '0,0,0' && els[0].to.join() === '16,16,16'
    && !/glass|leaves|ice|barrier|slime|honey/.test(name);
}

// Face corners in model units, ordered texture TL, TR, BR, BL.
function faceCorners(dir, f, t) {
  const [x1, y1, z1] = f, [x2, y2, z2] = t;
  switch (dir) {
    case 'up': return [[x1, y2, z1], [x2, y2, z1], [x2, y2, z2], [x1, y2, z2]];
    case 'down': return [[x1, y1, z2], [x2, y1, z2], [x2, y1, z1], [x1, y1, z1]];
    case 'north': return [[x2, y2, z1], [x1, y2, z1], [x1, y1, z1], [x2, y1, z1]];
    case 'south': return [[x1, y2, z2], [x2, y2, z2], [x2, y1, z2], [x1, y1, z2]];
    case 'west': return [[x1, y2, z1], [x1, y2, z2], [x1, y1, z2], [x1, y1, z1]];
    case 'east': return [[x2, y2, z2], [x2, y2, z1], [x2, y1, z1], [x2, y1, z2]];
  }
}
function defaultUV(dir, f, t) {
  const [x1, y1, z1] = f, [x2, y2, z2] = t;
  switch (dir) {
    case 'up': return [x1, z1, x2, z2];
    case 'down': return [x1, 16 - z2, x2, 16 - z1];
    case 'north': return [16 - x2, 16 - y2, 16 - x1, 16 - y1];
    case 'south': return [x1, 16 - y2, x2, 16 - y1];
    case 'west': return [z1, 16 - y2, z2, 16 - y1];
    case 'east': return [16 - z2, 16 - y2, 16 - z1, 16 - y1];
  }
}

/**
 * Draw a block model. `place(p)` maps a model-space point (0..16) to world space.
 * `neighbourFull(dir)` says whether a face with that cullface is hidden.
 */
function drawBlock(R, name, props, place, neighbourFull = () => false, opts = {}) {
  for (const variant of blockModels(name, props)) {
    const model = loadModel(variant.model);
    if (!model.elements) continue;
    const rx = ((variant.x || 0) * Math.PI) / 180, ry = ((variant.y || 0) * Math.PI) / 180;
    const vrot = (p) => { // blockstate rotation about the block centre (x first, then y; y turns north -> east)
      let q = sub(p, [8, 8, 8]);
      if (rx) q = [q[0], q[1] * Math.cos(rx) - q[2] * Math.sin(rx), q[1] * Math.sin(rx) + q[2] * Math.cos(rx)];
      if (ry) q = [q[0] * Math.cos(ry) - q[2] * Math.sin(ry), q[1], q[0] * Math.sin(ry) + q[2] * Math.cos(ry)];
      return add(q, [8, 8, 8]);
    };
    for (const el of model.elements) {
      let erot = (p) => p;
      if (el.rotation && el.rotation.angle) {
        const a = (el.rotation.angle * Math.PI) / 180, o = el.rotation.origin;
        const fn = { x: rotX, y: rotY, z: rotZ }[el.rotation.axis](a);
        erot = (p) => add(fn(sub(p, o)), o);
      }
      for (const [dir, face] of Object.entries(el.faces || {})) {
        const tex = texture(resolveTex(model.textures, face.texture) || 'minecraft:missing');
        let pts = faceCorners(dir, el.from, el.to).map((p) => vrot(erot(p)));
        if (face.cullface) {
          // the cullface turns with the block state
          const n = vrot(add(DIRS[face.cullface], [8, 8, 8]));
          const d = Object.keys(DIRS).find((k) => DIRS[k].every((c, i) => Math.abs(c - (n[i] - 8)) < 0.01));
          if (d && neighbourFull(d)) continue;
        }
        const uv = face.uv || defaultUV(dir, el.from, el.to);
        let uvs = [[uv[0], uv[1]], [uv[2], uv[1]], [uv[2], uv[3]], [uv[0], uv[3]]].map(([u, v]) => [u / 16, v / 16]);
        for (let r = 0; r < ((face.rotation || 0) / 90) % 4; r++) uvs = [uvs[3], uvs[0], uvs[1], uvs[2]];
        const world = pts.map(place);
        const nrm = norm(cross(sub(world[1], world[0]), sub(world[3], world[0])));
        const axis = Object.keys(DIRS).reduce((best, k) => (dot(DIRS[k], nrm) > dot(DIRS[best], nrm) ? k : best), 'up');
        R.quad(world, uvs, tex, {
          shade: (opts.shade ?? 1) * FACE_SHADE[axis],
          tint: face.tintindex !== undefined ? GRASS_TINT : undefined,
          cull: true,
          glow: opts.glow,
          id: opts.id,
        });
      }
    }
  }
}

// ---------- font ----------
let FONT = null;
function font() {
  if (FONT) return FONT;
  FONT = new Map();
  try {
    for (const p of readJson(path.join(VANILLA, 'minecraft/font/include/space.json')).providers) {
      for (const [chr, a] of Object.entries(p.advances || {})) FONT.set(chr, { space: true, advance: a });
    }
  } catch (e) { FONT.set(' ', { space: true, advance: 4 }); }
  const inc = readJson(path.join(VANILLA, 'minecraft/font/include/default.json'));
  for (const p of inc.providers) {
    if (p.type !== 'bitmap') continue;
    const img = texture(p.file.replace('.png', ''));
    const rows = p.chars.length, cols = [...p.chars[0]].length;
    const cw = img.width / cols, ch = img.height / rows, h = p.height || 8, scale = h / ch;
    p.chars.forEach((row, r) => [...row].forEach((chr, c) => {
      if (chr === '\u0000' || FONT.has(chr)) return;
      let right = -1;
      for (let x = 0; x < cw; x++) for (let y = 0; y < ch; y++) {
        if (img.data[((r * ch + y) * img.width + c * cw + x) * 4 + 3] > 0) right = Math.max(right, x);
      }
      FONT.set(chr, { img, sx: c * cw, sy: r * ch, cw, ch, scale, ascent: p.ascent, advance: Math.floor((right + 1) * scale + 0.5) + 1 });
    }));
  }
  return FONT;
}
const COLORS = {
  black: '000000', dark_blue: '0000AA', dark_green: '00AA00', dark_aqua: '00AAAA', dark_red: 'AA0000', dark_purple: 'AA00AA',
  gold: 'FFAA00', gray: 'AAAAAA', dark_gray: '555555', blue: '5555FF', green: '55FF55', aqua: '55FFFF', red: 'FF5555',
  light_purple: 'FF55FF', yellow: 'FFFF55', white: 'FFFFFF',
};
const LEGACY = '0123456789abcdef'.split('');
const LEGACY_NAMES = Object.keys(COLORS);
const hex = (h) => [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];

/** Flatten a text component (JSON/NBT-ish object, string, or array) into styled runs. */
function runs(comp, style = { color: 'FFFFFF', bold: false }, out = []) {
  if (comp == null) return out;
  if (typeof comp === 'string' || typeof comp === 'number') {
    let st = { ...style };
    const s = String(comp);
    for (let i = 0; i < s.length; i++) {
      if (s[i] === '§' && i + 1 < s.length) {
        const code = s[++i].toLowerCase(), k = LEGACY.indexOf(code);
        if (k >= 0) st = { color: COLORS[LEGACY_NAMES[k]], bold: false };
        else if (code === 'l') st = { ...st, bold: true };
        else if (code === 'r') st = { ...style };
        continue;
      }
      out.push({ ch: s[i], ...st });
    }
    return out;
  }
  if (Array.isArray(comp)) { comp.forEach((c, i) => runs(c, i === 0 ? style : style, out)); return out; }
  const st = { ...style };
  if (comp.color) st.color = COLORS[comp.color] || String(comp.color).replace('#', '');
  if (comp.bold !== undefined) st.bold = !!comp.bold && comp.bold !== 0;
  runs(comp.text ?? comp[''] ?? (comp.translate ? comp.fallback || comp.translate : ''), st, out);
  for (const e of comp.extra || []) runs(e, st, out);
  return out;
}

/** Lay out text into an RGBA image the way TextDisplayRenderer does (10 px lines, centred). */
function textImage(comp, background = 0x40000000, lineWidth = 200, opacity = 255, S = 4) {
  const F = font();
  const chars = runs(comp);
  const lines = [[]];
  for (const c of chars) { if (c.ch === '\n') lines.push([]); else lines[lines.length - 1].push(c); }
  const adv = (c) => { const g = F.get(c.ch) || F.get('?'); return (g ? g.advance : 6) + (c.bold ? 1 : 0); };
  const widths = lines.map((l) => l.reduce((s, c) => s + adv(c), 0));
  const w = Math.max(1, ...widths), h = 10 * lines.length;
  // background spans (-1,-1)..(w,h) in text pixels; drawn S x so glyph edges stay crisp
  const iw = (w + 1) * S, ih = (h + 1) * S;
  const data = new Uint8Array(iw * ih * 4);
  const ba = (background >>> 24) & 255, br = (background >> 16) & 255, bg = (background >> 8) & 255, bb = background & 255;
  for (let i = 0; i < iw * ih; i++) { data[i * 4] = br; data[i * 4 + 1] = bg; data[i * 4 + 2] = bb; data[i * 4 + 3] = ba; }
  lines.forEach((line, li) => {
    let x = (w - widths[li]) / 2;
    for (const c of line) {
      const g = F.get(c.ch) || F.get('?');
      if (g && !g.space) {
        const col = hex(c.color.padStart(6, '0'));
        const top = li * 10 + (7 - g.ascent);
        for (let gy = 0; gy < g.ch; gy++) for (let gx = 0; gx < g.cw; gx++) {
          if (g.img.data[((g.sy + gy) * g.img.width + g.sx + gx) * 4 + 3] < 128) continue;
          for (let b = 0; b <= (c.bold ? 1 : 0); b++) {
            const px0 = Math.round((x + 1 + b + gx * g.scale) * S), py0 = Math.round((top + 1 + gy * g.scale) * S);
            for (let yy = 0; yy < Math.max(1, Math.round(g.scale * S)); yy++) for (let xx = 0; xx < Math.max(1, Math.round(g.scale * S)); xx++) {
              const X = px0 + xx, Y = py0 + yy;
              if (X >= iw || Y >= ih) continue;
              const o = (Y * iw + X) * 4;
              data[o] = col[0]; data[o + 1] = col[1]; data[o + 2] = col[2]; data[o + 3] = opacity;
            }
          }
        }
      }
      x += adv(c);
    }
  });
  return { img: { width: iw, height: ih, data, clamp: true }, w, h };
}

// ---------- scene ----------
function orientation(e, R) { // local -> world rotation for a display entity
  if (e.billboard === 3 || e.billboard === 'center') { // CENTER: face the camera plane
    return (v) => add(add(mul(R.r, v[0]), mul(R.u, v[1])), mul(R.f, -v[2]));
  }
  const yaw = ((e.yaw || 0) * Math.PI) / 180, pitch = ((e.pitch || 0) * Math.PI) / 180;
  // rotationYXZ(-yaw, pitch, 0): apply Z, then X(pitch), then Y(-yaw)
  return (v) => rotY(-yaw)(rotX(pitch)(v));
}
function displayMatrix(e, R) {
  const orient = orientation(e, R);
  const left = e.left || [0, 0, 0, 1], right = e.right || [0, 0, 0, 1], s = e.scale || [1, 1, 1], t = e.translation || [0, 0, 0];
  return (v) => add(e.pos, orient(add(t, qrot(left, [s[0] * qrot(right, v)[0], s[1] * qrot(right, v)[1], s[2] * qrot(right, v)[2]]))));
}

function drawCard(R, e) {
  const M = displayMatrix(e, R);
  const item = (v) => M(rotY(Math.PI)(sub(v, [0.5, 0.5, 0.5]))); // ItemDisplayRenderer's RotY(pi), then the model's 0..1 box
  const tex = texture(`playing_cards:item/card/${e.model}`);
  const glow = e.glow ? [(e.glow >> 16) & 255, (e.glow >> 8) & 255, e.glow & 255] : undefined;
  const zf = 8.5 / 16, zb = 7.5 / 16;
  const id = R.track(`card ${e.model}`);
  // item/generated: front (south) shows the texture, back (north) mirrored; edges omitted (1/16 thick)
  R.quad([[0, 1, zf], [1, 1, zf], [1, 0, zf], [0, 0, zf]].map(item), [[0, 0], [1, 0], [1, 1], [0, 1]], tex, { cull: true, shade: 1, glow, id });
  R.quad([[1, 1, zb], [0, 1, zb], [0, 0, zb], [1, 0, zb]].map(item), [[0, 0], [1, 0], [1, 1], [0, 1]], tex, { cull: true, shade: 1, id });
}

function drawText(R, e) {
  const { img, w, h } = textImage(e.text, e.background ?? 0x40000000, e.lineWidth, e.opacity ?? 255);
  const M = displayMatrix(e, R);
  const px = (tx, ty) => M([0.025 * (tx + 1 - w / 2), 0.025 * (h - ty), 0]);
  R.quad([px(-1, -1), px(w, -1), px(w, h), px(-1, h)], [[0, 0], [1, 0], [1, 1], [0, 1]], img, { blend: true });
}

function drawBlockDisplay(R, e) {
  const M = displayMatrix(e, R);
  const key = `block ${e.block}`;
  const id = R.ids.indexOf(key) >= 0 ? R.ids.indexOf(key) : R.track(key);
  drawBlock(R, e.block, e.props || {}, (p) => M(mul(p, 1 / 16)), () => false, { id });
}

function box(R, center, size, yaw, color, shade = 1) { // an axis box rotated by yaw about its centre's vertical
  const h = mul(size, 0.5), tex = solid(color);
  const rot = rotY((-yaw * Math.PI) / 180);
  const P = (x, y, z) => add(center, rot([x * h[0], y * h[1], z * h[2]]));
  const faces = {
    up: [P(-1, 1, -1), P(1, 1, -1), P(1, 1, 1), P(-1, 1, 1)], down: [P(-1, -1, 1), P(1, -1, 1), P(1, -1, -1), P(-1, -1, -1)],
    north: [P(1, 1, -1), P(-1, 1, -1), P(-1, -1, -1), P(1, -1, -1)], south: [P(-1, 1, 1), P(1, 1, 1), P(1, -1, 1), P(-1, -1, 1)],
    west: [P(-1, 1, -1), P(-1, 1, 1), P(-1, -1, 1), P(-1, -1, -1)], east: [P(1, 1, 1), P(1, 1, -1), P(1, -1, -1), P(1, -1, 1)],
  };
  for (const [d, pts] of Object.entries(faces)) R.quad(pts, [[0, 0], [1, 0], [1, 1], [0, 1]], tex, { cull: true, shade: shade * FACE_SHADE[d] });
}

function drawPlayer(R, e) { // a seated figure (Steve colours) and its name tag
  const yaw = e.yaw || 0, fwd = lookDir(yaw, 0), eye = e.eye;
  box(R, eye, [0.5, 0.5, 0.5], yaw, [150, 105, 80]);                              // head
  box(R, add(eye, [0, -0.25 - 0.375, 0]), [0.5, 0.75, 0.25], yaw, [0, 170, 170]);  // torso
  const hip = add(eye, [0, -1.0 - 0.125, 0]);
  const side = norm(cross(fwd, [0, 1, 0]));
  for (const s of [-1, 1]) {
    box(R, add(add(hip, mul(fwd, 0.3)), mul(side, 0.125 * s)), [0.25, 0.25, 0.75], yaw, [60, 60, 160]); // thighs forward
    box(R, add(add(eye, [0, -0.25 - 0.375, 0]), mul(side, 0.375 * s)), [0.25, 0.75, 0.25], yaw, [150, 105, 80]); // arms
  }
  // vanilla name tag: top at bbHeight + 0.5 = eye + 0.68, one 10 px line (0.25) drawn downward
  if (e.name) drawText(R, { pos: add(eye, [0, 0.68 - 0.25, 0]), billboard: 3, text: e.name, background: 0x40000000, scale: [1, 1, 1] });
}

function render(scene, outFile) {
  const R = new Raster(scene.camera);
  // the flat world beyond the captured blocks: grass plane under everything
  const gy = scene.groundY ?? -60, g = 256, c = scene.camera.eye;
  const gt = texture('minecraft:block/grass_block_top');
  R.quad([[c[0] - g, gy - 0.002, c[2] - g], [c[0] + g, gy - 0.002, c[2] - g], [c[0] + g, gy - 0.002, c[2] + g], [c[0] - g, gy - 0.002, c[2] + g]],
    [[0, 0], [2 * g, 0], [2 * g, 2 * g], [0, 2 * g]], gt, { tint: GRASS_TINT, shade: 1 });
  const full = new Set();
  for (const b of scene.blocks || []) if (isFullCube(b.name, b.props)) full.add(`${b.x},${b.y},${b.z}`);
  for (const b of scene.blocks || []) {
    const nb = (d) => full.has(`${b.x + DIRS[d][0]},${b.y + DIRS[d][1]},${b.z + DIRS[d][2]}`);
    drawBlock(R, b.name, b.props, (p) => [b.x + p[0] / 16, b.y + p[1] / 16, b.z + p[2] / 16], nb);
  }
  for (const e of scene.entities || []) {
    if (e.type === 'item_display' && e.model) drawCard(R, e);
    else if (e.type === 'text_display') drawText(R, e);
    else if (e.type === 'block_display' && e.block) drawBlockDisplay(R, e);
    else if (e.type === 'player') drawPlayer(R, e);
  }
  R.finish();
  if (scene.caption) { // a caption strip in the Minecraft font, like a chat line
    const { img } = textImage(scene.caption, 0xa0000000, 200, 255, 2);
    R.blit(img, 8, H - img.height - 8);
  }
  R.save(outFile);
  return { missing: [...texCache.values()].filter((t) => t.missing).map((t) => t.missing), visibility: R.visibility() };
}

module.exports = { render, lookDir, textImage, runs };

if (require.main === module) {
  const [scene, out] = process.argv.slice(2);
  const r = render(readJson(scene), out);
  if (r.missing.length) console.log('missing textures:', r.missing.join(', '));
  for (const v of r.visibility) if (v.cover) console.log(`  ${v.name}: ${(100 * v.visible).toFixed(0)}% visible of ${v.cover} px`);
  console.log('wrote', out);
}
