package com.vortex.poker.table;

import com.vortex.poker.PokerPlugin;
import com.vortex.poker.config.ConfigManager;
import com.vortex.poker.display.TableView;
import com.vortex.poker.display.WorldTableView;
import com.vortex.poker.economy.EconomyProvider;
import com.vortex.poker.game.ActionOptions;
import com.vortex.poker.game.ActionType;
import com.vortex.poker.game.HandSummary;
import com.vortex.poker.game.HoldemGame;
import com.vortex.poker.game.HoldemListener;
import com.vortex.poker.game.IllegalActionException;
import com.vortex.poker.game.SeatResult;
import com.vortex.poker.game.ShowdownHand;
import com.vortex.poker.game.Street;
import com.vortex.poker.game.VoidResult;
import com.vortex.poker.model.Card;
import com.vortex.poker.model.Deck;
import com.vortex.poker.model.HandValue;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One poker table in the world: seats, buy-ins and cash-outs, the turn timer and the pause between
 * hands. The rules live in {@link HoldemGame}; this class turns its events into cards on the felt
 * ({@link TableView}) and chat, and turns commands and clicks into game actions.
 *
 * <p>Money: a buy-in moves from Vault onto the table stack when a player sits; the stack (plus any
 * top-up not yet applied) goes back to Vault when they leave. Everything runs on the main thread.
 */
public class PokerTable implements HoldemListener {

    /** Ticks between runout streets, and before the showdown reveal and the payout after it. */
    private static final long RUNOUT_TICKS = 30L;
    private static final long REVEAL_TICKS = 20L;
    private static final long AWARD_TICKS = 30L;
    /** Hands a player with no chips may sit out before they are stood up. */
    private static final int MAX_BUSTED_HANDS = 3;
    /** How long a seat stays reserved while its player picks a buy-in. */
    private static final long RESERVE_MILLIS = 60_000L;
    private static final long SNEAK_CONFIRM_MILLIS = 3_000L;

    private final PokerPlugin plugin;
    private final TableManager manager;
    private final int id;
    private final TableLayout layout;
    private final TableSettings settings;
    private final TableView view;
    private final Seating seating;
    private final HoldemGame game;

    private final Map<Integer, UUID> reservedSeats = new HashMap<>();
    private final Map<Integer, Long> reservedUntil = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<Integer, String> lastAction = new HashMap<>();
    private final Map<UUID, Integer> missedTurns = new HashMap<>();
    private final Map<UUID, Long> pendingLeaves = new HashMap<>();
    private final Map<UUID, Integer> bustedHands = new HashMap<>();
    private final Set<BukkitTask> scheduled = new HashSet<>();

    private BukkitTask turnTimer;
    private BukkitTask nextHandTask;
    private boolean closed;

    // Visual pacing inside one call into the game: runout streets, the reveal and the payout are
    // spread out by pushing them further back on this cue instead of all landing on the same tick.
    private long cue;
    private int streetsThisCall;

    public PokerTable(PokerPlugin plugin, TableManager manager, int id, TableLayout layout, TableSettings settings) {
        this.plugin = plugin;
        this.manager = manager;
        this.id = id;
        this.layout = layout;
        this.settings = settings;
        ConfigManager cfg = plugin.getConfigManager();
        this.game = new HoldemGame(layout.getSeatCount(), settings.getSmallBlind(cfg), settings.getBigBlind(cfg), Deck::new);
        this.game.setListener(this);
        this.view = new WorldTableView(plugin, layout, id);
        this.seating = new Seating(plugin, layout, id);
        view.setPotInfo("");
    }

    // =====================================================================================
    // Joining, buying in, topping up, leaving
    // =====================================================================================

    /** A player clicked a chair (seat) or used /poker join: pick a seat and ask for a buy-in. */
    public void join(Player player, int preferredSeat) {
        ConfigManager cfg = cfg();
        if (closed) return;
        if (manager.getTableOf(player) != null) {
            player.sendMessage(cfg.getPrefixed("already-at-table"));
            return;
        }
        if (!inRange(player)) {
            player.sendMessage(cfg.getPrefixed("too-far-from-table"));
            return;
        }
        if (!economy().isAvailable()) {
            player.sendMessage(cfg.getPrefixed("economy-unavailable"));
            return;
        }
        int seat = seatFor(player, preferredSeat);
        if (seat < 0) {
            player.sendMessage(cfg.getPrefixed("table-full"));
            return;
        }
        long min = getMinBuyIn();
        long max = getMaxBuyIn();
        long balance = economy().getBalance(player.getUniqueId());
        if (balance < min) {
            player.sendMessage(cfg.formatPrefixed("insufficient-funds",
                "amount", money(min), "balance", money(balance)));
            return;
        }
        reservedSeats.put(seat, player.getUniqueId());
        reservedUntil.put(seat, System.currentTimeMillis() + RESERVE_MILLIS);
        plugin.getBuyInMenu().open(player, this, seat, min, max, Math.min(max, balance));
    }

    /** Take a seat with a buy-in; called by the buy-in menu / command once the amount is chosen. */
    public boolean sit(Player player, int seat, long buyIn) {
        ConfigManager cfg = cfg();
        UUID uuid = player.getUniqueId();
        if (closed) return false;
        if (manager.getTableOf(player) != null) {
            player.sendMessage(cfg.getPrefixed("already-at-table"));
            return false;
        }
        if (seat < 0 || seat >= game.getMaxSeats() || !isSeatFreeFor(seat, uuid)) {
            seat = seatFor(player, seat);
            if (seat < 0) {
                player.sendMessage(cfg.getPrefixed("table-full"));
                return false;
            }
        }
        if (!inRange(player)) {
            player.sendMessage(cfg.getPrefixed("too-far-from-table"));
            return false;
        }
        long min = getMinBuyIn();
        long max = getMaxBuyIn();
        if (buyIn < min || buyIn > max) {
            player.sendMessage(cfg.formatPrefixed("buy-in-out-of-range", "min", money(min), "max", money(max)));
            return false;
        }
        if (!economy().isAvailable()) {
            player.sendMessage(cfg.getPrefixed("economy-unavailable"));
            return false;
        }
        if (!economy().hasEnough(uuid, buyIn) || !economy().subtract(uuid, buyIn)) {
            player.sendMessage(cfg.formatPrefixed("insufficient-funds",
                "amount", money(buyIn), "balance", money(economy().getBalance(uuid))));
            return false;
        }
        try {
            game.seatPlayer(seat, uuid, buyIn);
        } catch (RuntimeException e) {
            refund(uuid, buyIn, "buy-in for a seat that was just taken");
            player.sendMessage(cfg.getPrefixed("table-full"));
            return false;
        }
        reservedSeats.remove(seat);
        reservedUntil.remove(seat);
        names.put(uuid, player.getName());
        missedTurns.remove(uuid);

        // Seat first, register after: the teleport listener stands up seated players who teleport away
        seating.sit(player, seat);
        manager.setPlayerTable(player, this);
        plugin.getCardResourcePack().offer(player);

        player.sendMessage(cfg.formatPrefixed("seated", "seat", seat + 1, "amount", money(buyIn)));
        broadcast(cfg.formatPrefixed("player-joined", "player", player.getName(), "amount", money(buyIn)), player);
        refreshSeat(seat);
        refreshPot();
        if (game.isHandInProgress()) {
            player.sendMessage(cfg.getPrefixed("wait-next-hand"));
        } else {
            scheduleNextHand();
        }
        return true;
    }

    /** Add chips from Vault; applied now between hands, or when the current hand ends. */
    public boolean topUp(Player player, long amount) {
        ConfigManager cfg = cfg();
        int seat = seatOf(player);
        if (seat < 0) {
            player.sendMessage(cfg.getPrefixed("not-at-table"));
            return false;
        }
        long room = getMaxTopUp(player);
        if (room <= 0) {
            player.sendMessage(cfg.getPrefixed("topup-none-allowed"));
            return false;
        }
        if (amount <= 0 || amount > room) {
            player.sendMessage(cfg.formatPrefixed("buy-in-out-of-range", "min", money(1), "max", money(room)));
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (!economy().isAvailable()) {
            player.sendMessage(cfg.getPrefixed("economy-unavailable"));
            return false;
        }
        if (!economy().hasEnough(uuid, amount) || !economy().subtract(uuid, amount)) {
            player.sendMessage(cfg.formatPrefixed("insufficient-funds",
                "amount", money(amount), "balance", money(economy().getBalance(uuid))));
            return false;
        }
        if (game.addChips(seat, amount)) {
            player.sendMessage(cfg.formatPrefixed("topup-done", "amount", money(amount), "stack", money(game.getStack(seat))));
            refreshSeat(seat);
            scheduleNextHand();
        } else {
            player.sendMessage(cfg.formatPrefixed("topup-queued", "amount", money(amount)));
        }
        return true;
    }

    /** Most a seated player may add right now without going over the maximum buy-in. */
    public long getMaxTopUp(Player player) {
        int seat = seatOf(player);
        if (seat < 0) return 0;
        long have = game.getStack(seat) + game.getPendingTopUp(seat) + (game.isLive(seat) ? game.getContributed(seat) : 0);
        return Math.max(0, getMaxBuyIn() - have);
    }

    /**
     * Stand up and cash out. Mid-hand this folds first. Safe to call more than once and for players who
     * aren't seated (it then just drops a pending seat reservation).
     */
    public void leave(Player player) {
        leave(player.getUniqueId(), player, "player-left");
    }

    private void leave(UUID uuid, Player player, String broadcastKey) {
        ConfigManager cfg = cfg();
        reservedSeats.values().removeIf(uuid::equals);
        pendingLeaves.remove(uuid);
        missedTurns.remove(uuid);
        int seat = game.seatOf(uuid);
        if (seat < 0) {
            if (player != null && manager.getTableOf(player) == this) manager.setPlayerTable(player, null);
            return;
        }

        // Cancel before folding them: the fold can start the next player's timer
        if (game.getActor() == seat) cancelTurnTimer();
        bustedHands.remove(uuid);
        long cashOut = enter(() -> game.removePlayer(seat));
        if (cashOut > 0) {
            payOut(uuid, cashOut, player, "left-cashout");
        } else if (player != null) {
            player.sendMessage(cfg.getPrefixed("left-table"));
        }
        if (player != null) {
            seating.stand(player);
            if (manager.getTableOf(player) == this) manager.setPlayerTable(player, null);
        }
        lastAction.remove(seat);
        view.clearSeat(seat);
        refreshSeat(seat);
        refreshPot();
        String name = names.getOrDefault(uuid, player != null ? player.getName() : "?");
        broadcast(cfg.formatPrefixed(broadcastKey, "player", name), null);
        if (!game.isHandInProgress() && game.fundedCount() < 2) cancelNextHand();
    }

    /**
     * Sneaking off the chair: leave at once, unless that would fold a live hand, in which case the first
     * sneak only warns and a second one within three seconds confirms.
     */
    public boolean confirmSneakLeave(Player player) {
        int seat = seatOf(player);
        if (seat < 0 || !game.isLive(seat)) return true;
        long now = System.currentTimeMillis();
        Long first = pendingLeaves.get(player.getUniqueId());
        if (first != null && now - first <= SNEAK_CONFIRM_MILLIS) {
            pendingLeaves.remove(player.getUniqueId());
            return true;
        }
        pendingLeaves.put(player.getUniqueId(), now);
        player.sendMessage(cfg().getPrefixed("seat-leave-confirm-fold"));
        seating.reseat(player);
        return false;
    }

    /** A seated player right-clicked the felt: actions on their turn, otherwise the top-up menu. */
    public void onTableClick(Player player) {
        int seat = seatOf(player);
        if (seat < 0) return;
        if (game.getActor() == seat) {
            plugin.getActionMenu().open(player, this);
        } else {
            long room = getMaxTopUp(player);
            if (room > 0) {
                plugin.getBuyInMenu().openTopUp(player, this, room);
            } else {
                player.sendMessage(cfg().getPrefixed("topup-none-allowed"));
            }
        }
    }

    // =====================================================================================
    // Actions (commands, menus, chat buttons)
    // =====================================================================================

    public boolean fold(Player player) { return act(player, ActionType.FOLD, 0); }
    public boolean check(Player player) { return act(player, ActionType.CHECK, 0); }
    public boolean call(Player player) { return act(player, ActionType.CALL, 0); }
    /** Bet {@code amount} in total on this street. */
    public boolean bet(Player player, long amount) { return act(player, ActionType.BET, amount); }
    /** Raise to {@code raiseTo} in total on this street. */
    public boolean raise(Player player, long raiseTo) { return act(player, ActionType.RAISE, raiseTo); }
    public boolean allIn(Player player) { return act(player, ActionType.ALL_IN, 0); }

    /** The player's options if it is their turn, otherwise null. */
    public ActionOptions getOptions(Player player) {
        int seat = seatOf(player);
        return seat < 0 ? null : game.getOptions(seat);
    }

    private boolean act(Player player, ActionType type, long amount) {
        ConfigManager cfg = cfg();
        int seat = seatOf(player);
        if (seat < 0) {
            player.sendMessage(cfg.getPrefixed("not-at-table"));
            return false;
        }
        if (!game.isHandInProgress()) {
            player.sendMessage(cfg.getPrefixed("no-hand-in-progress"));
            return false;
        }
        ActionOptions options = game.getOptions(seat);
        try {
            cancelTurnTimer();
            missedTurns.remove(player.getUniqueId());
            enter(() -> {
                game.act(seat, type, amount);
                return null;
            });
            return true;
        } catch (IllegalActionException e) {
            player.sendMessage(actionError(e.getReason(), options));
            if (game.getActor() == seat) startTurnTimer(seat); // still their turn
            return false;
        }
    }

    private String actionError(IllegalActionException.Reason reason, ActionOptions o) {
        ConfigManager cfg = cfg();
        return switch (reason) {
            case NOT_YOUR_TURN -> cfg.getPrefixed("not-your-turn");
            case NO_HAND -> cfg.getPrefixed("no-hand-in-progress");
            default -> cfg.formatPrefixed("action-error-" + reason.key(),
                "min", o == null ? "" : money(o.minRaiseTo()),
                "max", o == null ? "" : money(o.maxRaiseTo()),
                "to_call", o == null ? "" : money(o.toCall()));
        };
    }

    // =====================================================================================
    // Hand lifecycle
    // =====================================================================================

    private void scheduleNextHand() {
        scheduleNextHand(20L * cfg().getNextHandDelaySeconds());
    }

    private void scheduleNextHand(long delayTicks) {
        if (closed || nextHandTask != null || game.isHandInProgress() || !game.canStartHand()) return;
        nextHandTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            nextHandTask = null;
            startHand();
        }, Math.max(1L, delayTicks));
    }

    private void cancelNextHand() {
        if (nextHandTask != null) {
            nextHandTask.cancel();
            nextHandTask = null;
        }
    }

    private void startHand() {
        if (closed || game.isHandInProgress()) return;
        dropOfflinePlayers();
        ConfigManager cfg = cfg();
        game.setBlinds(settings.getSmallBlind(cfg), settings.getBigBlind(cfg));
        if (!game.canStartHand()) {
            broadcast(cfg.getPrefixed("waiting-for-players"), null);
            return;
        }
        try {
            enter(() -> {
                game.startHand();
                return null;
            });
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Poker table #" + id + ": could not start a hand: " + e);
        }
    }

    /**
     * Before a deal: cash out anyone who went offline without the quit listener catching it, and stand
     * up players who have sat out {@link #MAX_BUSTED_HANDS} hands with no chips.
     */
    private void dropOfflinePlayers() {
        for (int s = 0; s < game.getMaxSeats(); s++) {
            UUID uuid = game.getPlayer(s);
            if (uuid == null) continue;
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) {
                leave(uuid, null, "player-left");
            } else if (game.getStack(s) > 0) {
                bustedHands.remove(uuid);
            } else if (bustedHands.merge(uuid, 1, Integer::sum) > MAX_BUSTED_HANDS) {
                leave(uuid, p, "player-left-busted");
            }
        }
    }

    /** Run a call into the game with a fresh visual cue. */
    private <T> T enter(java.util.function.Supplier<T> call) {
        cue = 0;
        streetsThisCall = 0;
        return call.get();
    }

    /** Run now, or after the current cue if earlier events of this call are still being shown. */
    private void at(Runnable r) {
        if (cue <= 0) {
            r.run();
            return;
        }
        BukkitTask[] self = new BukkitTask[1];
        self[0] = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            scheduled.remove(self[0]);
            if (!closed) r.run();
        }, cue);
        scheduled.add(self[0]);
    }

    @Override
    public void onHandStarted(int handNumber, int button, int smallBlindSeat, int bigBlindSeat) {
        view.clearHand();
        view.setButton(button);
        lastAction.clear();
        pendingLeaves.clear();
        broadcast(cfg().formatPrefixed("hand-started", "hand", handNumber, "player", nameAt(button)), null);
    }

    @Override
    public void onBlindPosted(int seat, long amount, boolean bigBlind, boolean allIn) {
        lastAction.put(seat, cfg().formatMessage(bigBlind ? "display-action-bb" : "display-action-sb", "amount", money(amount)));
        refreshSeat(seat);
        Player p = playerAt(seat);
        if (p != null) {
            p.sendMessage(cfg().formatPrefixed(bigBlind ? "you-post-bb" : "you-post-sb", "amount", money(amount)));
        }
    }

    @Override
    public void onHoleCards(int seat, List<Card> cards) {
        Player p = playerAt(seat);
        view.dealHoleCards(seat, p, cards);
        if (p != null) {
            p.sendMessage(cfg().formatPrefixed("your-cards", "cards", cardsText(cards)));
            playSound(p, cfg().getCardDealSound(), "card-deal");
        }
    }

    @Override
    public void onTurn(int seat, ActionOptions options) {
        view.highlightTurn(seat);
        refreshPot();
        Player p = playerAt(seat);
        if (p == null) return; // dropped at the next check; the timer folds them meanwhile
        p.sendMessage(cfg().formatPrefixed("turn-prompt",
            "to_call", money(options.toCall()), "pot", money(options.potTotal()), "stack", money(options.stack())));
        if (cfg().sendChatButtons()) {
            plugin.getActionMenu().sendChatButtons(p, this);
        }
        playSound(p, cfg().getTurnSound(), "your-turn");
        startTurnTimer(seat);
    }

    @Override
    public void onAction(int seat, ActionType type, long streetBet, boolean allIn, boolean left) {
        ConfigManager cfg = cfg();
        String name = nameAt(seat);
        String key = type == ActionType.ALL_IN || (allIn && type != ActionType.FOLD) ? "allin" : type.name().toLowerCase();
        lastAction.put(seat, cfg.formatMessage("display-action-" + key, "amount", money(streetBet)));
        refreshSeat(seat);
        refreshPot();
        if (type == ActionType.FOLD) {
            view.clearSeat(seat);
            refreshSeat(seat);
        }
        if (!left) {
            broadcast(cfg.formatPrefixed("action-" + key, "player", name, "amount", money(streetBet)), null);
            Player p = playerAt(seat);
            if (p != null) {
                p.sendMessage(cfg.formatPrefixed("you-" + key, "amount", money(streetBet)));
            }
        } else {
            broadcast(cfg.formatPrefixed("action-left-fold", "player", name), null);
        }
    }

    @Override
    public void onStreet(Street street, List<Card> board) {
        if (streetsThisCall++ > 0) cue += RUNOUT_TICKS;
        at(() -> {
            view.setBoard(board);
            for (int s = 0; s < game.getMaxSeats(); s++) {
                if (lastAction.remove(s) != null) refreshSeat(s);
            }
            broadcast(cfg().formatPrefixed("board-dealt",
                "street", cfg().getMessage("street-" + street.name().toLowerCase()), "cards", cardsText(board)), null);
        });
    }

    @Override
    public void onUncalledReturned(int seat, UUID player, long amount, boolean seated) {
        if (!seated) {
            // They already left; their bet was never called, so it's theirs
            payOut(player, amount, Bukkit.getPlayer(player), "uncalled-returned");
            return;
        }
        Player p = playerAt(seat);
        if (p != null) p.sendMessage(cfg().formatPrefixed("uncalled-returned", "amount", money(amount)));
        refreshSeat(seat);
    }

    @Override
    public void onShowdown(List<ShowdownHand> hands) {
        cancelTurnTimer();
        cue += REVEAL_TICKS;
        at(() -> {
            view.highlightTurn(-1);
            for (ShowdownHand h : hands) {
                view.revealHoleCards(h.seat(), h.holeCards());
                broadcast(cfg().formatPrefixed("showdown-hand", "player", names.getOrDefault(h.player(), "?"),
                    "cards", cardsText(h.holeCards()), "hand", describe(h.value())), null);
            }
        });
    }

    @Override
    public void onPotAwarded(int potIndex, long amount, Map<Integer, Long> shares, HandValue winningHand) {
        if (winningHand != null && potIndex == 0) cue += AWARD_TICKS;
        Map<Integer, UUID> winners = new HashMap<>();
        shares.keySet().forEach(s -> winners.put(s, game.getPlayer(s)));
        at(() -> {
            ConfigManager cfg = cfg();
            String hand = winningHand == null ? "" : describe(winningHand);
            for (Map.Entry<Integer, Long> e : shares.entrySet()) {
                UUID uuid = winners.get(e.getKey());
                String name = names.getOrDefault(uuid, "?");
                String won = money(e.getValue());
                if (winningHand == null) {
                    broadcast(cfg.formatPrefixed("pot-won-uncontested", "player", name, "amount", won), null);
                } else {
                    broadcast(cfg.formatPrefixed(potIndex == 0 ? "pot-won" : "side-pot-won",
                        "player", name, "amount", won, "hand", hand), null);
                }
                Player p = uuid == null ? null : Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.sendMessage(cfg.formatPrefixed(winningHand == null ? "you-win-uncontested" : "you-win",
                        "amount", won, "hand", hand));
                    playSound(p, cfg.getWinSound(), "win");
                }
                refreshSeat(e.getKey());
            }
        });
    }

    @Override
    public void onHandEnded(HandSummary summary) {
        cancelTurnTimer();
        for (SeatResult r : summary.results()) {
            try {
                plugin.getStatsManager().recordHand(r.player(), r.net(), r.isWinner(),
                    r.wentToShowdown() && r.isWinner(), r.won());
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Could not record poker stats: " + e);
            }
        }
        long pause = cue + 20L * cfg().getNextHandDelaySeconds()
            + (summary.showdown() ? 20L * cfg().getShowdownDisplaySeconds() : 0L);
        at(() -> {
            view.highlightTurn(-1);
            refreshPot();
            for (int s = 0; s < game.getMaxSeats(); s++) {
                refreshSeat(s);
                Player p = playerAt(s);
                if (p != null && game.getStack(s) == 0) {
                    p.sendMessage(cfg().formatPrefixed("busted", "max", money(getMaxBuyIn())));
                }
            }
        });
        // at() above may still be queued; the next hand waits for it plus the configured pause
        cancelNextHand();
        if (!closed) {
            nextHandTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                nextHandTask = null;
                startHand();
            }, Math.max(1L, pause));
        }
    }

    @Override
    public void onHandVoided(VoidResult result) {
        cancelTurnTimer();
        result.refundedToStacks().forEach((seat, amount) -> {
            Player p = playerAt(seat);
            if (p != null) p.sendMessage(cfg().formatPrefixed("hand-voided", "amount", money(amount)));
        });
        result.refundedToLeavers().forEach((uuid, amount) -> payOut(uuid, amount, Bukkit.getPlayer(uuid), "hand-voided"));
    }

    // =====================================================================================
    // Turn timer
    // =====================================================================================

    private void startTurnTimer(int seat) {
        cancelTurnTimer();
        int timeout = cfg().getTurnTimeoutSeconds();
        if (timeout <= 0) return;
        int hand = game.getHandNumber();
        int[] left = {timeout};
        turnTimer = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!game.isHandInProgress() || game.getHandNumber() != hand || game.getActor() != seat) {
                cancelTurnTimer();
                return;
            }
            Player p = playerAt(seat);
            if (left[0] <= 0) {
                cancelTurnTimer();
                onTimeout(seat, p);
                return;
            }
            if (p != null && (left[0] <= 10 || left[0] % 5 == 0)) {
                p.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    new TextComponent(cfg().formatMessage("turn-timer", "seconds", left[0])));
            }
            left[0]--;
        }, 0L, 20L);
    }

    private void onTimeout(int seat, Player player) {
        UUID uuid = game.getPlayer(seat);
        broadcast(cfg().formatPrefixed("turn-timeout", "player", nameAt(seat)), null);
        enter(() -> game.timeout());
        if (uuid == null) return;
        int missed = missedTurns.merge(uuid, 1, Integer::sum);
        int max = cfg().getMaxMissedTurns();
        if (player == null || (max > 0 && missed >= max)) {
            if (player != null) player.sendMessage(cfg().getPrefixed("auto-left-missed-turns"));
            leave(uuid, player, "player-left-inactive");
        }
    }

    private void cancelTurnTimer() {
        if (turnTimer != null) {
            turnTimer.cancel();
            turnTimer = null;
        }
    }

    // =====================================================================================
    // Shutdown / removal
    // =====================================================================================

    /**
     * Void any hand in progress (everyone gets back what they put in) and cash every player out.
     * Used when the table is removed; {@link #cleanup()} does the same on shutdown.
     */
    public void removeAllPlayers() {
        cancelTurnTimer();
        cancelNextHand();
        if (game.isHandInProgress()) {
            enter(game::voidHand);
        }
        for (int s = 0; s < game.getMaxSeats(); s++) {
            UUID uuid = game.getPlayer(s);
            if (uuid == null) continue;
            Player p = Bukkit.getPlayer(uuid);
            long cashOut = game.removePlayer(s);
            if (cashOut > 0) payOut(uuid, cashOut, p, "table-closed-cashout");
            if (p != null) {
                seating.stand(p);
                if (manager.getTableOf(p) == this) manager.setPlayerTable(p, null);
            }
            view.clearSeat(s);
        }
        reservedSeats.clear();
        reservedUntil.clear();
        pendingLeaves.clear();
        missedTurns.clear();
        bustedHands.clear();
        lastAction.clear();
    }

    /** Refund and cash out everyone, then remove every entity this table spawned. */
    public void cleanup() {
        if (closed) return;
        removeAllPlayers();
        closed = true;
        scheduled.forEach(BukkitTask::cancel);
        scheduled.clear();
        view.destroy();
        seating.clear();
    }

    /** Blinds and buy-in limits changed in place; the blinds apply from the next hand. */
    public void onSettingsChanged() {
        refreshPot();
    }

    // =====================================================================================
    // Money
    // =====================================================================================

    private void payOut(UUID uuid, long amount, Player player, String messageKey) {
        if (amount <= 0) return;
        if (economy().add(uuid, amount)) {
            if (player != null) player.sendMessage(cfg().formatPrefixed(messageKey, "amount", money(amount)));
        } else {
            plugin.getLogger().severe("Poker table #" + id + ": FAILED to pay " + amount + " to " + uuid
                + " (" + names.getOrDefault(uuid, "?") + "). Pay it back by hand.");
            if (player != null) player.sendMessage(cfg().formatPrefixed("error-payout", "amount", money(amount)));
        }
    }

    private void refund(UUID uuid, long amount, String why) {
        if (!economy().add(uuid, amount)) {
            plugin.getLogger().severe("Poker table #" + id + ": FAILED to refund " + amount + " to " + uuid + " (" + why + ")");
        }
    }

    // =====================================================================================
    // Display helpers
    // =====================================================================================

    private void refreshSeat(int seat) {
        UUID uuid = game.getPlayer(seat);
        if (uuid == null) {
            view.setSeatInfo(seat, "");
            return;
        }
        StringBuilder text = new StringBuilder(names.getOrDefault(uuid, "?"))
            .append('\n').append(money(game.getStack(seat)));
        String action = lastAction.get(seat);
        if (action != null && !action.isEmpty()) text.append('\n').append(action);
        view.setSeatInfo(seat, text.toString());
    }

    private void refreshPot() {
        long pot = game.getPot();
        view.setPotInfo(pot > 0 ? cfg().formatMessage("display-pot", "pot", money(pot)) : "");
    }

    private String cardsText(List<Card> cards) {
        return cards.stream().map(Card::display).collect(Collectors.joining(" "));
    }

    private String describe(HandValue value) {
        String key = "hand." + value.getRank().name();
        return cfg().hasMessage(key) ? cfg().getMessage(key) : value.describe();
    }

    private String money(long amount) {
        return cfg().formatCurrency(String.format("%,d", amount));
    }

    private void playSound(Player p, Sound sound, String name) {
        if (sound != null && cfg().areSoundsEnabled()) {
            p.playSound(p.getLocation(), sound, cfg().getSoundVolume(name), cfg().getSoundPitch(name));
        }
    }

    /** Send to everyone seated here (and anyone picking a buy-in), except {@code except}. */
    private void broadcast(String message, Player except) {
        Set<UUID> to = new HashSet<>(reservedSeats.values());
        for (int s = 0; s < game.getMaxSeats(); s++) {
            if (game.getPlayer(s) != null) to.add(game.getPlayer(s));
        }
        for (UUID uuid : to) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p != except) p.sendMessage(message);
        }
    }

    // =====================================================================================
    // Seats
    // =====================================================================================

    private int seatFor(Player player, int preferred) {
        UUID uuid = player.getUniqueId();
        if (preferred >= 0 && preferred < game.getMaxSeats() && isSeatFreeFor(preferred, uuid)) return preferred;
        for (int s = 0; s < game.getMaxSeats(); s++) {
            if (isSeatFreeFor(s, uuid)) return s;
        }
        return -1;
    }

    private boolean isSeatFreeFor(int seat, UUID uuid) {
        if (!game.isSeatAvailable(seat)) return false;
        UUID holder = reservedSeats.get(seat);
        if (holder == null || holder.equals(uuid)) return true;
        if (reservedUntil.getOrDefault(seat, 0L) < System.currentTimeMillis()) {
            reservedSeats.remove(seat);
            reservedUntil.remove(seat);
            return true;
        }
        return false;
    }

    private boolean inRange(Player player) {
        Location center = layout.getCenter();
        return center.getWorld() != null && center.getWorld().equals(player.getWorld())
            && player.getLocation().distance(center) <= settings.getMaxJoinDistance(cfg());
    }

    private Player playerAt(int seat) {
        UUID uuid = game.getPlayer(seat);
        return uuid == null ? null : Bukkit.getPlayer(uuid);
    }

    private String nameAt(int seat) {
        UUID uuid = game.getPlayer(seat);
        if (uuid == null) return "?";
        String name = names.get(uuid);
        if (name != null) return name;
        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        return off.getName() != null ? off.getName() : "?";
    }

    private ConfigManager cfg() {
        return plugin.getConfigManager();
    }

    private EconomyProvider economy() {
        return plugin.getEconomyProvider();
    }

    // =====================================================================================
    // Getters
    // =====================================================================================

    public int getId() { return id; }
    public TableLayout getLayout() { return layout; }
    public TableSettings getSettings() { return settings; }
    public TableView getView() { return view; }
    public boolean ownsEntity(Entity entity) { return seating.ownsEntity(entity) || view.ownsEntity(entity); }
    public boolean isHandInProgress() { return game.isHandInProgress(); }
    public long getMinBuyIn() { return settings.getMinBuyIn(cfg()); }
    public long getMaxBuyIn() { return settings.getMaxBuyIn(cfg()); }
    public long getSmallBlind() { return game.isHandInProgress() ? game.getSmallBlind() : settings.getSmallBlind(cfg()); }
    public long getBigBlind() { return game.isHandInProgress() ? game.getBigBlind() : settings.getBigBlind(cfg()); }
    public long getPot() { return game.getPot(); }
    public List<Card> getBoard() { return game.getBoard(); }
    public int getSeatCount() { return game.getMaxSeats(); }

    public boolean isSeated(Player player) { return seatOf(player) >= 0; }
    public int getSeat(Player player) { return seatOf(player); }
    public long getStack(Player player) {
        int seat = seatOf(player);
        return seat < 0 ? 0 : game.getStack(seat);
    }
    public boolean isPlayerTurn(Player player) {
        int seat = seatOf(player);
        return seat >= 0 && game.getActor() == seat;
    }

    /** Seated players plus seats being bought into. */
    public boolean hasPlayers() {
        long now = System.currentTimeMillis();
        reservedSeats.keySet().removeIf(seat -> reservedUntil.getOrDefault(seat, 0L) < now);
        reservedUntil.keySet().retainAll(reservedSeats.keySet());
        if (!reservedSeats.isEmpty()) return true;
        for (int s = 0; s < game.getMaxSeats(); s++) {
            if (game.getPlayer(s) != null) return true;
        }
        return false;
    }

    public int getPlayerCount() {
        int n = 0;
        for (int s = 0; s < game.getMaxSeats(); s++) {
            if (game.getPlayer(s) != null) n++;
        }
        return n;
    }

    /** Online players seated here, in seat order. */
    public List<Player> getPlayers() {
        List<Player> list = new ArrayList<>();
        for (int s = 0; s < game.getMaxSeats(); s++) {
            Player p = playerAt(s);
            if (p != null) list.add(p);
        }
        return list;
    }

    private int seatOf(Player player) {
        return game.seatOf(player.getUniqueId());
    }
}
