// Round-2 checks (D1): the play-again ready check and the board clearing between hands.
// Flow (D2): hand ends -> showdown pause -> clearHand -> ready-prompt to everyone -> next hand ~1 s after the last
// seated player confirms (/poker ready) with 2+ funded. Sitting down counts as confirmed; top-up does not.
// Timeout = config game.ready-timeout-seconds (read at every ready phase); these scenarios set it to 8 and restore 30.
const fs = require('fs');
const path = require('path');
const { rcon, balance, setBalance, sleep, check } = require('./lib');

const RX = {
  cards: /Your hole cards/i,
  turn: /Your turn.*to call: \D*([\d,]+)/,
  win: /\bwins?\b|You win/i,
  readyPrompt: /Play again|\/poker ready/i,
  confirmed: /You're in for the next hand/i,
  removed: /didn't confirm in time/i,
  cashout: /cash(?:ed)? out \D*([\d,]+)/i,
};
const TIMEOUT_S = 8;

const count = (sel) => {
  const out = rcon(`execute if entity ${sel}`);
  if (/failed/i.test(out)) return 0;
  const m = out.match(/(\d+)/);
  return m ? Number(m[1]) : 0;
};
const seated = (name) => /passed/i.test(rcon(`execute as ${name} on vehicle if entity @s[tag=poker-seat]`));

function setReadyTimeout(ctx, seconds) {
  const file = path.join(ctx.serverDir, 'plugins', 'Poker', 'config.yml');
  const text = fs.readFileSync(file, 'utf8');
  if (!/ready-timeout-seconds:/.test(text)) throw new Error('config.yml has no game.ready-timeout-seconds (old jar?)');
  fs.writeFileSync(file, text.replace(/(ready-timeout-seconds:\s*)\d+/, `$1${seconds}`));
  rcon('poker reload');
}

async function until(fn, timeout) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    if (fn()) return true;
    await sleep(250);
  }
  return false;
}

// Seat two bots (sitting counts as confirmed, so a hand starts) and return their marks from before joining.
async function sitTwo(a, b, stack = 500) {
  setBalance(a.name, 10000);
  setBalance(b.name, 10000);
  const marks = new Map([[a, a.mark()], [b, b.mark()]]);
  a.cmd(`/poker join ${stack} 1`);
  await sleep(700);
  b.cmd(`/poker join ${stack} 4`);
  for (const x of [a, b]) await x.waitFor(RX.cards, marks.get(x), 20000);
  return marks;
}

// Answer turn prompts after `marks` with `act(bot, toCall)` until someone gets the ready prompt.
async function playUntilReady(bots, marks, act, timeout = 90000) {
  const seen = new Map(marks);
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    for (const b of bots) {
      for (let i = seen.get(b); i < b.log.length; i++) {
        seen.set(b, i + 1);
        if (RX.readyPrompt.test(b.log[i])) return true;
        const t = b.log[i].match(RX.turn);
        if (t) await act(b, Number(t[1].replace(/,/g, '')));
      }
    }
    await sleep(150);
  }
  return false;
}
const checkOrCall = async (b, toCall) => b.cmd(toCall === 0 ? '/poker check' : '/poker call');

module.exports = {
  // #2 regression: a hand played to the river leaves no board cards once it is over.
  async boardClearsBetweenHands(bots, ctx) {
    const [a, b] = bots;
    setReadyTimeout(ctx, TIMEOUT_S);
    const marks = await sitTwo(a, b);
    let maxBoard = 0;
    const watch = setInterval(() => { maxBoard = Math.max(maxBoard, count('@e[tag=poker-board]')); }, 700);
    const prompted = await playUntilReady([a, b], marks, checkOrCall);
    clearInterval(watch);
    check('hand reached the ready prompt', prompted);
    check('board was dealt during the hand (5 cards seen)', maxBoard === 5, `max ${maxBoard}`);
    check('board is cleared by the ready prompt', await until(() => count('@e[tag=poker-board]') === 0, 1000),
      `${count('@e[tag=poker-board]')} board cards left`);

    // A hand that ends because a player leaves (no next hand possible) must clear too.
    for (const x of [a, b]) x.cmd('/poker ready');
    const m2 = new Map([[a, a.mark()], [b, b.mark()]]);
    await a.waitFor(RX.cards, m2.get(a), 15000);
    // Check to the flop (answering turns in the background), then BotB leaves.
    playUntilReady([a, b], m2, checkOrCall, 60000).catch(() => {});
    const flop = await until(() => count('@e[tag=poker-board]') >= 3, 60000);
    check('flop dealt in the second hand', flop);
    b.cmd('/poker leave');
    check('board cleared after a leave ends the hand', await until(() => count('@e[tag=poker-board]') === 0, 6000),
      `${count('@e[tag=poker-board]')} board cards left`);
    setReadyTimeout(ctx, 30);
  },

  // #3: both confirm -> the next hand deals; the confirm is acknowledged.
  async readyCheckConfirm(bots, ctx) {
    const [a, b] = bots;
    setReadyTimeout(ctx, TIMEOUT_S);
    const marks = await sitTwo(a, b);
    check('first hand dealt without a ready check (sitting counts)', true);
    const prompted = await playUntilReady([a, b], marks, async (x) => x.cmd('/poker fold'));
    check('ready prompt after the hand', prompted);
    const m = new Map([[a, a.mark()], [b, b.mark()]]);
    a.cmd('/poker ready');
    await a.waitFor(RX.confirmed, m.get(a), 4000).then(() => check('BotA told it is in', true),
      () => check('BotA told it is in', false));
    await sleep(1500);
    check('no hand yet while BotB has not confirmed', !a.since(m.get(a)).some((l) => RX.cards.test(l)));
    b.cmd('/poker ready');
    const dealt = await a.waitFor(RX.cards, m.get(a), 6000).then(() => true, () => false);
    check('next hand deals once both confirm', dealt);
    setReadyTimeout(ctx, 30);
  },

  // #3: whoever doesn't confirm in time is removed and cashed out exactly; the other stays seated.
  async readyCheckTimeout(bots, ctx) {
    const [a, b] = bots;
    setReadyTimeout(ctx, TIMEOUT_S);
    const marks = await sitTwo(a, b);
    const walletsBefore = balance(a.name) + balance(b.name) + 1000; // + both 500 stacks
    const prompted = await playUntilReady([a, b], marks, async (x) => x.cmd('/poker fold'));
    check('ready prompt after the hand', prompted);
    const m = b.mark();
    const t0 = Date.now();
    a.cmd('/poker ready');
    const line = await b.waitFor(RX.removed, m, (TIMEOUT_S + 6) * 1000).catch(() => null);
    const took = (Date.now() - t0) / 1000;
    check(`BotB removed after ~${TIMEOUT_S} s`, !!line && took >= TIMEOUT_S - 2, `${took.toFixed(1)} s: ${line}`);
    await sleep(1000);
    check('BotB no longer seated', !seated(b.name));
    check('BotA still seated', seated(a.name));
    // BotA is alone at the table now, so it cashes out on leave and both wallets must add up.
    a.cmd('/poker leave');
    await sleep(1500);
    const after = balance(a.name) + balance(b.name);
    check('chips conserved through the timeout cash-out', after === walletsBefore, `${after} vs ${walletsBefore}`);
    setReadyTimeout(ctx, 30);
  },
};

