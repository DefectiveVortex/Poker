package com.vortex.poker.economy;

import java.util.UUID;

/**
 * Economy operations in whole currency units. Table stacks are longs, so the economy is too.
 */
public interface EconomyProvider {
    /** Whether the provider is ready to service economy requests. */
    boolean isAvailable();

    /** Check if a player has at least this much money. */
    boolean hasEnough(UUID playerUuid, long amount);

    /** Add money to a player's account. */
    boolean add(UUID playerUuid, long amount);

    /** Subtract money from a player's account. */
    boolean subtract(UUID playerUuid, long amount);

    /** A player's balance, rounded down to whole units. */
    long getBalance(UUID playerUuid);

    /** Name of the economy plugin behind this provider. */
    String getProviderName();
}
