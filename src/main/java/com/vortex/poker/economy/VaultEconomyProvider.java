package com.vortex.poker.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;

/**
 * Simple Vault economy provider - works with any Vault-compatible economy plugin
 */
public class VaultEconomyProvider implements EconomyProvider {
    private Economy economy;
    private boolean enabled = false;

    public VaultEconomyProvider(Plugin plugin) {
        setupEconomy();
    }

    /** Try again to find an economy, e.g. when it registered after this plugin enabled. */
    public boolean reconnect() {
        return setupEconomy();
    }

    private boolean setupEconomy() {
        if (Bukkit.getServer().getPluginManager().getPlugin("Vault") == null) {
            enabled = false;
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            enabled = false;
            return false;
        }
        economy = rsp.getProvider();
        enabled = economy != null;
        return enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isAvailable() {
        return enabled && economy != null;
    }

    @Override
    public boolean hasEnough(UUID playerUuid, long amount) {
        if (!isAvailable()) return false;
        try {
            return economy.has(player(playerUuid), amount);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean add(UUID playerUuid, long amount) {
        if (!isAvailable() || amount < 0) return false;
        if (amount == 0) return true;
        try {
            return economy.depositPlayer(player(playerUuid), amount).transactionSuccess();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean subtract(UUID playerUuid, long amount) {
        if (!isAvailable() || amount < 0) return false;
        if (amount == 0) return true;
        try {
            return economy.withdrawPlayer(player(playerUuid), amount).transactionSuccess();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public long getBalance(UUID playerUuid) {
        if (!isAvailable()) return 0L;
        try {
            return (long) Math.floor(economy.getBalance(player(playerUuid)));
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public String getProviderName() {
        return enabled && economy != null ? economy.getName() : "None";
    }

    private static OfflinePlayer player(UUID uuid) {
        return Bukkit.getOfflinePlayer(uuid);
    }
}
