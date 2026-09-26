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
| `/poker reload` | Reload `config.yml` and messages | `poker.admin` |
| `/poker version` | Show the plugin version | everyone |

Table settings are `seats`, `small-blind`, `big-blind`, `min-buy-in` and `max-buy-in` (both in big blinds), and `max-join-distance`. The seat count can only be changed while the table is empty.

## Permissions

| Permission | Default | |
|---|---|---|
| `poker.play` | everyone | Sit at tables and play |
| `poker.stats.others` | op | View other players' statistics |
| `poker.admin` | op | Build, change and remove tables, break table blocks, reload. Includes the two above |

## Configuration

`config.yml` holds the defaults for new tables (seats, blinds, buy-in range), the turn timer and pauses between hands, whether players use chest menus or chat buttons, the card display, the resource pack, sounds and particles. Every option is commented. Each table keeps its own settings once created; change them with `/poker settable`.

Messages live in `messages.yml` (English) and `messages_<code>.yml`. Pick a language with `language:` in `config.yml` (`en`, `ko`, `tr`, `ru`), and apply it with `/poker reload`. New keys are added to your files automatically on update, and anything a translation lacks falls back to English.

## Building

`./build.sh` builds `target/Poker-<version>.jar` with Maven in a container and runs the unit tests.
