// Checks that offline-mode players show their real Mojang skins (SkinsRestorer on the test server).
// Usage: node skincheck.js <name> <name> [...]   (exit 0 = every name carries its own Mojang skin)
// Each name joins as a bot; every bot reports the skin URL it was sent for the others (what a real client would
// render), and that URL is compared with the one Mojang's session server gives for the account of that name.
const { TestBot } = require('./lib');
const https = require('https');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function getJson(url) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers: { 'User-Agent': 'DefectiveVortex/Poker-test' } }, (res) => {
      let body = '';
      res.on('data', (c) => (body += c));
      res.on('end', () => {
        try { resolve(res.statusCode === 200 ? JSON.parse(body) : null); } catch (e) { reject(e); }
      });
    }).on('error', reject);
  });
}

async function mojangSkin(name) {
  const profile = await getJson(`https://api.mojang.com/users/profiles/minecraft/${name}`);
  if (!profile) return { exists: false };
  const session = await getJson(`https://sessionserver.mojang.com/session/minecraft/profile/${profile.id}`);
  const prop = session && (session.properties || []).find((p) => p.name === 'textures');
  const textures = prop ? JSON.parse(Buffer.from(prop.value, 'base64').toString()).textures : {};
  return { exists: true, uuid: profile.id, url: textures.SKIN ? textures.SKIN.url : null };
}

// TestBot answers the login-time resource pack offer and keeps movement packets off (see lib.js)
async function join(name) {
  const t = new TestBot(name);
  await t.connect();
  return t.bot;
}

(async () => {
  const names = process.argv.slice(2);
  if (names.length < 2) throw new Error('give at least two names (each one checks the others)');
  const bots = [];
  for (const n of names) {
    bots.push(await join(n));
    await sleep(4500); // connection throttle
  }
  await sleep(8000); // SkinsRestorer applies the skin after the join and re-sends the player info
  let bad = 0;
  for (const name of names) {
    const want = await mojangSkin(name);
    const observer = bots.find((b) => b.username !== name);
    const seen = observer.players[name] && observer.players[name].skinData;
    const got = seen ? seen.url : null;
    const ok = want.exists && want.url && got === want.url;
    if (!ok) bad++;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}: Mojang ${want.exists ? want.url || 'account has no custom skin' : 'NO SUCH ACCOUNT'}`
      + ` | seen by ${observer.username}: ${got || 'no skin sent (default Steve/Alex)'}`);
  }
  for (const b of bots) b.quit();
  await sleep(500);
  process.exit(bad ? 1 : 0);
})().catch((e) => { console.error(e.message || e); process.exit(2); });
