# botcam — what a seated player sees

`capture.js` sits BotA/BotB/BotC in seats 1, 2 and 5 of run.js's table and plays one check/call hand.
At the flop and again at the showdown, it dumps each bot's **own** entity table. That table holds the
display entities the server actually sent that bot, so per-viewer hiding (face vs back) is real, not
simulated. It also dumps the blocks around the table and each bot's seated eye (RCON `Pos` + 1.62).
`render.js` then draws every view from that eye, with FOV 70.

The renderer has no dependencies: node + zlib and a software z-buffer. It uses:
- vanilla 26.3 block models, textures and font, extracted once to `/home/vortex/Poker-ops/botcam/assets`
- the Playing Cards pack from `/home/vortex/Blackjack/resourcepack/pack/assets`

Display entities follow the client's DisplayRenderer: position, then billboard/yaw, then translation,
left rotation, scale and right rotation. Items also get RotY(π) and the item/generated quad. On 1.20.1 the
CLOCK CustomModelData is mapped through `CardModels.MODEL_NAMES`.

```bash
# poker-test running (see Poker-ops/TESTING.md), from this directory:
ln -sfn /home/vortex/Blackjack/test-server/bot/node_modules ../test-bot/node_modules
MC_VERSION=26.1 node capture.js                    # -> shots/26.3/*.png, scenes/26.3/*.json
MC_VERSION=1.20.1 SERVER_DIR=/home/vortex/Poker-ops/test-server/paper-1.20.1 node capture.js
node render.js scenes/26.3/flop-seat1-BotA.json /tmp/x.png   # re-render a dump offline (no server)
```

It is not a real client. It skips smooth lighting/AO, shadows, the HUD, card edge thickness and player
skins (players are plain seated figures). Everything else is geometry-exact.
