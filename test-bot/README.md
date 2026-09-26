# test-bot — headless mineflayer scenarios against poker-test

See /home/vortex/Poker-ops/TESTING.md. Only D1 runs these against the server.

    ln -sfn /home/vortex/Blackjack/test-server/bot/node_modules node_modules   # once per checkout (gitignored)
    MC_VERSION=26.1 node run.js [scenario ...]

- `lib.js` — TestBot + rcon/balance/check helpers (from Blackjack; RCON 25581/poker-test).
- `run.js` — runner + `setup` (D1). Bots: BotA, BotB, BotC. Table: 6 seats, blinds 5/10, centred 0,-60,0 facing north.
- `display.js` — display/visibility checks (D1).
- `game.js` — game-flow checks (D2).

Each scenario: `async name(bots, ctx)`; `ctx = { serverDir, legacy, tableId }`. Assert on direct-to-player lines
(broadcasts can be throttled), balances via `balance()`, and conserve chips (wallet + stack) across hands.
