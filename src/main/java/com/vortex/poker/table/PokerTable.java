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
import com.vortex.poker.game.ReadyCheck;
import com.vortex.poker.game.SeatResult;
import com.vortex.poker.game.ShowdownHand;
import com.vortex.poker.game.Street;
import com.vortex.poker.game.VoidResult;
import com.vortex.poker.gui.PlayerUI;
import com.vortex.poker.model.Card;
import com.vortex.poker.model.Deck;
import com.vortex.poker.model.HandValue;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
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
 * One poker table in the world: seats, buy-ins and cash-outs, the turn timer and the "play again"
 * check between hands. The rules live in {@link HoldemGame}; {@link TablePresenter} puts the hand on
 * the felt; this class does chat, money and timers, and turns commands and clicks into game actions.
 *
 * <p>Between hands: the result stays on the table for a moment, the table is cleared, and every player
 * who was dealt in must confirm ({@link #ready(Player)}) within the ready timeout or is stood up and
 * cashed out. The next hand is dealt once nobody is left to confirm and two players have chips.
 * Players who sit down count as confirmed.
 *
 * <p>Money: a buy-in moves from Vault onto the table stack when a player sits; the stack (plus any
 * top-up not yet applied) goes back to Vault when they leave. Everything runs on the main thread.
 */
public class PokerTable implements HoldemListener {

    /** Pause after a hand everyone folded before the table clears. */
    private static final long FOLD_HOLD_TICKS = 40L;
    /** Deal this long after the last player confirms. */
    private static final long DEAL_AFTER_READY_TICKS = 20L;
    /** How long a seat stays reserved while its player picks a buy-in. */
    private static final long RESERVE_MILLIS = 60_000L;
    private static final long SNEAK_CONFIRM_MILLIS = 3_000L;
    private static final int READY_REMINDER_SECONDS = 10;

    private final PokerPlugin plugin;
    private final TableManager manager;
    private final int id;
    private final TableLayout layout;
    private final TableSettings settings;
    private final TableView view;
    private final Seating seating;
    private final HoldemGame game;
    private final TablePresenter presenter;
    private final ReadyCheck readyCheck = new ReadyCheck();

    private final Map<Integer, UUID> reservedSeats = new HashMap<>();
    private final Map<Integer, Long> reservedUntil = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<Integer, String> lastAction = new HashMap<>();
    private final Map<UUID, Integer> missedTurns = new HashMap<>();
    private final Map<UUID, Long> pendingLeaves = new HashMap<>();
    private final Set<UUID> remindedReady = new HashSet<>();
    private final Set<BukkitTask> scheduled = new HashSet<>();

    private BukkitTask turnTimer;
    private BukkitTask nextHandTask;
    private BukkitTask readyTicker;
    private boolean closed;

    public PokerTable(PokerPlugin plugin, TableManager manager, int id, TableLayout layout, TableSettings settings) {
        this.plugin = plugin;
        this.manager = manager;
        this.id = id;
        this.layout = layout;
        this.settings = settings;
        ConfigManager cfg = plugin.getConfigManager();
        this.game = new HoldemGame(layout.getSeatCount(), settings.getSmallBlind(cfg), settings.getBigBlind(cfg), Deck::new);
        this.view = new WorldTableView(plugin, layout, id);
        this.seating = new Seating(plugin, layout, id);
        this.presenter = new TablePresenter(view, this::later, this::playerAt, this::timing);
        presenter.setOnCleared(this::onTableCleared);
        // The presenter goes first so that chat below can queue behind the cards it just paced
        this.game.setListener(HoldemListener.all(presenter, this));
        readyCheck.start(List.of(), List.of(), now(), 0); // an empty table is waiting for players
        refreshPot();
    }

    // =====================================================================================
    // Joining, buying in, topping up, leaving
    // =====================================================================================

    /** A player clicked a chair (seat) or used /poker join: pick a seat and ask for a buy-in. */
    public void join(Player player, int preferredSeat) {
        ConfigManager cfg = cfg();
        if (closed) return;
        if (manager.getTableOf(player) != null) {
            tell(player, cfg.getPrefixed("already-at-table"));
            return;
        }
        if (!inRange(player)) {
            tell(player, cfg.getPrefixed("too-far-from-table"));
            return;
        }
        if (!economy().isAvailable()) {
            tell(player, cfg.getPrefixed("economy-unavailable"));
            return;
        }
        int seat = seatFor(player, preferredSeat);
        if (seat < 0) {
            tell(player, cfg.getPrefixed("table-full"));
            return;
        }
        long min = getMinBuyIn();
        long max = getMaxBuyIn();
        long balance = economy().getBalance(player.getUniqueId());
        if (balance < min) {
            tell(player, cfg.formatPrefixed("insufficient-funds", "amount", money(min), "balance", money(balance)));
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
            tell(player, cfg.getPrefixed("already-at-table"));
            return false;
        }
        if (seat < 0 || seat >= game.getMaxSeats() || !isSeatFreeFor(seat, uuid)) {
            seat = seatFor(player, seat);
            if (seat < 0) {
                tell(player, cfg.getPrefixed("table-full"));
                return false;
            }
        }
        if (!inRange(player)) {
            tell(player, cfg.getPrefixed("too-far-from-table"));
            return false;
        }
        long min = getMinBuyIn();
        long max = getMaxBuyIn();
        if (buyIn < min || buyIn > max) {
            tell(player, cfg.formatPrefixed("buy-in-out-of-range", "min", money(min), "max", money(max)));
            return false;
        }
        if (!economy().isAvailable()) {
            tell(player, cfg.getPrefixed("economy-unavailable"));
            return false;
        }
        if (!economy().hasEnough(uuid, buyIn) || !economy().subtract(uuid, buyIn)) {
            tell(player, cfg.formatPrefixed("insufficient-funds",
                "amount", money(buyIn), "balance", money(economy().getBalance(uuid))));
            return false;
        }
        try {
            game.seatPlayer(seat, uuid, buyIn);
        } catch (RuntimeException e) {
            refund(uuid, buyIn, "buy-in for a seat that was just taken");
            tell(player, cfg.getPrefixed("table-full"));
            return false;
        }
        reservedSeats.remove(seat);
        reservedUntil.remove(seat);
        names.put(uuid, player.getName());
        missedTurns.remove(uuid);
        readyCheck.join(uuid); // choosing a seat is choosing to play

        // Seat first, register after: the teleport listener stands up seated players who teleport away
        seating.sit(player, seat);
        manager.setPlayerTable(player, this);
        plugin.getCardResourcePack().offer(player);

        tell(player, cfg.formatPrefixed("seated", "seat", seat + 1, "amount", money(buyIn)));
        broadcast(cfg.formatPrefixed("player-joined", "player", player.getName(), "amount", money(buyIn)), player);
        ui().onSeated(player, this);
        refreshSeat(seat);
        refreshPot();
        if (game.isHandInProgress()) {
            tell(player, cfg.getPrefixed("wait-next-hand"));
        } else {
            tryStartHand();
        }
        return true;
    }

    /** Add chips from Vault; applied now between hands, or when the current hand ends. */
    public boolean topUp(Player player, long amount) {
        ConfigManager cfg = cfg();
        int seat = seatOf(player);
        if (seat < 0) {
            tell(player, cfg.getPrefixed("not-at-table"));
            return false;
        }
        long room = getMaxTopUp(player);
        if (room <= 0) {
            tell(player, cfg.getPrefixed("topup-none-allowed"));
            return false;
        }
        if (amount <= 0 || amount > room) {
            tell(player, cfg.formatPrefixed("buy-in-out-of-range", "min", money(1), "max", money(room)));
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (!economy().isAvailable()) {
            tell(player, cfg.getPrefixed("economy-unavailable"));
            return false;
        }
        if (!economy().hasEnough(uuid, amount) || !economy().subtract(uuid, amount)) {
            tell(player, cfg.formatPrefixed("insufficient-funds",
                "amount", money(amount), "balance", money(economy().getBalance(uuid))));
            return false;
        }
        if (game.addChips(seat, amount)) {
            tell(player, cfg.formatPrefixed("topup-done", "amount", money(amount), "stack", money(game.getStack(seat))));
            refreshSeat(seat);
            tryStartHand();
        } else {
            tell(player, cfg.formatPrefixed("topup-queued", "amount", money(amount)));
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
        leave(player.getUniqueId(), player, "player-left", "left-cashout");
    }

    private void leave(UUID uuid, Player player, String broadcastKey, String cashOutKey) {
        ConfigManager cfg = cfg();
        reservedSeats.values().removeIf(uuid::equals);
        pendingLeaves.remove(uuid);
        missedTurns.remove(uuid);
        remindedReady.remove(uuid);
        readyCheck.remove(uuid);
        int seat = game.seatOf(uuid);
        if (seat < 0) {
            if (player != null && manager.getTableOf(player) == this) manager.setPlayerTable(player, null);
            return;
        }

        // Cancel before folding them: the fold can start the next player's timer
        if (game.getActor() == seat) cancelTurnTimer();
        long cashOut = enter(() -> game.removePlayer(seat));
        if (cashOut > 0) {
            payOut(uuid, cashOut, player, cashOutKey);
        } else if (player != null) {
            tell(player, cfg.getPrefixed("left-table"));
        }
        if (player != null) {
            seating.stand(player);
            if (manager.getTableOf(player) == this) manager.setPlayerTable(player, null);
        }
        lastAction.remove(seat);
        if (!game.isHandInProgress()) view.clearSeat(seat); // mid-hand the fold already took the cards
        refreshSeat(seat);
        refreshPot();
        String name = names.getOrDefault(uuid, player != null ? player.getName() : "?");
        broadcast(cfg.formatPrefixed(broadcastKey, "player", name), null);
        if (!game.canStartHand()) cancelNextHand();
        tryStartHand(); // they may have been the last one everyone was waiting for
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
        tell(player, cfg().getPrefixed("seat-leave-confirm-fold"));
        seating.reseat(player);
        return false;
    }

    /** A seated player right-clicked the felt: actions on their turn, "play again" between hands, else top-up. */
    public void onTableClick(Player player) {
        int seat = seatOf(player);
        if (seat < 0) return;
        if (game.getActor() == seat) {
            plugin.getActionMenu().open(player, this);
        } else if (readyCheck.isPending(player.getUniqueId()) && game.getStack(seat) > 0) {
            ready(player);
        } else {
            long room = getMaxTopUp(player);
            if (room > 0) {
                plugin.getBuyInMenu().openTopUp(player, this, room);
            } else {
                tell(player, cfg().getPrefixed("topup-none-allowed"));
            }
        }
    }

    // =====================================================================================
    // Play again (ready check)
    // =====================================================================================

    /** "Play again": confirm for the next hand. Sends its own feedback. */
    public boolean ready(Player player) {
        ConfigManager cfg = cfg();
        int seat = seatOf(player);
        UUID uuid = player.getUniqueId();
        if (seat < 0) {
            tell(player, cfg.getPrefixed("not-at-table"));
            return false;
        }
        if (game.isHandInProgress() || !readyCheck.isActive()) {
            tell(player, cfg.getPrefixed("ready-no-phase"));
            return false;
        }
        if (readyCheck.isConfirmed(uuid)) {
            tell(player, cfg.getPrefixed("ready-already"));
            return false;
        }
        if (game.getStack(seat) + game.getPendingTopUp(seat) <= 0) {
            tell(player, cfg.formatPrefixed("ready-need-chips", "max", money(getMaxBuyIn())));
            return false;
        }
        if (readyCheck.confirm(uuid) != ReadyCheck.Result.CONFIRMED) {
            tell(player, cfg.getPrefixed("ready-no-phase"));
            return false;
        }
        remindedReady.remove(uuid);
        int ready = readyCheck.confirmedCount();
        int total = readyCheck.total();
        tell(player, cfg.formatPrefixed("ready-confirmed", "ready", ready, "total", total));
        String waitingOn = readyCheck.getPending().stream().map(u -> names.getOrDefault(u, "?"))
            .collect(Collectors.joining(", "));
        for (UUID other : readyCheck.getConfirmed()) {
            Player p = Bukkit.getPlayer(other);
            if (p != null && p != player && !waitingOn.isEmpty()) {
                tell(p, cfg.formatPrefixed("ready-waiting", "ready", ready, "total", total, "names", waitingOn));
            }
        }
        broadcast(cfg.formatPrefixed("player-ready", "player", player.getName()), player);
        tryStartHand();
        return true;
    }

    /** Same as {@link #ready(Player)}. */
    public boolean confirmReady(Player player) {
        return ready(player);
    }

    /** Between hands, waiting for players to confirm. */
    public boolean isReadyPhase() {
        return !game.isHandInProgress() && readyCheck.isActive() && !readyCheck.getPending().isEmpty();
    }

    public boolean isReady(Player player) {
        return readyCheck.isConfirmed(player.getUniqueId());
    }

    /** Whether this player still has to confirm "play again". */
    public boolean isAwaitingReady(Player player) {
        return readyCheck.isPending(player.getUniqueId());
    }

    public int getReadySecondsLeft(Player player) {
        return readyCheck.secondsLeft(player.getUniqueId(), now());
    }

    /** The finished hand has been shown and cleared off the felt: ask everyone who played to go again. */
    private void onTableCleared() {
        if (closed) return;
        lastAction.clear();
        for (int s = 0; s < game.getMaxSeats(); s++) {
            refreshSeat(s);
            Player p = playerAt(s);
            if (p != null && game.getStack(s) + game.getPendingTopUp(s) == 0) {
                tell(p, cfg().formatPrefixed("busted", "max", money(getMaxBuyIn())));
            }
        }
        refreshPot();
        beginReadyCheck();
    }

    private void beginReadyCheck() {
        // Only players dealt into the hand who are still in that seat confirm; anyone who (re)sat since is in
        int timeout = cfg().getReadyTimeoutSeconds();
        readyCheck.open(seatedIds(), now(), timeout * 1000L);
        remindedReady.clear();
        for (UUID uuid : readyCheck.getPending()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            tell(p, cfg().formatPrefixed("ready-prompt", "seconds", timeout));
            plugin.getActionMenu().sendReadyButton(p, this);
        }
        if (!readyCheck.getPending().isEmpty()) startReadyTicker();
        tryStartHand();
    }

    private void startReadyTicker() {
        stopReadyTicker();
        readyTicker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickReady, 20L, 20L);
    }

    private void stopReadyTicker() {
        if (readyTicker != null) {
            readyTicker.cancel();
            readyTicker = null;
        }
    }

    /** Once a second while anyone still has to confirm: countdown, reminder, and standing up latecomers. */
    private void tickReady() {
        if (closed || game.isHandInProgress() || !readyCheck.isActive()) {
            stopReadyTicker();
            return;
        }
        long now = now();
        for (UUID uuid : readyCheck.expired(now)) {
            Player p = Bukkit.getPlayer(uuid);
            leave(uuid, p, "player-left-ready-timeout", "ready-timeout-removed");
        }
        for (UUID uuid : readyCheck.getPending()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) {
                leave(uuid, null, "player-left-ready-timeout", "ready-timeout-removed");
                continue;
            }
            int left = readyCheck.secondsLeft(uuid, now);
            actionBar(p, cfg().formatMessage("ready-countdown", "seconds", left));
            if (left <= READY_REMINDER_SECONDS && remindedReady.add(uuid)) {
                tell(p, cfg().formatPrefixed("ready-reminder", "seconds", left));
            }
        }
        if (readyCheck.getPending().isEmpty()) stopReadyTicker();
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
            tell(player, cfg.getPrefixed("not-at-table"));
            return false;
        }
        if (!game.isHandInProgress()) {
            tell(player, cfg.getPrefixed("no-hand-in-progress"));
            return false;
        }
        ActionOptions options = game.getOptions(seat);
        // The turn timer keeps running: the next onTurn replaces it, and a refused action must not
        // buy the player a fresh countdown
        try {
            enter(() -> {
                game.act(seat, type, amount);
                return null;
            });
            missedTurns.remove(player.getUniqueId());
            return true;
        } catch (IllegalActionException e) {
            tell(player, actionError(e.getReason(), options));
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

    /** Deal soon if everyone has confirmed, two players have chips and the last hand is off the felt. */
    private void tryStartHand() {
        if (closed || nextHandTask != null || game.isHandInProgress() || presenter.isHandOnTable()
                || !readyCheck.allConfirmed() || !game.canStartHand()) {
            return;
        }
        long delay = cfg().getReadyTimeoutSeconds() <= 0
            ? 20L * cfg().getNextHandDelaySeconds() : DEAL_AFTER_READY_TICKS;
        nextHandTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            nextHandTask = null;
            startHand();
        }, delay);
    }

    private void cancelNextHand() {
        if (nextHandTask != null) {
            nextHandTask.cancel();
            nextHandTask = null;
        }
    }

    private void startHand() {
        if (closed || game.isHandInProgress() || !readyCheck.allConfirmed()) return;
        dropOfflinePlayers();
        ConfigManager cfg = cfg();
        game.setBlinds(settings.getSmallBlind(cfg), settings.getBigBlind(cfg));
        if (!game.canStartHand()) {
            broadcast(cfg.getPrefixed("waiting-for-players"), null);
            return;
        }
        readyCheck.stop();
        stopReadyTicker();
        try {
            enter(() -> {
                game.startHand();
                return null;
            });
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Poker table #" + id + ": could not start a hand: " + e);
            readyCheck.start(List.of(), seatedIds(), now(), 0);
        }
    }

    /** Anyone who went offline without the quit listener catching it is cashed out before the deal. */
    private void dropOfflinePlayers() {
        for (int s = 0; s < game.getMaxSeats(); s++) {
            UUID uuid = game.getPlayer(s);
            if (uuid != null && Bukkit.getPlayer(uuid) == null) {
                leave(uuid, null, "player-left", "left-cashout");
            }
        }
    }

    /** Run a call into the game with a fresh visual cue. */
    private <T> T enter(java.util.function.Supplier<T> call) {
        presenter.beginCall();
        return call.get();
    }

    /** Chat that belongs with the cards: shown when the presenter shows them. */
    private void at(Runnable r) {
        presenter.at(r);
    }

    @Override
    public void onHandStarted(int handNumber, int button, int smallBlindSeat, int bigBlindSeat) {
        lastAction.clear();
        pendingLeaves.clear();
        List<UUID> dealt = new ArrayList<>();
        for (int s = 0; s < game.getMaxSeats(); s++) {
            if (game.isLive(s)) dealt.add(game.getPlayer(s));
        }
        readyCheck.handStarted(dealt);
        broadcast(cfg().formatPrefixed("hand-started", "hand", handNumber, "player", nameAt(button)), null);
    }

    @Override
    public void onBlindPosted(int seat, long amount, boolean bigBlind, boolean allIn) {
        lastAction.put(seat, cfg().formatMessage(bigBlind ? "display-action-bb" : "display-action-sb", "amount", money(amount)));
        refreshSeat(seat);
        refreshPot();
        Player p = playerAt(seat);
        if (p != null) {
            tell(p, cfg().formatPrefixed(bigBlind ? "you-post-bb" : "you-post-sb", "amount", money(amount)));
        }
    }

    @Override
    public void onHoleCards(int seat, List<Card> cards) {
        Player p = playerAt(seat);
        if (p != null) {
            tell(p, cfg().formatPrefixed("your-cards", "cards", cfg().formatCards(cards)));
            ui().dealt(p);
        }
    }

    @Override
    public void onTurn(int seat, ActionOptions options) {
        refreshPot();
        Player p = playerAt(seat);
        if (p != null) {
            ui().sendTurnPrompt(p, this, options, game.getHoleCards(seat));
        }
        startTurnTimer(seat); // offline players are folded by the timer, then dropped before the next deal
    }

    @Override
    public void onAction(int seat, ActionType type, long streetBet, boolean allIn, boolean left) {
        ConfigManager cfg = cfg();
        String name = nameAt(seat);
        String key = type == ActionType.ALL_IN || (allIn && type != ActionType.FOLD) ? "allin" : type.name().toLowerCase();
        lastAction.put(seat, cfg.formatMessage("display-action-" + key, "amount", money(streetBet)));
        refreshSeat(seat);
        refreshPot();
        Player p = playerAt(seat);
        if (left) {
            broadcast(cfg.formatPrefixed("action-left-fold", "player", name), p);
            return;
        }
        // The actor gets their own line; everyone else the table line
        broadcast(cfg.formatPrefixed("action-" + key, "player", name, "amount", money(streetBet)), p);
        if (p != null) tell(p, cfg.formatPrefixed("you-" + key, "amount", money(streetBet)));
        if (type == ActionType.FOLD) {
            if (p != null) ui().folded(p);
        } else if (type != ActionType.CHECK) {
            ui().chips(this);
        }
    }

    @Override
    public void onStreet(Street street, List<Card> board) {
        at(() -> {
            for (int s = 0; s < game.getMaxSeats(); s++) {
                if (lastAction.remove(s) != null) refreshSeat(s);
            }
            broadcast(cfg().formatPrefixed("board-dealt",
                "street", cfg().getMessage("street-" + street.name().toLowerCase()),
                "cards", cfg().formatCards(board)), null);
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
        if (p != null) tell(p, cfg().formatPrefixed("uncalled-returned", "amount", money(amount)));
        refreshSeat(seat);
    }

    @Override
    public void onShowdown(List<ShowdownHand> hands) {
        cancelTurnTimer();
        at(() -> {
            for (ShowdownHand h : hands) {
                broadcast(cfg().formatPrefixed("showdown-hand", "player", names.getOrDefault(h.player(), "?"),
                    "cards", cfg().formatCards(h.holeCards()), "hand", cfg().describeHand(h.value())), null);
            }
        });
    }

    @Override
    public void onPotAwarded(int potIndex, long amount, Map<Integer, Long> shares, HandValue winningHand) {
        Map<Integer, UUID> winners = new HashMap<>();
        shares.keySet().forEach(s -> winners.put(s, game.getPlayer(s)));
        at(() -> {
            ConfigManager cfg = cfg();
            String hand = winningHand == null ? "" : cfg.describeHand(winningHand);
            for (Map.Entry<Integer, Long> e : shares.entrySet()) {
                UUID uuid = winners.get(e.getKey());
                String name = names.getOrDefault(uuid, "?");
                String won = money(e.getValue());
                Player p = uuid == null ? null : Bukkit.getPlayer(uuid);
                if (winningHand == null) {
                    broadcast(cfg.formatPrefixed("pot-won-uncontested", "player", name, "amount", won), p);
                } else {
                    broadcast(cfg.formatPrefixed(potIndex == 0 ? "pot-won" : "side-pot-won",
                        "player", name, "amount", won, "hand", hand), p);
                }
                if (p != null) {
                    tell(p, cfg.formatPrefixed(winningHand == null ? "you-win-uncontested" : "you-win",
                        "amount", won, "hand", hand));
                    ui().win(p, e.getValue(), winningHand);
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
        // The presenter clears the felt after the result has been shown, then onTableCleared() asks
        // everyone to play again.
    }

    @Override
    public void onHandVoided(VoidResult result) {
        cancelTurnTimer();
        result.refundedToStacks().forEach((seat, amount) -> {
            Player p = playerAt(seat);
            if (p != null) tell(p, cfg().formatPrefixed("hand-voided", "amount", money(amount)));
        });
        result.refundedToLeavers().forEach((uuid, amount) -> payOut(uuid, amount, Bukkit.getPlayer(uuid), "hand-voided"));
    }

    private TablePresenter.Timing timing() {
        TablePresenter.Timing d = TablePresenter.Timing.DEFAULT;
        return new TablePresenter.Timing(d.runoutTicks(), d.revealTicks(), d.awardTicks(),
            20L * cfg().getShowdownDisplaySeconds(), FOLD_HOLD_TICKS);
    }

    private Runnable later(long ticks, Runnable task) {
        BukkitTask[] self = new BukkitTask[1];
        self[0] = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            scheduled.remove(self[0]);
            if (!closed) task.run();
        }, Math.max(1L, ticks));
        scheduled.add(self[0]);
        return () -> {
            self[0].cancel();
            scheduled.remove(self[0]);
        };
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
                actionBar(p, cfg().formatMessage("turn-timer", "seconds", left[0]));
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
            if (player != null) tell(player, cfg().getPrefixed("auto-left-missed-turns"));
            leave(uuid, player, "player-left-inactive", "left-cashout");
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
        stopReadyTicker();
        readyCheck.stop();
        if (game.isHandInProgress()) {
            enter(game::voidHand);
        }
        presenter.clearNow();
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
            refreshSeat(s);
        }
        reservedSeats.clear();
        reservedUntil.clear();
        pendingLeaves.clear();
        missedTurns.clear();
        remindedReady.clear();
        lastAction.clear();
        refreshPot();
        if (!closed) readyCheck.start(List.of(), List.of(), now(), 0);
    }

    /** Refund and cash out everyone, then remove every entity this table spawned. */
    public void cleanup() {
        if (closed) return;
        removeAllPlayers();
        closed = true;
        stopReadyTicker();
        new ArrayList<>(scheduled).forEach(BukkitTask::cancel);
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
            if (player != null) tell(player, cfg().formatPrefixed(messageKey, "amount", money(amount)));
        } else {
            plugin.getLogger().severe("Poker table #" + id + ": FAILED to pay " + amount + " to " + uuid
                + " (" + names.getOrDefault(uuid, "?") + "). Pay it back by hand.");
            if (player != null) tell(player, cfg().formatPrefixed("error-payout", "amount", money(amount)));
        }
    }

    private void refund(UUID uuid, long amount, String why) {
        if (!economy().add(uuid, amount)) {
            plugin.getLogger().severe("Poker table #" + id + ": FAILED to refund " + amount + " to " + uuid + " (" + why + ")");
        }
    }

    // =====================================================================================
    // Display and chat helpers
    // =====================================================================================

    private void refreshSeat(int seat) {
        UUID uuid = game.getPlayer(seat);
        if (uuid == null) {
            view.setSeatInfo(seat, null, null, null, null);
            return;
        }
        view.setSeatInfo(seat, uuid, names.getOrDefault(uuid, "?"), money(game.getStack(seat)), lastAction.get(seat));
    }

    private void refreshPot() {
        long pot = game.getPot();
        view.setPotInfo(pot > 0 ? cfg().formatMessage("display-pot", "pot", money(pot)) : "");
        view.setPotChips(pot);
    }

    private String money(long amount) {
        return cfg().formatCurrency(String.format("%,d", amount));
    }

    /** A direct line; nothing is sent if the message was blanked out in messages.yml. */
    private static void tell(Player player, String message) {
        PlayerUI.send(player, message);
    }

    private static void actionBar(Player player, String message) {
        if (message != null && !ChatColor.stripColor(message).isBlank()) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
        }
    }

    /** Send to everyone seated here (and anyone picking a buy-in), except {@code except}. */
    private void broadcast(String message, Player except) {
        if (message == null || ChatColor.stripColor(message).isBlank()) return;
        Set<UUID> to = new HashSet<>(reservedSeats.values());
        to.addAll(seatedIds());
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

    private List<UUID> seatedIds() {
        List<UUID> ids = new ArrayList<>();
        for (int s = 0; s < game.getMaxSeats(); s++) {
            if (game.getPlayer(s) != null) ids.add(game.getPlayer(s));
        }
        return ids;
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

    private static long now() {
        return System.currentTimeMillis();
    }

    private ConfigManager cfg() {
        return plugin.getConfigManager();
    }

    private EconomyProvider economy() {
        return plugin.getEconomyProvider();
    }

    private PlayerUI ui() {
        return plugin.getPlayerUI();
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
        return !reservedSeats.isEmpty() || !seatedIds().isEmpty();
    }

    public int getPlayerCount() {
        return seatedIds().size();
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
