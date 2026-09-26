// Display checks (D1, from D3's spec in Poker-ops/test-server/bot/D3-display-notes.md).
// Table from run.js setup: 6 seats centred on 0,-60,0 facing north. Seat 1 (1-based) = slot at (2,-60,-2),
// seat 4 = (-2,-60,2). Hole cards sit at y -58.97: seat 1's at x 1.65, seat 4's at x -0.65, the board at x 0.5.
const { rcon, sleep, check } = require('./lib');

const count = (sel) => {
  const out = rcon(`execute if entity ${sel}`);
  if (/failed/i.test(out)) return 0;
  const m = out.match(/(\d+)/);
  return m ? Number(m[1]) : 0;
};

async function until(fn, timeout = 15000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    if (fn()) return true;
    await sleep(500);
  }
  return false;
}

// Card-shaped item_displays this client has been sent, classified by model and position.
function seenCards(bot) {
  const out = { seat1: { face: 0, back: 0 }, seat4: { face: 0, back: 0 }, board: { face: 0, back: 0 } };
  for (const e of Object.values(bot.bot.entities)) {
    if (e.name !== 'item_display') continue;
    const p = e.position;
    if (Math.abs(p.y + 58.97) > 0.2 || Math.abs(p.x) > 3 || Math.abs(p.z) > 3) continue;
    const meta = JSON.stringify(e.metadata);
    let kind = null;
    if (/card\/back|\b21000\b/.test(meta)) kind = 'back';
    else if (/card\/[shdc](?:10|[1-9jqk])|\b210(?:0[1-9]|[1-4]\d|5[0-2])\b/.test(meta)) kind = 'face';
    if (!kind) continue;
    const where = p.x > 1.2 ? 'seat1' : p.x < 0 ? 'seat4' : 'board';
    out[where][kind]++;
  }
  return out;
}

async function sitTwo(bots) {
  const [a, b] = bots;
  a.cmd('/poker join 500 1');
  await sleep(700);
  b.cmd('/poker join 500 4');
  // Two funded players start a hand on their own; wait until both seats have their backs out.
  return until(() => count('@e[type=item_display,tag=poker-card-back]') >= 4);
}

module.exports = {
  // 1. Card items carry the pack model: item_model on 1.21.2+, CustomModelData 21000-21053 before.
  async cardModels(bots, ctx) {
    check('a hand is dealt with 2 players', await sitTwo(bots));
    const face = rcon('data get entity @e[type=item_display,tag=poker-card,limit=1] item');
    const back = rcon('data get entity @e[type=item_display,tag=poker-card-back,limit=1] item');
    if (ctx.legacy) {
      check('face uses CustomModelData 21001-21052', /CustomModelData:\s*210(0[1-9]|[1-4]\d|5[0-2])\b/.test(face), face);
      check('back uses CustomModelData 21000', /CustomModelData:\s*21000\b/.test(back), back);
    } else {
      check('face uses item_model playing_cards:card/<id>', /playing_cards:card\/[shdc](10|[1-9jqk])"/.test(face), face);
      check('back uses item_model playing_cards:card/back', /playing_cards:card\/back"/.test(back), back);
    }
  },

  // 2. Each player sees only their own hole-card faces; the other seat shows backs.
  async holeCardPrivacy(bots) {
    const [a, b] = bots;
    check('a hand is dealt with 2 players', await sitTwo(bots));
    await sleep(1500); // let entity spawns/hides reach the clients
    const sa = seenCards(a);
    const sb = seenCards(b);
    check('BotA sees its own 2 faces, no backs', sa.seat1.face === 2 && sa.seat1.back === 0, JSON.stringify(sa));
    check("BotA sees BotB's 2 backs, no faces", sa.seat4.back === 2 && sa.seat4.face === 0, JSON.stringify(sa));
    check('BotB sees its own 2 faces, no backs', sb.seat4.face === 2 && sb.seat4.back === 0, JSON.stringify(sb));
    check("BotB sees BotA's 2 backs, no faces", sb.seat1.back === 2 && sb.seat1.face === 0, JSON.stringify(sb));
    check('both see the same board', sa.board.face === sb.board.face && sa.board.back === 0, `${sa.board.face} vs ${sb.board.face}`);
    const serverFaces = count('@e[type=item_display,tag=poker-card]');
    check('server has 4 hole faces + board', serverFaces === 4 + sa.board.face, `server ${serverFaces}, board ${sa.board.face}`);
  },

  // 3. Removing the table removes every entity it spawned. Runs last: rebuilds the table afterwards.
  async removeTableCleansUp(bots, ctx) {
    const [a] = bots;
    await sitTwo(bots);
    check('table has display entities before removal', count('@e[tag=poker-display]') > 0);
    rcon(`tp ${a.name} 0.5 -60 0.5 180 0`);
    await sleep(500);
    a.cmd('/poker removetable');
    const gone = await until(() => count('@e[tag=poker-display]') === 0 && count('@e[tag=poker-seat]') === 0, 5000);
    check('no poker-display / poker-seat entities remain', gone,
      `display=${count('@e[tag=poker-display]')} seat=${count('@e[tag=poker-seat]')}`);
    const m = a.mark();
    a.cmd('/poker createtable seats:6 small-blind:5 big-blind:10 min-buy-in:10 max-buy-in:200');
    await a.waitFor(/created/i, m);
  },
};
