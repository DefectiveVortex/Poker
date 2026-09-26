# PlaceholderAPI placeholders

Poker registers the `poker` expansion when [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) is installed. Nothing needs downloading with `/papi ecloud`. Test a placeholder with `/papi parse me %poker_stats_hands_played%`.

Amounts are whole currency units. Placeholders ending in `_formatted` use the language's `currency-format`.

## Player statistics

These work for offline players too.

| Placeholder | Value |
|---|---|
| `%poker_stats_hands_played%` | Hands the player was dealt into |
| `%poker_stats_hands_won%` | Hands where they won all or part of a pot |
| `%poker_stats_win_rate%` | Hands won as a percentage of hands played (whole number) |
| `%poker_stats_showdowns_won%` | Pots won at a showdown, rather than by everyone folding |
| `%poker_stats_biggest_pot%` | The most they've collected in a single hand |
| `%poker_stats_biggest_pot_formatted%` | The same, with the currency format |
| `%poker_stats_net%` | Lifetime winnings minus losses (can be negative) |
| `%poker_stats_net_formatted%` | The same, with the currency format |

## The player's table

These are empty when the player isn't seated, except `table_seated`.

| Placeholder | Value |
|---|---|
| `%poker_table_seated%` | `true` or `false` |
| `%poker_table_id%` | Table ID, as shown by `/poker tables` |
| `%poker_table_seat%` | Seat number, from 1 |
| `%poker_table_stack%` | Chips in front of the player |
| `%poker_table_players%` | Players seated at the table |
| `%poker_table_seats%` | Seats at the table |
| `%poker_table_pot%` | The current pot, all bets included |
| `%poker_table_small_blind%`, `%poker_table_big_blind%` | The blinds |
| `%poker_table_blinds%` | Both, e.g. `10/20` |
| `%poker_table_in_hand%` | `true` while a hand is being played |
| `%poker_table_my_turn%` | `true` when it's this player's turn |

## Leaderboards

`%poker_top_<ranking>_<position>_name%` and `%poker_top_<ranking>_<position>_value%`. The position counts from 1 (up to 100). Rankings:

| Ranking | Ordered by |
|---|---|
| `net` | Net winnings |
| `won` | Hands won |
| `played` | Hands played |
| `biggest_pot` | Biggest pot won |

For example, `%poker_top_net_1_name%` is the player with the highest net winnings and `%poker_top_net_1_value%` is that amount. An empty position shows `-` for the name and `0` for the value.
