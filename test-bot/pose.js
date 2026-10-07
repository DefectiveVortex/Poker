// Pose driver for screenshots: two players that sit at the table, play passively and can be steered.
//
//   MC_VERSION=26.1 node pose.js [--names auknr,rombwie] [--seats 2,5] [--buyin 200] [--human DefectiveVortex]
//                                [--table x,y,z] [--think 2500] [--turn-timeout 0] [--cmd /tmp/poker-pose.cmd] [--no-heads]
//
// Both players run in this one node process. They join the nearest table at --seats (1-based) for --buyin,
// check when free and call otherwise (never fold on their own), confirm "play again" after every hand, top up
// when they bust, and turn their heads to whoever is acting (to the table on their own turn and at showdown).
// They never chat.
//
// Steer it by typing a line on stdin, appending one to the --cmd file (echo "hold" >> /tmp/poker-pose.cmd), or
// in game as --human: whisper it to either player (/msg rombwie fold: a whisper is for the one you whisper to)
// or say it in chat after "pose" (pose hold). The players never answer in chat; F3+D clears your own chat.
// A line may start with who it is for: a name, a prefix of one, or "all" (the default).
//   hold | go                 stop acting / act again (a held turn is played at once on "go")
//   fold | check | call | allin | raise [to] | bet [amount]
//                             that player's next action, played at once (even while held) if it is their turn;
//                             without a name: whoever acts next. "raise" alone raises by about the pot.
//   seat <n>                  move to seat n once the hand is over ("seat <n> now" folds and moves at once)
//   leave | join [seat]       stand up / sit down again
//   look table|actor|auto|<player>    where to look (auto = the default behaviour)
//   heads on|off              head turning (off if look packets ever get a player kicked)
//   think <ms>                pause before acting (default 2500)
//   status                    print what each player is doing
//   quit                      both leave and the process exits
//
// For a shoot, on the test server set game.turn-timeout-seconds: 0 (no limit) so a held pose can't time out,
// and raise game.showdown-display-seconds and game.ready-timeout-seconds to leave time for framing. If the
// turn timer stays on, pass it as --turn-timeout: a held player then acts 4 s before it would run out.
const fs = require('fs');
const os = require('os');
const path = require('path');
const readline = require('readline');
const { TestBot, sleep } = require('./lib');

function options(argv) {
  const o = { names: 'auknr,rombwie', seats: '2,5', buyin: '200', human: 'DefectiveVortex', table: '', think: '2500',
    'turn-timeout': '0', cmd: path.join(os.tmpdir(), 'poker-pose.cmd'), heads: true };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--no-heads') o.heads = false;
    else if (a.startsWith('--') && a.slice(2) in o && i + 1 < argv.length) o[a.slice(2)] = argv[++i];
    else throw new Error(`unknown option ${a} (see the top of pose.js)`);
  }
  return o;
}

const opt = options(process.argv.slice(2));
const HUMAN = opt.human;
const TURN_TIMEOUT_MS = Number(opt['turn-timeout']) * 1000;
const TABLE = opt.table ? opt.table.split(',').map(Number) : null;

const TURN = /Your turn.*to call: \D*([\d,]+).*pot: \D*([\d,]+).*stack: \D*([\d,]+)/;
const ACTED = /\bYou (fold|check|call|bet|raise to)\b|You're all in/;
const READY = /Hand over\..*poker ready/;
const HAND = /Hand #\d+/;
const RESULT = /\bshows\b.* - |\bwins? [^a-z]*[\d,]+/;
const BUSTED = /You're out of chips/;
const REFUSED = /not your turn/i;
const SEATED = /You sit down at seat (\d+)/;
const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const WHISPER = new RegExp(`^${esc(HUMAN)} whispers to you: (.+)$`, 'i');
const CHAT = new RegExp(`^<${esc(HUMAN)}> pose (.+)$`, 'i');

let think = Number(opt.think);
let heads = opt.heads;
let anyNext = null; // an action for whoever acts next
let handOn = false; // between "Hand #" and the result
let quitting = false;
const queue = []; // steering lines waiting to be run

const say = (text) => console.log(`${new Date().toTimeString().slice(0, 8)}  ${text}`);
const num = (s) => Number(String(s).replace(/,/g, ''));

class Poser {
  constructor(name, seat, index) {
    this.name = name;
    this.seat = seat;
    this.index = index;
    this.tb = new TestBot(name);
    this.seen = 0;
    this.turn = null;      // { toCall, pot, stack, since, sent, retried }
    this.next = null;      // steered action for the next turn
    this.held = false;
    this.readyAt = 0;
    this.seated = false;
    this.moveTo = 0;       // seat to move to after the hand
    this.focus = 'auto';   // auto | table | actor | <player name>
    this.aim = null;       // { yaw, pitch } being shown
    this.target = null;    // { key, since } the focus the head is turning to
    this.home = null;      // the point on the table in front of this seat
    this.connected = false;
  }

  async connect() {
    await this.tb.connect();
    this.seen = this.tb.log.length;
    this.connected = true;
    this.tb.bot.once('end', () => {
      this.connected = false;
      this.seated = false;
      this.turn = null;
      if (!quitting) say(`${this.name} lost the connection; reconnecting`);
    });
  }

  cmd(command) {
    if (this.connected) this.tb.cmd(command);
  }

  join(seat = this.seat) {
    this.seat = seat;
    this.home = null;
    this.aim = null;
    this.cmd(`/poker join ${opt.buyin} ${seat}`);
    this.seated = false;
    this.joining = Date.now();
  }

  leave() {
    this.cmd('/poker leave');
    this.seated = false;
    this.turn = null;
    this.readyAt = 0;
    say(`${this.name} leaves the table`);
  }

  read(now) {
    const log = this.tb.log;
    while (this.seen < log.length) {
      const line = log[this.seen++];
      const t = line.match(TURN);
      const sat = line.match(SEATED);
      const whisper = line.match(WHISPER);
      const chat = this.index === 0 && line.match(CHAT);
      if (whisper) {
        // a whisper with no name in it is for the one it was whispered to
        const first = whisper[1].trim().split(/\s+/)[0].toLowerCase();
        const named = first === 'all' || opt.names.split(',').some((n) => n.toLowerCase().startsWith(first));
        queue.push(named || /^(hold|go|status|quit|exit|heads|think)\b/i.test(whisper[1].trim()) ? whisper[1] : `${this.name} ${whisper[1]}`);
      } else if (chat) {
        queue.push(chat[1]);
      } else if (sat) {
        this.seated = true;
        this.joining = 0;
        this.sat = now;
        this.seat = Number(sat[1]);
        say(`${this.name} sits at seat ${this.seat} with ${opt.buyin}`);
      } else if (t) {
        this.turn = { toCall: num(t[1]), pot: num(t[2]), stack: num(t[3]), since: now, sent: 0, retried: false };
        handOn = true;
        say(`${this.name}'s turn (to call ${this.turn.toCall}, pot ${this.turn.pot})${this.held ? ' - held' : ''}`);
      } else if (ACTED.test(line) || REFUSED.test(line)) {
        this.turn = null;
      } else if (HAND.test(line)) {
        handOn = true;
      } else if (READY.test(line)) {
        handOn = false;
        this.turn = null;
        this.readyAt = now + 1400 + this.index * 900; // not both at the same instant
      } else if (RESULT.test(line)) {
        handOn = false;
      } else if (BUSTED.test(line) && now - (this.toppedUp || 0) > 5000) {
        this.toppedUp = now;
        this.cmd(`/poker topup ${opt.buyin}`);
        say(`${this.name} busted and tops up ${opt.buyin}`);
      }
    }
  }

  passive() {
    return this.turn.toCall === 0 ? 'check' : 'call';
  }

  act(action, why = '') {
    const t = this.turn;
    let a = action;
    if (a === 'raise' || a === 'bet') {
      const to = 2 * t.toCall + t.pot; // about a pot-sized raise, always at least the minimum
      a = to >= t.stack + t.toCall ? 'allin' : `raise ${to}`;
    }
    if (a === 'check' && t.toCall > 0) a = 'call';
    if (a === 'call' && t.toCall === 0) a = 'check';
    this.cmd(`/poker ${a}`);
    t.sent = Date.now();
    say(`${this.name}: ${a}${why ? ' (' + why + ')' : ''}`);
  }

  step(now) {
    if (!this.connected) return;
    this.read(now);
    if (this.joining && now - this.joining > 6000) {
      this.joining = 0;
      say(`${this.name} did NOT get seat ${this.seat}: ${this.tb.log.slice(-2).join(' / ')}`);
    }
    if (this.readyAt && now >= this.readyAt) {
      this.readyAt = 0;
      if (this.moveTo) {
        const seat = this.moveTo;
        this.moveTo = 0;
        this.leave();
        setTimeout(() => this.join(seat), 1200);
      } else if (this.seated) {
        this.cmd('/poker ready'); // confirmed even while held: only Vortex's own click starts the next hand
      }
    }
    const t = this.turn;
    if (!t) return;
    if (t.sent) {
      // the server didn't take it (too small a raise, say): fall back to the passive move, once
      if (now - t.sent > 2500) {
        if (t.retried) {
          this.turn = null;
        } else {
          t.retried = true;
          this.act(this.passive(), 'the last action was refused');
        }
      }
      return;
    }
    const steered = this.next || anyNext;
    if (steered) {
      if (this.next) this.next = null;
      else anyNext = null;
      this.act(steered, 'steered');
    } else if (this.held) {
      if (TURN_TIMEOUT_MS > 0 && now - t.since > TURN_TIMEOUT_MS - 4000) {
        this.act(this.passive(), 'hold broken: the turn timer was about to run out');
      }
    } else if (now - t.since >= think + this.index * 400) {
      this.act(this.passive());
    }
  }

  // ---- head ----

  eye() {
    const p = this.tb.bot.entity.position;
    return { x: p.x, y: p.y + 1.1, z: p.z };
  }

  /** The spot on the table in front of this seat: the server seats players facing the table. */
  tablePoint() {
    if (TABLE) return { x: TABLE[0] + 0.5, y: TABLE[1] + 1.0, z: TABLE[2] + 0.5 };
    if (!this.home) {
      const e = this.tb.bot.entity;
      // mineflayer's yaw is pi - notchian; looking direction is (-sin, -cos) in x,z
      this.home = { x: e.position.x - Math.sin(e.yaw) * 1.4, y: e.position.y + 0.55, z: e.position.z - Math.cos(e.yaw) * 1.4 };
    }
    return this.home;
  }

  step2Head(now, posers) {
    if (!heads || !this.connected || !this.seated || now - this.sat < 2500) return;
    let key = this.focus;
    if (key === 'auto') key = this.turn || !handOn ? 'table' : 'actor';
    if (key === 'actor') {
      const acting = posers.find((p) => p !== this && p.turn);
      key = acting ? acting.name : this.turn ? 'table' : HUMAN;
    }
    if (!this.target || this.target.key !== key) this.target = { key, since: now };
    if (now - this.target.since < 250 + this.index * 350 && this.aim) return; // a moment before the head follows

    let point = null;
    if (key !== 'table') {
      const other = posers.find((p) => p.name.toLowerCase() === key.toLowerCase());
      const entity = other && other.connected ? other.tb.bot.entity
        : Object.values(this.tb.bot.players).find((pl) => pl.username.toLowerCase() === key.toLowerCase() && pl.entity)?.entity;
      if (entity && entity !== this.tb.bot.entity) point = { x: entity.position.x, y: entity.position.y + 1.1, z: entity.position.z };
    }
    if (!point) point = this.tablePoint();
    const eye = this.eye();
    const dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
    const flat = Math.hypot(dx, dz);
    if (flat < 0.05) return;
    const want = { yaw: Math.atan2(-dx, dz) * 180 / Math.PI, pitch: Math.max(-40, Math.min(55, -Math.atan2(dy, flat) * 180 / Math.PI)) };
    if (!this.aim) {
      // start from where the server seated the head (facing the table), so the first turn is eased too
      this.aim = { yaw: 180 - this.tb.bot.entity.yaw * 180 / Math.PI, pitch: 0 };
    }
    {
      const turn = ((want.yaw - this.aim.yaw + 540) % 360) - 180;
      const tilt = want.pitch - this.aim.pitch;
      if (Math.abs(turn) < 0.5 && Math.abs(tilt) < 0.5) return; // there already: send nothing, so no jitter
      // ease out: fast while far, slowing as the head arrives
      const ease = (d, max) => {
        const stride = Math.min(max, Math.max(1.5, Math.abs(d) * 0.3));
        return Math.abs(d) <= stride ? d : Math.sign(d) * stride;
      };
      this.aim = { yaw: this.aim.yaw + ease(turn, 14), pitch: this.aim.pitch + ease(tilt, 8) };
    }
    // lib.js drops movement packets (they get bots kicked through ViaBackwards); a bare look is what a seated
    // client sends, so it goes straight to the serializer
    const client = this.tb.bot._client;
    if (client.serializer && client.serializer.writable) {
      client.serializer.write({ name: 'look', params: { yaw: this.aim.yaw, pitch: this.aim.pitch, onGround: true,
        flags: { onGround: true, hasHorizontalCollision: false } } });
    }
  }
}

// ---------------------------------------------------------------- steering

const ACTIONS = new Set(['fold', 'check', 'call', 'allin', 'raise', 'bet']);

function command(line, posers) {
  const words = line.trim().split(/\s+/).filter(Boolean);
  if (!words.length || words[0].startsWith('#')) return;
  const first = words[0].toLowerCase();
  let who = posers;
  let named = false;
  const match = posers.filter((p) => p.name.toLowerCase().startsWith(first));
  if (first === 'all' || match.length === 1) {
    if (match.length === 1) {
      who = match;
      named = true;
    }
    words.shift();
  }
  let verb = (words.shift() || '').toLowerCase().replace('all-in', 'allin');
  const arg = words[0];
  switch (verb) {
    case 'hold': who.forEach((p) => { p.held = true; }); return say(`hold: ${who.map((p) => p.name).join(', ')}`);
    case 'go': who.forEach((p) => { p.held = false; if (p.turn) p.turn.since = 0; }); return say(`go: ${who.map((p) => p.name).join(', ')}`);
    case 'seat': {
      const n = Number(arg);
      if (!named || !(n >= 1)) return say('usage: <name> seat <n> [now]');
      const p = who[0];
      if (words[1] === 'now' || !handOn) {
        p.leave();
        setTimeout(() => p.join(n), 1200);
      } else {
        p.moveTo = n;
        say(`${p.name} moves to seat ${n} after this hand`);
      }
      return;
    }
    case 'leave': who.filter((p) => p.seated).forEach((p) => p.leave()); return;
    case 'join': who.filter((p) => !p.seated).forEach((p) => p.join(named && Number(arg) >= 1 ? Number(arg) : p.seat)); return;
    case 'ready': who.filter((p) => p.seated).forEach((p) => p.cmd('/poker ready')); return;
    case 'look': who.forEach((p) => { p.focus = arg || 'auto'; }); return say(`look ${arg || 'auto'}: ${who.map((p) => p.name).join(', ')}`);
    case 'heads': heads = arg !== 'off'; return say(`heads ${heads ? 'on' : 'off'}`);
    case 'think': think = Math.max(0, Number(arg) || 0); return say(`think ${think} ms`);
    case 'status':
      say(`hand ${handOn ? 'in progress' : 'over'}, think ${think} ms, heads ${heads ? 'on' : 'off'}`);
      for (const p of posers) {
        say(`  ${p.name}: ${!p.connected ? 'offline' : !p.seated ? 'standing' : 'seat ' + p.seat}${p.held ? ', held' : ''}`
          + `${p.turn ? ', ON TURN (to call ' + p.turn.toCall + ')' : ''}${p.next ? ', next: ' + p.next : ''}, looking ${p.focus}`);
      }
      return;
    case 'quit': case 'exit': return shutdown(posers);
    default:
      if (!ACTIONS.has(verb)) return say(`don't know "${line.trim()}" (hold go fold check call allin raise bet seat leave join ready look heads think status quit)`);
      if ((verb === 'raise' || verb === 'bet') && arg && Number(arg) > 0) verb = `raise ${Number(arg)}`;
      if (named) who[0].next = verb;
      else anyNext = verb;
      return say(`next action${named ? ' for ' + who[0].name : ''}: ${verb}`);
  }
}

async function shutdown(posers) {
  if (quitting) return;
  quitting = true;
  for (const p of posers) if (p.seated) p.leave();
  await sleep(1500);
  for (const p of posers) await p.tb.quit();
  process.exit(0);
}

async function main() {
  const names = opt.names.split(',');
  const seats = opt.seats.split(',').map(Number);
  const posers = names.map((n, i) => new Poser(n, seats[i] || i + 1, i));

  fs.writeFileSync(opt.cmd, '');
  let offset = 0;
  readline.createInterface({ input: process.stdin }).on('line', (l) => queue.push(l));
  process.on('SIGINT', () => shutdown(posers));
  process.on('SIGTERM', () => shutdown(posers));

  for (const p of posers) {
    await p.connect();
    p.join();
    await sleep(1500);
  }
  say(`ready. Steer with stdin or: echo "hold" >> ${opt.cmd}`);

  for (;;) {
    try {
      const size = fs.statSync(opt.cmd).size;
      if (size < offset) offset = 0;
      if (size > offset) {
        const text = fs.readFileSync(opt.cmd, 'utf8').slice(offset);
        const upTo = text.lastIndexOf('\n');
        if (upTo >= 0) {
          queue.push(...text.slice(0, upTo).split('\n'));
          offset += Buffer.byteLength(text.slice(0, upTo + 1));
        }
      }
    } catch (e) { /* the command file was removed: stdin still works */ }
    while (queue.length) command(queue.shift(), posers);

    const now = Date.now();
    for (const p of posers) {
      if (quitting) break;
      if (!p.connected && !p.reconnecting) {
        p.reconnecting = true;
        sleep(6000).then(() => p.connect()).then(() => p.join()).catch((e) => say(`${p.name} can't reconnect: ${e.message}`))
          .finally(() => { p.reconnecting = false; });
        continue;
      }
      try {
        p.step(now);
        p.step2Head(now, posers);
      } catch (e) {
        say(`${p.name}: ${e.message}`);
      }
    }
    await sleep(100);
  }
}

main().catch((e) => { console.error(e); process.exit(2); });
