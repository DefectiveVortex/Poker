// A sparring bot for manual testing: sits at the table and plays every hand.
// Usage: node opponent.js [BotName] [seat] [buyIn]      (defaults BotB 4 1000)
// Default play: check when free, call otherwise. Steer it from game chat:
//   "bot fold" | "bot check" | "bot call" | "bot allin" | "bot raise 60" | "bot bet 40"  -> its next action
//   "bot passive" (default) | "bot aggro" (min-raises when it can) | "bot leave" | "bot join"
//   "bot stay" (default: confirms play-again after each hand) | "bot quit" (stops confirming, so it times out)
const { TestBot, sleep } = require('./lib');

const [name = 'BotB', seat = '4', buyIn = '1000'] = process.argv.slice(2);
const TURN = /Your turn.*to call: \D*([\d,]+)/;
const READY = /Play again|\/poker ready/i; // round-2 ready check: confirm after every hand
const SAY = /^<(\w+)> bot (fold|check|call|allin|passive|aggro|leave|join|stay|quit|(?:raise|bet) \d+)\s*$/i;

(async () => {
  const b = new TestBot(name);
  await b.connect();
  let next = null;
  let mode = 'passive';
  let stay = true;
  let seen = 0;
  const join = () => b.cmd(`/poker join ${buyIn} ${seat}`);
  join();
  console.log(`${name} joined seat ${seat} for ${buyIn}`);
  b.bot.on('end', () => { console.log('disconnected'); process.exit(1); });
  for (;;) {
    while (seen < b.log.length) {
      const line = b.log[seen++];
      if (process.env.VERBOSE) console.log('  ' + line);
      const say = line.match(SAY);
      if (say) {
        const c = say[2].toLowerCase();
        if (c === 'passive' || c === 'aggro') { mode = c; b.bot.chat(`ok, playing ${c}`); }
        else if (c === 'stay' || c === 'quit') { stay = c === 'stay'; b.bot.chat(stay ? 'ok, I will keep playing' : 'ok, I will not confirm the next hand'); }
        else if (c === 'leave') b.cmd('/poker leave');
        else if (c === 'join') join();
        else { next = c; b.bot.chat(`ok, next action: ${c}`); }
        continue;
      }
      if (/left the table|cash out|busted/i.test(line) && !/You're not seated/.test(line)) console.log(line);
      if (READY.test(line) && !/You're in/i.test(line)) {
        if (stay) { await sleep(1500); b.cmd('/poker ready'); console.log('confirmed play again'); }
        continue;
      }
      const t = line.match(TURN);
      if (!t) continue;
      await sleep(1200); // give a human time to see whose turn it is
      const toCall = Number(t[1].replace(/,/g, ''));
      let act = next || (toCall === 0 ? 'check' : 'call');
      if (!next && mode === 'aggro') act = toCall === 0 ? 'bet 20' : 'raise ' + (toCall * 2 + 10);
      next = null;
      b.cmd(`/poker ${act}`);
      console.log(`turn (to call ${toCall}): ${act}`);
    }
    await sleep(200);
  }
})().catch((e) => { console.error(e); process.exit(2); });
