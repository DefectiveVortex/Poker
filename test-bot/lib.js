// Helpers for driving the Poker test server with headless mineflayer bots.
const mineflayer = require('mineflayer');
const { execFileSync } = require('child_process');
const path = require('path');

const HOST = process.env.MC_HOST || '127.0.0.1';
const PORT = Number(process.env.MC_PORT || 25571);
const VERSION = process.env.MC_VERSION || '26.1';
const RCON = process.env.RCON_PY || '/home/vortex/Poker-ops/test-server/rcon.py';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function rcon(cmd) {
  const out = execFileSync('python3', [RCON, '127.0.0.1', process.env.RCON_PORT || '25581', process.env.RCON_PASS || 'poker-test', cmd], { encoding: 'utf8' });
  return out.replace(/§./g, '').trim();
}

// Balance as tracked by TestEconomy (starts at 10000 per player per server run)
function balance(name) {
  const out = rcon(`money ${name}`);
  const m = out.match(/\$(-?[\d.]+)/);
  if (!m) throw new Error(`no balance in: ${out}`);
  return Number(m[1]);
}

function setBalance(name, amount) {
  rcon(`money ${name} ${amount}`);
}

function countDisplays(selector = '@e[type=item_display]') {
  const out = rcon(`execute if entity ${selector}`);
  if (/Test failed/i.test(out)) return 0;
  const m = out.match(/Count:\s*(\d+)/i);
  if (!m) throw new Error(`bad selector ${selector}: ${out}`);
  return Number(m[1]);
}

class TestBot {
  constructor(name) {
    this.name = name;
    this.log = [];      // plain-text chat lines
    this.raw = [];      // raw JSON of each chat line (for hover/click checks)
    this.packs = [];    // resource pack offers received in play
    this.serverPacks = []; // offers received while logging in (server.properties pack)
  }

  async connect(attempt = 1) {
    try {
      await this.connectOnce();
    } catch (e) {
      // Bukkit refuses reconnects from one IP within connection-throttle (4 s by default)
      if (attempt >= 4) throw e;
      await sleep(5000);
      await this.connect(attempt + 1);
    }
  }

  async connectOnce() {
    this.bot = mineflayer.createBot({ host: HOST, port: PORT, username: this.name, version: VERSION, auth: 'offline' });
    // Bots never walk (the plugin teleports them to seats). Physics simulated through ViaBackwards
    // produced NaN positions and got them kicked for invalid movement, so it stays off.
    this.bot.physicsEnabled = false;
    // mineflayer still sends a periodic position packet, and through ViaBackwards to 26.3 that
    // one gets the bot kicked too. Bots never move on their own and teleport_confirm alone
    // acknowledges server teleports, so movement packets are simply not sent.
    // The one exception is the position_look that answers a server teleport right after
    // teleport_confirm, as a real client sends it: until the server gets it, it ignores the
    // player's block clicks.
    const write = this.bot._client.write.bind(this.bot._client);
    let teleportAck = false;
    this.bot._client.write = (name, params) => {
      if (name === 'teleport_confirm') teleportAck = true;
      else if (name === 'position_look' && teleportAck) teleportAck = false;
      else if (['position', 'position_look', 'look', 'flying'].includes(name)) return;
      return write(name, params);
    };
    this.bot.on('message', (json) => {
      const text = json.toString();
      this.log.push(text);
      this.raw.push(json.json);
      if (process.env.VERBOSE) console.log(`  [${this.name}] ${text}`);
    });
    // Answer every pack offer like a client that accepts and loads it: a server pack set in
    // server.properties holds the login (configuration phase) until it gets an answer. Only offers
    // made in play (the plugin's, at the table) are recorded in `packs`.
    const onPack = (p) => {
      const c = this.bot._client;
      const id = p.uuid ? { uuid: p.uuid } : {};
      c.write('resource_pack_receive', { ...id, result: 3 }); // accepted
      c.write('resource_pack_receive', { ...id, result: 0 }); // successfully loaded
      if (c.state === 'configuration') this.serverPacks.push(p);
      else this.packs.push(p);
    };
    this.bot._client.on('add_resource_pack', onPack);
    this.bot._client.on('resource_pack_send', onPack);
    this.bot.on('kicked', (r) => console.log(`  [${this.name}] kicked: ${JSON.stringify(r)}`));
    this.bot.on('error', (e) => console.log(`  [${this.name}] error: ${e.message}`));
    await new Promise((resolve, reject) => {
      this.bot.once('spawn', resolve);
      this.bot.once('end', (r) => reject(new Error(`${this.name} disconnected before spawn: ${r}`)));
    });
    await sleep(1500);
  }

  // Press and release sneak (entity_action before 1.21.6, player_input after)
  async sneak() {
    this.bot.setControlState('sneak', true);
    await sleep(150);
    this.bot.setControlState('sneak', false);
  }

  async waitWindow(timeout = 5000) {
    const end = Date.now() + timeout;
    while (Date.now() < end) {
      if (this.bot.currentWindow) return this.bot.currentWindow;
      await sleep(100);
    }
    throw new Error(`${this.name}: no window opened`);
  }

  closeWindow() {
    if (this.bot.currentWindow) this.bot.closeWindow(this.bot.currentWindow);
  }

  // Right-click the block at x,y,z
  async useBlock(x, y, z) {
    const { Vec3 } = require('vec3');
    const block = this.bot.blockAt(new Vec3(x, y, z));
    if (!block) throw new Error(`${this.name}: block ${x},${y},${z} not loaded`);
    // activateBlock's own lookAt waits for a physics tick, which never comes while riding (or with
    // physics off); looking with force first leaves it nothing to wait for.
    await this.bot.lookAt(block.position.offset(0.5, 0.5, 0.5), true);
    await this.bot.activateBlock(block);
  }

  cmd(command) {
    this.bot.chat(command.startsWith('/') ? command : `/${command}`);
  }

  mark() { return this.log.length; }

  // Wait for a chat line matching `re` that arrived after `from` (a mark()).
  async waitFor(re, from = 0, timeout = 8000) {
    const end = Date.now() + timeout;
    while (Date.now() < end) {
      for (let i = from; i < this.log.length; i++) {
        if (re.test(this.log[i])) return this.log[i];
      }
      await sleep(100);
    }
    throw new Error(`${this.name}: timed out waiting for ${re}; last lines:\n    ` + this.log.slice(-8).join('\n    '));
  }

  since(from) { return this.log.slice(from); }

  quit() {
    return new Promise((resolve) => {
      if (!this.bot || !this.bot.player) return resolve();
      this.bot.once('end', resolve);
      this.bot.quit();
      setTimeout(resolve, 3000);
    });
  }
}

let passed = 0;
let failed = 0;
function check(label, condition, detail = '') {
  if (condition) {
    passed++;
    console.log(`  PASS  ${label}`);
  } else {
    failed++;
    console.log(`  FAIL  ${label}${detail ? ' -- ' + detail : ''}`);
  }
}
function summary() {
  console.log(`\n${passed} passed, ${failed} failed`);
  return failed;
}

module.exports = { TestBot, rcon, balance, setBalance, countDisplays, sleep, check, summary };
