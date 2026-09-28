// botcam capture: seat three bots, play one hand, and at chosen moments dump each bot's OWN view of
// the table (the display entities the server sent that bot, so per-viewer hiding is real) plus the
// blocks around it, then render every view from that bot's seated eye with render.js.
//
// Usage (poker-test must be running; see ../botcam/README.md):
//   MC_VERSION=26.1   node capture.js            # 26.3 server through ViaBackwards
//   MC_VERSION=1.20.1 node capture.js
// Env: OUT (default ./shots/<server>), SCENES (default ./scenes/<server>), DUMP=1 prints raw metadata.
const fs = require('fs');
const path = require('path');
const { TestBot, rcon, setBalance, sleep } = require('../test-bot/lib');
const { render } = require('./render');
// mineflayer's own deps live in test-bot/node_modules
const dep = (name) => require(require.resolve(name, { paths: [path.join(__dirname, '../test-bot')] }));

const VERSION = process.env.MC_VERSION || '26.1';
const SERVER = /^1\.20/.test(VERSION) ? '1.20.1' : '26.3';
const OUT = process.env.OUT || path.join(__dirname, 'shots', SERVER);
const SCENES = process.env.SCENES || path.join(__dirname, 'scenes', SERVER);
const SEATS = { BotA: 1, BotB: 2, BotC: 5 }; // 1-based, as /poker join takes them
const TABLE = [0.5, -59, 0.5];               // run.js's table: centred on block 0,-60,0
const LEGACY_NAMES = ['back', ...['s', 'h', 'd', 'c'].flatMap((s) => ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', 'j', 'q', 'k'].map((r) => s + r)), 'j'];
const RX = {
  seated: /You (?:sit down at|sat down in) seat (\d+)/,
  turn: /Your turn/,
  win: /You win/,
};

// ---------- reading a bot's entity table ----------
const vec = (v) => (v == null ? null : Array.isArray(v) ? v : [v.x, v.y, v.z].concat(v.w !== undefined ? [v.w] : []));
function simplifyNbt(v) {
  if (v && typeof v === 'object' && 'type' in v && 'value' in v) {
    if (v.type === 'compound') return Object.fromEntries(Object.entries(v.value).map(([k, x]) => [k, simplifyNbt(x)]));
    if (v.type === 'list') return (v.value.value || []).map((x) => simplifyNbt({ type: v.value.type, value: x }));
    return v.value;
  }
  if (Array.isArray(v)) return v.map(simplifyNbt);
  if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, simplifyNbt(x)]));
  return v;
}
function textOf(v) {
  if (v == null) return '';
  if (typeof v === 'string') { try { return JSON.parse(v); } catch (e) { return v; } }
  return simplifyNbt(v);
}
function cardModel(item) {
  if (!item) return null;
  const s = JSON.stringify(item, (k, x) => (typeof x === 'bigint' ? Number(x) : x));
  const m = s.match(/playing_cards:(?:item\/)?card\/([a-z0-9]+)/);
  if (m) return m[1];
  const cmd = s.match(/CustomModelData\D{0,40}?(2\d{4})/) || s.match(/"custom_model_data"[^\d]*(2\d{4})/);
  if (cmd) return LEGACY_NAMES[Number(cmd[1]) - 21000] || null;
  return null;
}
const signed = (n) => (n > 0x7fffffff ? n - 0x100000000 : n);

/** The display entities this bot has been sent, in render.js's scene format. */
function displays(bot) {
  const keys = (name) => bot.registry.entitiesByName[name].metadataKeys;
  const out = [];
  for (const e of Object.values(bot.entities)) {
    if (!['item_display', 'text_display', 'block_display'].includes(e.name)) continue;
    const k = keys(e.name), md = (key) => e.metadata[k.indexOf(key)];
    const d = {
      type: e.name, id: e.id,
      pos: [e.position.x, e.position.y, e.position.z],
      yaw: ((((Math.PI - e.yaw) * 180) / Math.PI) % 360 + 360) % 360, pitch: (-e.pitch * 180) / Math.PI,
      translation: vec(md('translation')) || [0, 0, 0], scale: vec(md('scale')) || [1, 1, 1],
      left: vec(md('left_rotation')) || [0, 0, 0, 1], right: vec(md('right_rotation')) || [0, 0, 0, 1],
      billboard: md('billboard_render_constraints') ?? 0,
    };
    const flags = md('shared_flags') ?? 0, glow = md('glow_color_override');
    if (flags & 0x40) d.glow = glow != null && glow !== -1 ? signed(glow) & 0xffffff : 0xffffff;
    if (e.name === 'item_display') { d.model = cardModel(md('item_stack')); d.item = md('item_stack'); }
    if (e.name === 'text_display') {
      d.text = textOf(md('text'));
      const bg = md('background_color');
      d.background = bg == null ? 0x40000000 : signed(bg) >>> 0;
      const op = md('text_opacity');
      d.opacity = op == null || op === -1 ? 255 : op & 255;
    }
    if (e.name === 'block_display') {
      const id = md('block_state');
      const Block = dep('prismarine-block')(bot.registry);
      const b = id != null ? Block.fromStateId(id, 0) : null;
      d.block = b ? b.name : null;
      d.props = b ? b.getProperties() : {};
    }
    if (process.env.DUMP) console.log(JSON.stringify(e.metadata, (k2, x) => (typeof x === 'bigint' ? Number(x) : x)).slice(0, 600));
    delete d.item;
    out.push(d);
  }
  return out;
}

function blocksAround(bot, c = TABLE, r = 7) {
  const { Vec3 } = dep('vec3');
  const out = [];
  for (let x = Math.floor(c[0]) - r; x <= Math.floor(c[0]) + r; x++)
    for (let z = Math.floor(c[2]) - r; z <= Math.floor(c[2]) + r; z++)
      for (let y = -64; y <= -55; y++) {
        const b = bot.blockAt(new Vec3(x, y, z));
        if (b && b.name !== 'air' && b.name !== 'cave_air') out.push({ x, y, z, name: b.name, props: b.getProperties() });
      }
  return out;
}

/** Server-side position of a (possibly seated) player: feet + 1.62 = eye. */
function eyeOf(name) {
  const pos = rcon(`data get entity ${name} Pos`).match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/);
  const rot = rcon(`data get entity ${name} Rotation`).match(/\[(-?[\d.]+)f, (-?[\d.]+)f\]/);
  if (!pos) throw new Error(`no position for ${name}`);
  return { eye: [Number(pos[1]), Number(pos[2]) + 1.62, Number(pos[3])], yaw: rot ? Number(rot[1]) : 0 };
}

const horiz = (a, b) => Math.hypot(a[0] - b[0], a[2] - b[2]);
const isBoard = (d) => d.type === 'item_display' && d.model && horiz(d.pos, TABLE) < 0.95;

// ---------- the shots ----------
async function shoot(bots, moment, aim) {
  fs.mkdirSync(OUT, { recursive: true });
  fs.mkdirSync(SCENES, { recursive: true });
  const where = Object.fromEntries(bots.map((b) => [b.name, eyeOf(b.name)]));
  const report = [];
  for (const b of bots) {
    const me = where[b.name];
    const ents = displays(b.bot);
    const cards = ents.filter((d) => d.type === 'item_display' && d.model);
    const mine = cards.filter((d) => !isBoard(d) && horiz(d.pos, me.eye) < 1.2);
    const board = cards.filter(isBoard);
    const faces = cards.filter((d) => !isBoard(d) && d.model !== 'back');
    const backs = cards.filter((d) => d.model === 'back');
    // look where a seated player would: between their own cards and the board (or the table centre)
    const target = aim === 'table' || !mine.length ? TABLE
      : mine.reduce((s, d) => [s[0] + d.pos[0] / mine.length, s[1] + d.pos[1] / mine.length, s[2] + d.pos[2] / mine.length], [0, 0, 0])
        .map((v, i) => 0.55 * v + 0.45 * TABLE[i]);
    const dx = target[0] - me.eye[0], dy = target[1] - me.eye[1], dz = target[2] - me.eye[2];
    const yaw = (Math.atan2(-dx, dz) * 180) / Math.PI, pitch = (-Math.atan2(dy, Math.hypot(dx, dz)) * 180) / Math.PI;
    const others = bots.filter((o) => o !== b).map((o) => ({ type: 'player', name: o.name, eye: where[o.name].eye, yaw: where[o.name].yaw }));
    const seat = SEATS[b.name];
    const scene = {
      version: SERVER, moment, viewer: b.name, seat,
      camera: { eye: me.eye, yaw, pitch, fov: 70 },
      caption: `${b.name} (seat ${seat}) - ${moment} - Poker ${SERVER} - botcam render`,
      blocks: blocksAround(b.bot), entities: [...ents, ...others],
      counts: { faces: faces.length, backs: backs.length, board: board.length, facesNearMe: mine.filter((d) => d.model !== 'back').length },
      faces: faces.map((d) => d.model), board: board.map((d) => d.model),
    };
    const base = `${moment}-seat${seat}-${b.name}`;
    fs.writeFileSync(path.join(SCENES, base + '.json'), JSON.stringify(scene));
    const t = Date.now();
    const r = render(scene, path.join(OUT, base + '.png'));
    report.push(`${base}: faces seen ${JSON.stringify(scene.faces)} backs ${backs.length} board ${JSON.stringify(scene.board)} (${Date.now() - t} ms${r.missing.length ? ', missing ' + r.missing : ''})`);
  }
  console.log(report.map((l) => '  ' + l).join('\n'));
}

async function main() {
  const bots = Object.keys(SEATS).map((n) => new TestBot(n));
  for (const b of bots) await b.connect();
  const [a] = bots;
  try {
    // the table run.js builds
    rcon(`op ${a.name}`);
    rcon('gamerule doMobSpawning false');
    rcon('time set day');
    for (const b of bots) { rcon(`tp ${b.name} 3 -60 3`); setBalance(b.name, 10000); }
    rcon(`tp ${a.name} 0.5 -60 0.5 180 0`);
    await sleep(1000);
    a.cmd('/poker removetable');
    await sleep(800);
    let m = a.mark();
    a.cmd('/poker createtable seats:6 small-blind:5 big-blind:10 min-buy-in:10 max-buy-in:200');
    console.log(' ', await a.waitFor(/table #(\d+) created|can't go here|Invalid argument/i, m));
    const marks = new Map();
    for (const b of bots) {
      marks.set(b, b.mark());
      m = b.mark();
      b.cmd(`/poker join 500 ${SEATS[b.name]}`);
      console.log(' ', await b.waitFor(RX.seated, m, 8000));
    }
    // play check/call; freeze at the flop and shoot, then play on to the showdown
    let shotFlop = false;
    const end = Date.now() + 150000;
    let won = null;
    while (Date.now() < end && !won) {
      const view = displays(a.bot);
      if (!shotFlop && view.filter(isBoard).length >= 3) {
        await sleep(2000); // cards slide in and flip over ~1 s
        console.log('flop:');
        await shoot(bots, 'flop', 'mine');
        shotFlop = true;
      }
      for (const b of bots) {
        for (let i = marks.get(b); i < b.log.length; i++) {
          marks.set(b, i + 1);
          const line = b.log[i];
          if (RX.win.test(line)) won = b;
          else if (RX.turn.test(line)) {
            const call = /to call: \D*([\d,]+)/.exec(line);
            b.cmd(call && Number(call[1].replace(/,/g, '')) > 0 ? '/poker call' : '/poker check');
          }
        }
      }
      if (!won) await sleep(200);
    }
    if (!won) throw new Error('hand did not finish');
    await sleep(1500); // showdown reveal flips
    console.log(`showdown (${won.name} won):`);
    await shoot(bots, 'showdown', 'table');
  } finally {
    for (const b of bots) { try { b.cmd('/poker leave'); } catch (e) { /* gone */ } }
    await sleep(1500);
    for (const b of bots) await b.quit();
  }
}

main().then(() => process.exit(0), (e) => { console.error(e); process.exit(1); });
