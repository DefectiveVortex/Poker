# Poker

No-limit Texas Hold'em cash tables for Spigot and Paper. The tables are physical objects in the world. An admin builds one where they're standing, and players right-click a chair to sit down and buy in with Vault money. Cards are dealt onto the felt as item displays, using the same Playing Cards resource pack as [Blackjack](https://modrinth.com/plugin/bjplugin). Every action is available from a chest menu, from clickable chat buttons, and as a plain command.

## Features

- 2–8 seats per table (6 by default), with the chairs built around the felt.
- Cash-game rules. The button rotates with a small and a big blind; heads-up, the button posts the small blind and acts first preflop. You can fold, check, call, bet, raise or go all-in. The minimum raise is the last full raise, and a short all-in doesn't reopen the betting. Side pots are handled, and the odd chip of a split pot goes to the first seat left of the button. A hand won by everyone folding isn't shown.
- Buy-ins are set in big blinds (40–100 BB by default). Money moves from Vault into your table stack when you sit and back when you leave. You can top up between hands.
- A turn timer checks for you if it can, otherwise folds. Players who keep timing out are stood up.
- Leaving mid-hand counts as a fold, and the rest of your stack is returned. A server shutdown mid-hand cancels the hand and refunds everything that was bet in it.
- A raise menu with min-raise, ½ pot, pot, all-in and a typed custom amount.
- Per-player statistics: hands played and won, showdowns won, biggest pot, net winnings.
- PlaceholderAPI placeholders, including leaderboards (see [PLACEHOLDERAPI.md](PLACEHOLDERAPI.md)).
- Ships in English, Korean, Turkish and Russian, and every message can be edited.

## Requirements

| | |
|---|---|
| Server | Spigot, Paper, Purpur or another Bukkit fork, **1.20 or newer** |
| Java | 17 or newer |
| Required plugins | [Vault](https://www.spigotmc.org/resources/vault.34315/) plus a Vault economy plugin (EssentialsX, CMI, etc.) |
| Optional plugins | [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/), [GSit](https://www.spigotmc.org/resources/gsit.62325/) |
| Resource pack | [Playing Cards](https://modrinth.com/resourcepack/bjplayingcards), for the card textures (offered to players automatically) |

## Getting started

1. Put `Poker.jar` in `plugins/`, next to Vault and your economy plugin, and restart.
2. Stand where the table should go, facing along its long side, and run `/poker createtable`. Options go in `key:value` form: `/poker createtable seats:8 small-blind:25 big-blind:50 min-buy-in:50 max-buy-in:200`.
3. Players right-click a chair, choose a buy-in, and the first hand starts as soon as two players have chips.

## Commands

All commands are `/poker` (alias `/pk`). For `bet` and `raise`, the amount is your **total** for this betting round ("raise to").

| Command | What it does | Permission |
|---|---|---|
| `/poker join [amount] [seat]` | Sit at the nearest table; without an amount you get the buy-in menu | `poker.play` |
| `/poker leave` | Stand up and cash out (a fold if you're in a hand) | `poker.play` |
| `/poker fold` · `check` · `call` · `allin` | Act on your turn | `poker.play` |
| `/poker bet <amount>` · `raise <amount>` | Bet or raise to a total | `poker.play` |
| `/poker topup [amount]` | Add chips from your balance (applied between hands) | `poker.play` |
| `/poker menu` | The action menu on your turn, otherwise the top-up menu | `poker.play` |
| `/poker stats [player]` | Statistics; other players need `poker.stats.others` | everyone |
| `/poker tables` | List tables with their IDs | everyone |
| `/poker createtable [key:value…]` | Build a table here | `poker.admin` |
| `/poker settable <key:value…>` | Change the nearest table (`settable big-blind 50` works too) | `poker.admin` |
| `/poker removetable [id]` | Remove the nearest table, or one by ID (seated players are cashed out) | `poker.admin` |
| `/poker cleanup [radius]` | Remove leftover card displays nearby | `poker.admin` |
| `/poker reload` | Check, repair and reload `config.yml` and messages | `poker.admin` |
| `/poker version` | The plugin version and whether an update is out | everyone |
| `/poker update` | Check Modrinth now and download a newer version | `poker.admin` |

Table settings are `seats`, `small-blind`, `big-blind`, `min-buy-in` and `max-buy-in` (both in big blinds), and `max-join-distance`. The seat count can only be changed while the table is empty.

## Permissions

| Permission | Default | |
|---|---|---|
| `poker.play` | everyone | Sit at tables and play |
| `poker.stats.others` | op | View other players' statistics |
| `poker.admin` | op | Build, change and remove tables, break table blocks, reload. Includes the two above |

## Configuration

`config.yml` holds the defaults for new tables (seats, blinds, buy-in range), the turn timer and pauses between hands, whether players use chest menus or chat buttons, the card display, the resource pack, sounds and particles. Every option is commented. Each table keeps its own settings once created; change them with `/poker settable`.

By default the turn prompt shows your cards and the board but not what hand you hold, so players read their own hand. Set `interface.show-hand-strength: true` to add "You have: Two Pair, Kings and Sevens" to it. Showdowns and the winner's title always name the winning hand.

Messages live in `messages.yml` (English) and `messages_<code>.yml`. Pick a language with `language:` in `config.yml` (`en`, `ko`, `tr`, `ru`), and apply it with `/poker reload`. Anything a translation lacks falls back to English.

### Updates

Poker checks [Modrinth](https://modrinth.com/plugin/pokerplugin) for new versions at startup and every `updates.interval-hours`, tells admins when they join, and with `updates.auto-download: true` puts the new jar in `plugins/update/`, so it's installed on the next restart. The running version is never replaced. `updates.channel` is `release`, `beta` or `alpha`; set `updates.check: false` to turn it all off.

### Your files survive updates and mistakes

Every start and every `/poker reload` checks `config.yml`, the messages file in use and `stats.yml` before anything reads them. What it finds goes to the console, and `/poker reload` tells you how many warnings there were.

| Problem | What Poker does |
|---|---|
| A file is missing | Recreates it from the default |
| A file isn't valid YAML (a stray tab, a missing quote, a half-written file) | Renames it to `<name>.broken-<date>-<time>` (it never deletes your file), rebuilds it from the defaults, and keeps every setting it could still read. For `stats.yml` it keeps every player entry it could read |
| A setting is missing, or the file is from an older version | Adds the new settings with their defaults and moves renamed ones, keeping your values and comments. The previous file is saved as `<name>.pre-update.bak` |
| A setting has the wrong type or makes no sense (`big-blind: lots`, a negative blind, a big blind below the small blind) | Uses the default for that setting only and names it in a warning. Your file isn't changed, so fix it and `/poker reload` |
| A setting Poker doesn't know | Leaves it where it is and warns once per load |
| A known setting at the wrong level (e.g. `chat-buttons:` at the top instead of under `interface:`) | Moves it where it belongs |
| A message still has an older version's default text | Replaces it with the new default. Messages you've changed are never touched |

`config-version:` at the top of each file is what upgrades use. Leave it as it is.

## Building

`./build.sh` builds `target/Poker-<version>.jar` with Maven in a container and runs the unit tests.
