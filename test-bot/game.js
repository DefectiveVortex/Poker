// Game-flow scenarios (D2). Run by run.js after `setup` has built a 6-seat 5/10 table at 0,-60,0.
// Assertions use only lines sent straight to one player (broadcasts can be throttled) plus TestEconomy
// balances. Money rule: every bot's wallet + table stack is the same before and after a hand; once they
// leave, the stack is back in the wallet, so "sum of wallet changes = 0" is the conservation check.
//
// The regexes below follow the English messages.yml (D4). If wording changes, change it here only.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { rcon, balance, setBalance, check, sleep } = require('./lib');

const RX = {
  seated: /You (?:sit down at|sat down in) seat (\d+)/,
  cards: /Your (?:hole )?cards: (.+)/,
  postSb: /You post the small blind/,
  postBb: /You post the big blind/,
  turn: /Your turn.*to call: \D*([\d,]+).*pot: \D*([\d,]+).*stack: \D*([\d,]+)/,
  win: /You win \D*([\d,]+)/,
  cashout: /You (?:leave|left) the table and cash(?:ed)? out \D*([\d,]+)|You (?:leave|left) the table\./,
  actionError: /can't check|nothing to call|can't bet|can't raise|minimum is|most you can/i,
  autoLeft: /stood up after missing|missed too many turns/i,
};

const WALLET = 10000;
const num = (s) => Number(String(s).replace(/[^\d]/g, ''));

function reset(bots) {
  for (const b of bots) setBalance(b.name, WALLET);
}

async function join(bot, amount, seat) {
  const m = bot.mark();
  bot.cmd(`/poker join ${amount} ${seat}`);
  const line = await bot.waitFor(RX.seated, m, 8000);
  check(`${bot.name} seated with ${amount}`, balance(bot.name) === WALLET - amount, line);
}

async function leave(bot) {
  const m = bot.mark();
  bot.cmd('/poker leave');
  return bot.waitFor(RX.cashout, m, 8000).catch(() => null);
}

/**
 * Play one hand. `strategy(bot, turn)` sends the bot's action when it gets a turn prompt
 * (turn = {toCall, pot, stack, n}). Resolves when a bot is told it won and no turn prompt followed
 * for `settleMs`. Returns {winners: [bot], lines: {name: [..]}}.
 */
async function playHand(bots, strategy, { timeout = 90000, settleMs = 2500 } = {}) {
  const marks = new Map(bots.map((b) => [b, b.mark()]));
  const start = new Map(marks);
  const turns = new Map(bots.map((b) => [b, 0]));
  const end = Date.now() + timeout;
  let lastWinAt = 0;
  while (Date.now() < end) {
    let acted = false;
    for (const b of bots) {
      const lines = b.log;
      for (let i = marks.get(b); i < lines.length; i++) {
        marks.set(b, i + 1);
        const t = lines[i].match(RX.turn);
        if (t) {
          turns.set(b, turns.get(b) + 1);
          await strategy(b, { toCall: num(t[1]), pot: num(t[2]), stack: num(t[3]), n: turns.get(b) });
          acted = true;
        } else if (RX.win.test(lines[i])) {
          lastWinAt = Date.now();
        }
      }
    }
    if (acted) lastWinAt = 0;
    if (lastWinAt && Date.now() - lastWinAt > settleMs) break;
    await sleep(150);
  }
  const lines = {};
  for (const b of bots) lines[b.name] = b.since(start.get(b));
  const winners = bots.filter((b) => lines[b.name].some((l) => RX.win.test(l)));
  check('hand finished', winners.length > 0, 'nobody was told they won');
  return { winners, lines };
}

/** Send `/poker <action>`; if the plugin refuses it, fall back to `fallback`. */
async function tryAction(bot, action, fallback) {
  const m = bot.mark();
  bot.cmd(`/poker ${action}`);
  await sleep(600);
  if (fallback && bot.since(m).some((l) => RX.actionError.test(l))) bot.cmd(`/poker ${fallback}`);
}

const checkOrCall = async (b, t) => b.cmd(t.toCall === 0 ? '/poker check' : '/poker call');

function assertConserved(label, bots) {
  const deltas = bots.map((b) => balance(b.name) - WALLET);
  check(`${label}: chips conserved`, deltas.reduce((a, d) => a + d, 0) === 0,
    bots.map((b, i) => `${b.name} ${deltas[i]}`).join(', '));
  return deltas;
}

/** Wait for the next hand's hole cards on every bot. */
async function waitForDeal(bots, marks, timeout = 20000) {
  for (const b of bots) {
    const line = await b.waitFor(RX.cards, marks.get(b), timeout);
    check(`${b.name} got hole cards`, /\S+ \S+/.test(line.split(': ').pop()), line);
  }
}

// Read the TestEconomy audit trail ("<op> <player> <amount> -> <balance>") from a log file.
function lastBalances(logText, names) {
  const out = {};
  for (const line of logText.split('\n')) {
    for (const n of names) {
      const m = line.match(new RegExp(`\\b${n}\\b.*->\\s*([\\d.,]+)`));
      if (m) out[n] = Math.round(Number(m[1].replace(/,/g, '')));
    }
  }
  return out;
}

module.exports = {
  // Heads-up: the button posts the small blind and acts first; folding gives the big blind the pot.
  async headsUpFold(bots) {
    const [a, b] = bots;
    const two = [a, b];
    reset(bots);
    const marks = new Map(two.map((x) => [x, x.mark()]));
    await join(a, 500, 1);
    await join(b, 500, 2);
    await waitForDeal(two, marks);
    const sb = two.find((x) => x.since(marks.get(x)).some((l) => RX.postSb.test(l)));
    const bb = two.find((x) => x !== sb);
    check('one small blind, one big blind', !!sb && bb.since(marks.get(bb)).some((l) => RX.postBb.test(l)));
    let firstToAct = null;
    const { winners } = await playHand(two, async (x) => {
      firstToAct = firstToAct || x;
      x.cmd('/poker fold');
    });
    check('heads-up: small blind (button) acts first', firstToAct === sb, firstToAct && firstToAct.name);
    check('big blind wins', winners.length === 1 && winners[0] === bb);
    const win = bb.since(marks.get(bb)).find((l) => RX.win.test(l));
    check('big blind is told it won 10', win && num(win.match(RX.win)[1]) === 10, win);
    await leave(a);
    await leave(b);
    const [da, db] = assertConserved('headsUpFold', two);
    check('small blind lost exactly 5', (sb === a ? da : db) === -5, `${da} ${db}`);
  },

  // Everyone checks or calls to the river; the showdown pays out and nothing is created or lost.
  async checkDown(bots) {
    const [a, b] = bots;
    const two = [a, b];
    reset(bots);
    const marks = new Map(two.map((x) => [x, x.mark()]));
    await join(a, 500, 1);
    await join(b, 500, 2);
    await waitForDeal(two, marks);
    const { winners } = await playHand(two, checkOrCall);
    check('showdown has a winner', winners.length >= 1);
    await leave(a);
    await leave(b);
    const d = assertConserved('checkDown', two);
    // Nobody bet beyond the big blind, so nobody can win or lose more than 10
    check('showdown moved at most the blinds', d.every((x) => Math.abs(x) <= 10), d.join(' '));
  },

  // Three different stacks all-in preflop: side pots, and the big stack's uncalled chips come back.
  async threeWayAllIn(bots) {
    const [a, b, c] = bots;
    reset(bots);
    const marks = new Map(bots.map((x) => [x, x.mark()]));
    await join(a, 100, 1);
    await join(b, 300, 3);
    await join(c, 500, 5);
    await waitForDeal(bots, marks);
    await playHand(bots, (x) => tryAction(x, 'allin', 'call'));
    for (const x of bots) await leave(x);
    const [da, db, dc] = assertConserved('threeWayAllIn', bots);
    // Most anyone can lose is what the others could match; most anyone can win is the others' matched chips
    check('100-stack result in range', da >= -100 && da <= 200, `${da}`);
    check('300-stack result in range', db >= -300 && db <= 400, `${db}`);
    check('500-stack never risks its top 200', dc >= -300 && dc <= 400, `${dc}`);
  },

  // A player who never acts is checked/folded by the timer and stood up after max-missed-turns (2).
  async turnTimeout(bots) {
    const [a, b] = bots;
    const two = [a, b];
    reset(bots);
    const m = a.mark();
    await join(a, 500, 1);
    await join(b, 500, 2);
    // b plays (checks/calls); a never answers. Two timed-out hands (~30 s each) stand a up.
    const end = Date.now() + 150000;
    let bMark = b.mark();
    let left = null;
    while (Date.now() < end && !left) {
      for (let i = bMark; i < b.log.length; i++) {
        bMark = i + 1;
        const t = b.log[i].match(RX.turn);
        if (t) await checkOrCall(b, { toCall: num(t[1]) });
      }
      left = a.since(m).find((l) => RX.autoLeft.test(l));
      await sleep(300);
    }
    check('idle player stood up after missed turns', !!left, a.since(m).slice(-5).join(' | '));
    check('idle player cashed out', a.since(m).some((l) => RX.cashout.test(l)));
    await leave(b);
    assertConserved('turnTimeout', two);
  },

  // Leaving mid-hand folds: the leaver loses only their blind, net 5 either way.
  async leaveMidHand(bots) {
    const [a, b] = bots;
    const two = [a, b];
    reset(bots);
    const marks = new Map(two.map((x) => [x, x.mark()]));
    await join(a, 500, 1);
    await join(b, 500, 2);
    await waitForDeal(two, marks);
    const cash = await leave(a);
    check('leaver told what they cashed out', !!cash, String(cash));
    const bWin = await b.waitFor(RX.win, marks.get(b), 5000).catch(() => null);
    check('remaining player wins uncontested', !!bWin, String(bWin));
    await leave(b);
    const [da, db] = assertConserved('leaveMidHand', two);
    // As SB the leaver loses 5; as BB the unmatched 5 comes back, so also -5
    check('leaver lost exactly 5', da === -5 && db === 5, `${da} ${db}`);
  },

  // Part 1 of the shutdown check: seat two bots, let chips go in, then stop the server mid-hand.
  // Bots stay connected so it's the plugin's shutdown that refunds, not a quit. D1 then starts the
  // server and runs afterRestart, which reads the refunds from the previous log.
  async beforeRestart(bots, ctx) {
    const [a, b] = bots;
    const two = [a, b];
    reset(bots);
    const marks = new Map(two.map((x) => [x, x.mark()]));
    await join(a, 500, 1);
    await join(b, 500, 2);
    await waitForDeal(two, marks);
    // First to act calls, so both have chips in the pot when the server stops
    const first = await Promise.race(two.map((x) => x.waitFor(RX.turn, marks.get(x), 10000).then(() => x)));
    first.cmd('/poker call');
    await sleep(1500);
    const file = path.join(ctx.serverDir, '..', 'poker-restart.json');
    fs.writeFileSync(file, JSON.stringify({ expect: { [a.name]: WALLET, [b.name]: WALLET }, at: Date.now() }));
    check('hand in progress before stop', two.every((x) => balance(x.name) === WALLET - 500));
    try {
      rcon('stop');
    } catch (e) {
      // the connection can drop as the server goes down
    }
    await sleep(8000); // bots get kicked as the server goes down
  },

  // Part 2: the plugin's shutdown must have voided the hand and paid wallet + stack back in full.
  async afterRestart(bots, ctx) {
    const file = path.join(ctx.serverDir, '..', 'poker-restart.json');
    const want = JSON.parse(fs.readFileSync(file, 'utf8')).expect;
    const logs = path.join(ctx.serverDir, 'logs');
    const prev = fs.readdirSync(logs).filter((f) => f.endsWith('.log.gz'))
      .map((f) => ({ f, t: fs.statSync(path.join(logs, f)).mtimeMs })).sort((x, y) => y.t - x.t)[0];
    check('previous server log found', !!prev);
    if (!prev) return;
    const text = zlib.gunzipSync(fs.readFileSync(path.join(logs, prev.f))).toString('utf8');
    const got = lastBalances(text, Object.keys(want));
    for (const [name, amount] of Object.entries(want)) {
      check(`${name} refunded to ${amount} at shutdown`, got[name] === amount, `last balance ${got[name]}`);
    }
  },
};
