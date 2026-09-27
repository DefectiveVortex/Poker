package com.vortex.poker.game;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Play again?" between hands. While active, each seated player is either confirmed or pending with a
 * deadline; pending players who miss it are handed back by {@link #expired(long)} so the table can
 * stand them up. The next hand may start once nobody is pending (the table also needs two funded
 * players). Players who sit down count as confirmed: choosing a seat is choosing to play.
 * Plain Java; times are in milliseconds from whatever clock the caller uses.
 */
public final class ReadyCheck {

    public enum Result { CONFIRMED, ALREADY, NOT_WAITING }

    private final Map<UUID, Long> pending = new LinkedHashMap<>();
    private final Set<UUID> confirmed = new LinkedHashSet<>();
    private boolean active;

    /**
     * Open a new check: {@code mustConfirm} have until {@code now + timeoutMillis}; {@code alreadyIn}
     * (e.g. players who sat down during the last hand) start confirmed. A timeout of 0 or less
     * confirms everyone at once, i.e. no check.
     */
    public void start(Collection<UUID> mustConfirm, Collection<UUID> alreadyIn, long now, long timeoutMillis) {
        pending.clear();
        confirmed.clear();
        active = true;
        confirmed.addAll(alreadyIn);
        for (UUID id : mustConfirm) {
            if (confirmed.contains(id)) continue;
            if (timeoutMillis <= 0) {
                confirmed.add(id);
            } else {
                pending.put(id, now + timeoutMillis);
            }
        }
    }

    /** Close the check (a hand is being dealt). */
    public void stop() {
        active = false;
        pending.clear();
        confirmed.clear();
    }

    public boolean isActive() {
        return active;
    }

    public Result confirm(UUID id) {
        if (!active) return Result.NOT_WAITING;
        if (pending.remove(id) != null) {
            confirmed.add(id);
            return Result.CONFIRMED;
        }
        return confirmed.contains(id) ? Result.ALREADY : Result.NOT_WAITING;
    }

    /** Someone sat down: they are in without confirming. */
    public void join(UUID id) {
        if (!active) return;
        pending.remove(id);
        confirmed.add(id);
    }

    /** Someone left the table. */
    public void remove(UUID id) {
        pending.remove(id);
        confirmed.remove(id);
    }

    /** Remove and return everyone whose deadline has passed. */
    public List<UUID> expired(long now) {
        List<UUID> late = pending.entrySet().stream()
            .filter(e -> e.getValue() <= now).map(Map.Entry::getKey).toList();
        late.forEach(pending::remove);
        return late;
    }

    /** Active and nobody left to confirm. */
    public boolean allConfirmed() {
        return active && pending.isEmpty();
    }

    public boolean isPending(UUID id) {
        return pending.containsKey(id);
    }

    public boolean isConfirmed(UUID id) {
        return confirmed.contains(id);
    }

    public int confirmedCount() {
        return confirmed.size();
    }

    public int total() {
        return confirmed.size() + pending.size();
    }

    public Set<UUID> getPending() {
        return Set.copyOf(pending.keySet());
    }

    public Set<UUID> getConfirmed() {
        return Set.copyOf(confirmed);
    }

    /** Whole seconds until {@code id}'s deadline (rounded up), or 0 if they aren't pending. */
    public int secondsLeft(UUID id, long now) {
        Long deadline = pending.get(id);
        if (deadline == null) return 0;
        return (int) Math.max(0, (deadline - now + 999) / 1000);
    }
}
