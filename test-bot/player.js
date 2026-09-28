// D4 scenarios: the player layer (commands, buy-in / action / raise menus, chat actions, top-up, stats, PAPI).
// Runs after run.js `setup` (6 seats, blinds 5/10, buy-in 10-200 BB = 100-2000). BotA is op, BotB/BotC are not.
// Assertions use lines sent directly to the player, exact balances, and chip conservation.
const { rcon, balance, setBalance, check, sleep } = require('./lib');

const WALLET = 10000;
const RX = {
  seated: /You (?:sit down at|sat down in) seat (\d+)/,
  turn: /Your turn.*to call/,
  fold: /You fold/,
  raise: /You raise to \D*([\d,]+)/,
  win: /You win \D*([\d,]+)/,
  cashout: /You (?:leave|left) the table/,
};
const num = (s) => Number(String(s).replace(/[^\d]/g, ''));

function reset(bots) {
  for (const b of bots) setBalance(b.name, WALLET);
}

async function join(bot, amount, seat) {
  const m = bot.mark();
  bot.cmd(`/poker join ${amount} ${seat}`);
  await bot.waitFor(RX.seated, m, 8000);
}

async function leave(bot) {
  const m = bot.mark();
  bot.cmd('/poker leave');
  await bot.waitFor(RX.cashout, m, 8000).catch(() => {});
  await sleep(300);
}

/** The first of `bots` told "Your turn" after its mark. */
async function whoseTurn(bots, marks, timeout = 20000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    for (const b of bots) {
      if (b.since(marks.get(b)).some((l) => RX.turn.test(l))) return b;
    }
    await sleep(100);
  }
  throw new Error('nobody got a turn prompt');
}

/** Wait for a window other than `previous` (e.g. the raise submenu replacing the action menu). */
async function nextWindow(bot, previous, timeout = 5000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    const w = bot.bot.currentWindow;
    if (w && w !== previous) return w;
    await sleep(100);
  }
  throw new Error(`${bot.name}: no new window opened`);
}

// Item names arrive as an NBT compound on 1.20.5+ clients (bots connect as 26.1) and as a JSON string on 1.20.1.
function flat(x) {
  if (x == null) return '';
  if (typeof x === 'string') {
    const t = x.trim();
    if (/^[{\["]/.test(t)) { try { return flat(JSON.parse(t)); } catch (e) { /* plain text */ } }
    return x;
  }
  if (typeof x !== 'object') return String(x);
  if (Array.isArray(x)) return x.map(flat).join('');
  if (x.type && 'value' in x) return x.type === 'list' ? flat(x.value.value) : flat(x.value); // NBT tag
  return flat(x.text ?? x['']) + flat(x.extra);
}

function slotName(window, slot) {
  const item = window.slots[slot];
  if (!item) return null;
  return item.customName ? flat(item.customName) : (item.displayName || item.name);
}

/** A window can open before its items arrive (seen on a cold server); wait until `slot` is filled. */
async function filled(window, slot, timeout = 2000) {
  const end = Date.now() + timeout;
  while (Date.now() < end && !slotName(window, slot)) await sleep(100);
  return window;
}

function conserved(label, bots) {
  const total = bots.reduce((sum, b) => sum + balance(b.name), 0);
  check(`${label}: chips conserved (wallets back to ${bots.length} x ${WALLET})`, total === bots.length * WALLET,
    bots.map((b) => `${b.name}=${balance(b.name)}`).join(' '));
}

module.exports = {
  // Error paths: each answers with a clear line and moves no money.
  async d4CommandErrors(bots) {
    const [a, b] = bots;
    reset(bots);
    let m = b.mark();
    b.cmd('/poker fold');
    check('fold while not seated is refused', !!(await b.waitFor(/not seated at a poker table/i, m)));
    m = b.mark();
    b.cmd('/poker createtable');
    check('createtable needs poker.admin', !!(await b.waitFor(/permission/i, m)));
    m = a.mark();
    a.cmd('/poker nonsense');
    check('unknown subcommand is reported', !!(await a.waitFor(/Unknown subcommand/i, m)));
    m = b.mark();
    b.cmd('/poker join 5');
    check('buy-in below the table minimum is refused', !!(await b.waitFor(/buy-in here is between/i, m)));
    m = b.mark();
    b.cmd('/poker join 100 9');
    check('seat outside 1-6 is refused', !!(await b.waitFor(/Invalid seat/i, m)));
    m = b.mark();
    b.cmd('/poker join abc');
    check('non-numeric buy-in is refused', !!(await b.waitFor(/Invalid amount/i, m)));
    check('no money moved', balance(b.name) === WALLET, balance(b.name));
  },

  async d4Tables(bots, ctx) {
    const [a] = bots;
    const m = a.mark();
    a.cmd('/poker tables');
    const line = await a.waitFor(new RegExp(`#${ctx.tableId}\\b.*seated`), m);
    check('/poker tables lists the setup table', /0\/6 seated/.test(line), line);
  },

  // /poker join with no amount opens the buy-in menu; its first chip is the table minimum (100).
  async d4BuyInMenu(bots) {
    const [, b] = bots;
    reset(bots);
    const m = b.mark();
    b.cmd('/poker join');
    const w = await filled(await b.waitWindow(), 11);
    check('buy-in menu opened', !!w);
    check('slot 11 is the 100 minimum', /100/.test(slotName(w, 11) || ''), slotName(w, 11));
    check('slot 14 is the 2000 maximum', /2,?000/.test(slotName(w, 14) || ''), slotName(w, 14));
    await b.bot.clickWindow(11, 0, 0);
    await b.waitFor(RX.seated, m, 8000);
    check('bought in for exactly 100', balance(b.name) === WALLET - 100, balance(b.name));
    await leave(b);
    check('cashed out 100 alone at the table', balance(b.name) === WALLET, balance(b.name));
  },

  // Action menu -> raise submenu -> min-raise; the other player folds from the action menu.
  async d4ActionMenu(bots) {
    const [a, b] = bots;
    const two = [a, b];
    reset(bots);
    const marks = new Map(two.map((x) => [x, x.mark()]));
    await join(a, 500, 1);
    await join(b, 500, 2);
    const x = await whoseTurn(two, marks);
    const y = x === a ? b : a;

    const mx = x.mark();
    x.cmd('/poker menu');
    const menu = await filled(await x.waitWindow(), 10);
    check('action menu has fold in slot 10', /fold/i.test(slotName(menu, 10) || ''), slotName(menu, 10));
    check('action menu has call in slot 12', /call/i.test(slotName(menu, 12) || ''), slotName(menu, 12));
    await x.bot.clickWindow(14, 0, 0); // Raise...
    const raise = await filled(await nextWindow(x, menu), 4); // info item is always there
    // Options are centred in the middle row; here 1/2 pot equals the min raise, so there are three.
    const minSlot = [9, 10, 11, 12, 13, 14, 15, 16, 17].find((i) => /min raise/i.test(slotName(raise, i) || ''));
    check('raise menu offers min raise to 20', minSlot !== undefined && /20/.test(slotName(raise, minSlot)),
      minSlot === undefined ? 'no min-raise chip' : slotName(raise, minSlot));
    check('raise menu offers a pot raise to 30', [9, 10, 11, 12, 13, 14, 15, 16, 17]
      .some((i) => /pot.*30/i.test(slotName(raise, i) || '')));
    await x.bot.clickWindow(minSlot === undefined ? 12 : minSlot, 0, 0);
    const r = await x.waitFor(RX.raise, mx, 8000);
    check('min raise went through as a raise to 20', num(r.match(RX.raise)[1]) === 20, r);

    const my = y.mark();
    await y.waitFor(RX.turn, marks.get(y), 10000);
    y.cmd('/poker menu');
    await filled(await y.waitWindow(), 10);
    await y.bot.clickWindow(10, 0, 0); // Fold
    check('fold from the menu', !!(await y.waitFor(RX.fold, my, 8000)));
    check('raiser wins the pot', !!(await x.waitFor(RX.win, mx, 10000)));
    x.closeWindow();
    y.closeWindow();
    await leave(a);
    await leave(b);
    conserved('d4ActionMenu', two);
  },

  // Chat-command raise, fold, then a top-up between hands; money is exact throughout.
  async d4ChatRaiseAndTopUp(bots) {
    const [a, b] = bots;
    const two = [a, b];
    reset(bots);
    const marks = new Map(two.map((x) => [x, x.mark()]));
    await join(a, 300, 1);
    await join(b, 300, 2);
    const x = await whoseTurn(two, marks);
    const y = x === a ? b : a;
    const mx = x.mark();
    x.cmd('/poker raise 40');
    const r = await x.waitFor(RX.raise, mx, 8000);
    check('/poker raise 40 raises to 40', num(r.match(RX.raise)[1]) === 40, r);
    await y.waitFor(RX.turn, marks.get(y), 10000);
    const my = y.mark();
    y.cmd('/poker fold');
    await y.waitFor(RX.fold, my, 8000);
    await x.waitFor(RX.win, mx, 10000);

    // Top-up applies between hands, or is queued until the current hand ends.
    const before = balance(y.name);
    const mt = y.mark();
    y.cmd('/poker topup 50');
    const t = await y.waitFor(/Added|will be added/i, mt, 8000);
    await sleep(500);
    if (/Added/.test(t)) {
      check('top-up of 50 taken from the wallet', balance(y.name) === before - 50, `${t} ${balance(y.name)}`);
    } else {
      console.log('   (top-up was queued behind a new hand; covered by the conservation check)');
    }
    await leave(a);
    await leave(b);
    conserved('d4ChatRaiseAndTopUp', two);
  },

  // Round 2: the compact turn prompt, then "play again" after the hand. Both confirm (the plain command and an
  // alias) and a second hand is dealt; sitting down counted as ready, so the first hand needed no confirm.
  async d4PlayAgain(bots) {
    const [a, b] = bots;
    const two = [a, b];
    reset(bots);
    const marks = new Map(two.map((x) => [x, x.mark()]));
    await join(a, 300, 1);
    await join(b, 300, 2);
    const x = await whoseTurn(two, marks);
    const y = x === a ? b : a;
    const cardsLine = x.since(marks.get(x)).find((l) => /Your cards /.test(l));
    check('turn prompt shows your cards', !!cardsLine, cardsLine);
    check('hand strength hidden by default', !!cardsLine && !/You have: /.test(cardsLine), cardsLine);
    const mx = x.mark();
    const my = y.mark();
    x.cmd('/poker fold');
    await x.waitFor(RX.fold, mx, 8000);
    await y.waitFor(RX.win, my, 10000);
    for (const bot of two) {
      const prompt = await bot.waitFor(/Hand over|Play again|poker ready/i, bot === x ? mx : my, 15000);
      check(`${bot.name} asked to play again`, !!prompt, prompt);
    }
    const mx2 = x.mark();
    const my2 = y.mark();
    x.cmd('/poker ready');
    check('/poker ready confirms', !!(await x.waitFor(/You're in for the next hand/i, mx2, 8000)));
    y.cmd('/poker again');
    check('/poker again confirms', !!(await y.waitFor(/You're in for the next hand/i, my2, 8000)));
    const next = await x.waitFor(/Your (?:hole )?cards: /, mx2, 20000);
    check('second hand dealt after both confirmed', !!next, next);
    await leave(a);
    await leave(b);
    conserved('d4PlayAgain', two);
  },

  // Stats after the hands above, the others-permission, and PlaceholderAPI (26.3 only).
  async d4StatsAndPapi(bots) {
    const [a, b] = bots;
    let m = a.mark();
    a.cmd('/poker stats');
    const played = await a.waitFor(/Hands played: (\d+)|hasn't played/i, m);
    check('BotA has hands recorded', /Hands played: [1-9]/.test(played), played);
    m = b.mark();
    b.cmd('/poker stats BotA');
    check("non-op can't view others' stats", !!(await b.waitFor(/permission/i, m)));

    reset(bots);
    await join(a, 200, 1);
    const seated = rcon('papi parse BotA %poker_table_seated%');
    if (/Unknown command/i.test(seated)) {
      console.log('   (PlaceholderAPI not installed on this server; skipping PAPI checks)');
    } else {
      check('%poker_table_seated% is true while seated', /true/.test(seated), seated);
      check('%poker_table_stack% is 200', /\b200\b/.test(rcon('papi parse BotA %poker_table_stack%')));
      const hands = rcon('papi parse BotA %poker_stats_hands_played%');
      check('%poker_stats_hands_played% >= 1', /[1-9]/.test(hands), hands);
    }
    await leave(a);
    check('BotA wallet restored', balance(a.name) === WALLET, balance(a.name));
  },
};
