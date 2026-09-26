// Poker bot scenario runner. Usage: node run.js [scenario ...]   (exit 0 = all passed)
// Env: MC_VERSION (26.1 for the 26.3 server via ViaBackwards, 1.20.1 native), MC_HOST, MC_PORT, RCON_PORT, RCON_PASS,
//      SERVER_DIR (plugin data is read from $SERVER_DIR/plugins/Poker), VERBOSE=1 to echo bot chat.
// Scenario files export { name: async (bots, ctx) => {...} }. Owners: game.js = D2, display.js = D1.
// `setup` (below) builds a fresh 6-seat table (blinds 5/10, buy-in 100-2000) centred on 0,-60,0 facing north.
const { TestBot, rcon, check, summary, sleep } = require('./lib');

const BOT_NAMES = ['BotA', 'BotB', 'BotC'];
const ctx = {
  serverDir: process.env.SERVER_DIR || '/home/vortex/Poker-ops/test-server/paper-26.3',
  legacy: /^1\.20/.test(process.env.MC_VERSION || ''),
  tableId: null,
};

async function leaveAll(bots) {
  for (const b of bots) b.cmd('/poker leave');
  await sleep(1500);
}

const core = {
  async setup(bots) {
    const [a] = bots;
    rcon('op ' + a.name);
    rcon('gamerule doMobSpawning false');
    for (const b of bots) rcon(`tp ${b.name} 3 -60 3`);
    rcon(`tp ${a.name} 0.5 -60 0.5 180 0`); // facing north
    await sleep(1000);
    a.cmd('/poker removetable');
    await sleep(800);
    const m = a.mark();
    a.cmd('/poker createtable seats:6 small-blind:5 big-blind:10 min-buy-in:10 max-buy-in:200');
    const line = await a.waitFor(/table #(\d+) created|can't go here|Invalid argument/i, m);
    const id = line.match(/#(\d+)/);
    ctx.tableId = id ? Number(id[1]) : null;
    check('table created', !!id, line);
  },
};

const scenarios = { ...core };
for (const file of ['./display', './game']) {
  try {
    Object.assign(scenarios, require(file));
  } catch (e) {
    if (e.code !== 'MODULE_NOT_FOUND' || !e.message.includes(file.slice(2))) throw e;
  }
}

async function main() {
  const wanted = process.argv.slice(2);
  const names = wanted.length ? wanted : Object.keys(scenarios);
  const unknown = names.filter((n) => !scenarios[n]);
  if (unknown.length) throw new Error(`unknown scenario(s): ${unknown.join(', ')}; have ${Object.keys(scenarios).join(', ')}`);
  const bots = BOT_NAMES.map((n) => new TestBot(n));
  for (const b of bots) await b.connect();
  try {
    if (!names.includes('setup')) await scenarios.setup(bots, ctx);
    for (const name of names) {
      console.log(`\n== ${name}`);
      try {
        await scenarios[name](bots, ctx);
      } catch (e) {
        check(`${name} ran to completion`, false, e.message);
      } finally {
        await leaveAll(bots); // a failure must not leave bots seated for the next scenario
      }
    }
  } finally {
    for (const b of bots) await b.quit();
  }
  process.exit(summary() ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(2); });
