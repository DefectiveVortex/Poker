// Display checks (D1, from D3's spec in Poker-ops/test-server/bot/D3-display-notes.md).
// Table from run.js setup: 6 seats centred on 0,-60,0 facing north. Cards are located through D3's tags, not geometry.
const { TestBot, rcon, setBalance, sleep, check } = require('./lib');

const count = (sel) => {
  const out = rcon(`execute if entity ${sel}`);
  if (/Test failed/i.test(out)) return 0;
  const m = out.match(/Count:\s*(\d+)/i);
  if (!m) throw new Error(`bad selector ${sel}: ${out}`); // never read a number out of an error message
  return Number(m[1]);
};

async function until(fn, timeout = 15000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    if (fn()) return true;
    await sleep(500);
  }
  return false;
}

// Where the server says a tagged card is (first match), or null.
function tagPos(tag) {
  const out = rcon(`data get entity @e[type=item_display,tag=${tag},limit=1] Pos`);
  const m = out.match(/\[(-?[\d.]+)d?,\s*(-?[\d.]+)d?,\s*(-?[\d.]+)d?\]/);
  return m ? { x: Number(m[1]), y: Number(m[2]), z: Number(m[3]) } : null;
}

// Card-shaped item_displays this client has been sent, classified by model, and by position against the
// server-side positions of D3's tags (poker-seat-card-<0-based seat>, poker-board), so layout changes don't matter.
// Seat 1 / seat 4 in commands are 0-based seats 0 / 3.
function seenCards(bot) {
  const anchors = { seat1: tagPos('poker-seat-card-0'), seat4: tagPos('poker-seat-card-3'), board: tagPos('poker-board') };
  const out = { seat1: { face: 0, back: 0 }, seat4: { face: 0, back: 0 }, board: { face: 0, back: 0 } };
  for (const e of Object.values(bot.bot.entities)) {
    if (e.name !== 'item_display') continue;
    const meta = JSON.stringify(e.metadata);
    let kind = null;
    if (/card\/back|\b21000\b/.test(meta)) kind = 'back';
    else if (/card\/[shdc](?:10|[1-9jqk])|\b210(?:0[1-9]|[1-4]\d|5[0-2])\b/.test(meta)) kind = 'face';
    if (!kind) continue;
    const p = e.position;
    let where = null;
    let best = Infinity;
    for (const [k, a] of Object.entries(anchors)) {
      if (!a) continue;
      const d = Math.hypot(p.x - a.x, p.y - a.y, p.z - a.z);
      // hole cards sit within a card-width of their anchor; the board row spans ~1.5 blocks from its first card
      if (d < (k === 'board' ? 1.8 : 0.45) && d < best) { best = d; where = k; }
    }
    if (where) out[where][kind]++;
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

  // Round 2 (#1 compact layout): from every chair of a full 6-seat table, the rider can read its own hole cards and
  // the whole board. Limits from D3: own hole cards <= 0.9 from the eye, a board card <= 2.2, every board card <= 2.9.
  async chairView(bots) {
    const extra = ['BotD', 'BotE', 'BotF'].map((n) => new TestBot(n));
    for (const b of extra) {
      rcon(`whitelist add ${b.name}`);
      await b.connect();
      rcon(`tp ${b.name} 3 -60 3`);
    }
    const six = [...bots, ...extra];
    try {
      for (let i = 0; i < 6; i++) {
        setBalance(six[i].name, 10000);
        six[i].cmd(`/poker join 500 ${i + 1}`);
        await sleep(600);
      }
      // A hand starts as soon as two sit, so the rest join the next one: keep checking/calling and confirming
      // play-again until a hand has all six dealt in, then stop acting once its river is out and measure.
      const seen = new Map(six.map((b) => [b, Math.max(0, b.log.length - 30)]));
      const end = Date.now() + 180000;
      let full = false;
      while (Date.now() < end) {
        if (count('@e[tag=poker-card-back]') >= 12) full = true;
        if (full && count('@e[tag=poker-board]') === 5) break;
        for (const b of six) {
          for (let i = seen.get(b); i < b.log.length; i++) {
            seen.set(b, i + 1);
            if (/Hand over\..*poker ready/.test(b.log[i])) b.cmd('/poker ready');
            const t = b.log[i].match(/Your turn.*to call: \D*([\d,]+)/);
            if (t) b.cmd(Number(t[1].replace(/,/g, '')) === 0 ? '/poker check' : '/poker call');
          }
        }
        await sleep(200);
      }
      check('a hand with all 6 seats dealt in', full, `backs=${count('@e[tag=poker-card-back]')}`);
      check('river dealt with 6 players', count('@e[tag=poker-board]') === 5);
      for (let s = 0; s < 6; s++) {
        const who = six[s].name;
        const y = Number((rcon(`data get entity ${who} Pos[1]`).match(/(-?[\d.]+)d?\s*$/) || [])[1]);
        const eye = `execute as ${who} at @s positioned ~ ~1.62 ~ if entity`;
        const n = (sel) => {
          const o = rcon(`${eye} ${sel}`);
          if (/Test failed/i.test(o)) return 0;
          const m = o.match(/Count:\s*(\d+)/i);
          if (!m) throw new Error(`bad selector ${sel}: ${o}`);
          return Number(m[1]);
        };
        const own = n(`@e[tag=poker-seat-card-${s},distance=..0.9]`);
        const near = n('@e[tag=poker-board,distance=..2.2]');
        const all = n('@e[tag=poker-board,distance=..2.9]');
        check(`seat ${s + 1}: eye ${(y + 1.62).toFixed(2)} (chair block -60 + 1.52 = -58.48 expected)`, Math.abs(y + 1.62 + 58.48) < 0.2, `feet y ${y}`);
        check(`seat ${s + 1}: own hole cards within 0.9 of the eye`, own >= 2, `${own} card entities`);
        check(`seat ${s + 1}: a board card within 2.2`, near >= 1, `${near}`);
        check(`seat ${s + 1}: all 5 board cards within 2.9`, all === 5, `${all}`);
      }
    } finally {
      for (const b of extra) {
        try { b.cmd('/poker leave'); } catch (e) { /* gone */ }
      }
      await sleep(1500);
      for (const b of extra) await b.quit();
    }
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
    const line = await a.waitFor(/table #(\d+) created/i, m);
    ctx.tableId = Number(line.match(/#(\d+)/)[1]); // later scenarios look the table up by id
  },
};
