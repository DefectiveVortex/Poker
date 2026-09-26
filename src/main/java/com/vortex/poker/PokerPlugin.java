package com.vortex.poker;

import com.vortex.poker.commands.PokerCommand;
import com.vortex.poker.config.ConfigManager;
import com.vortex.poker.economy.EconomyProvider;
import com.vortex.poker.economy.VaultEconomyProvider;
import com.vortex.poker.gui.ActionMenu;
import com.vortex.poker.gui.BuyInMenu;
import com.vortex.poker.integration.CardResourcePack;
import com.vortex.poker.stats.StatsManager;
import com.vortex.poker.table.CardDisplayCleaner;
import com.vortex.poker.table.TableInteractListener;
import com.vortex.poker.table.TableManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** Texas Hold 'Em: wires config, economy, tables, commands and integrations together. */
public class PokerPlugin extends JavaPlugin {
    private ConfigManager configManager;
    private EconomyProvider economyProvider;
    private StatsManager statsManager;
    private CardResourcePack cardResourcePack;
    private TableManager tableManager;
    private BuyInMenu buyInMenu;
    private ActionMenu actionMenu;
    private PokerPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);

        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().severe("Vault is required. Install Vault plus a Vault-compatible economy plugin, then restart the server.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        VaultEconomyProvider vault = new VaultEconomyProvider(this);
        economyProvider = vault;
        if (!vault.isAvailable()) {
            // Economy plugins register with Vault in their own onEnable, which may run after ours.
            // The first tick runs once every plugin is enabled, so decide then.
            getServer().getScheduler().runTask(this, () -> {
                if (!vault.reconnect()) {
                    getLogger().severe("Vault was found, but no economy provider was registered. Install a Vault-compatible economy plugin.");
                    getServer().getPluginManager().disablePlugin(this);
                }
            });
        }

        statsManager = new StatsManager(this, configManager);
        cardResourcePack = new CardResourcePack(this);
        tableManager = new TableManager(this);
        tableManager.loadTables();

        getServer().getPluginManager().registerEvents(new TableInteractListener(this, tableManager), this);
        getServer().getPluginManager().registerEvents(new CardDisplayCleaner(this, tableManager), this);
        buyInMenu = new BuyInMenu(this);
        actionMenu = new ActionMenu(this);
        getServer().getPluginManager().registerEvents(buyInMenu, this);
        getServer().getPluginManager().registerEvents(actionMenu, this);

        PluginCommand command = getCommand("poker");
        if (command != null) {
            PokerCommand executor = new PokerCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholderExpansion = new PokerPlaceholderExpansion(this);
            placeholderExpansion.register();
            getLogger().info("PlaceholderAPI found: placeholders enabled.");
        }

        getLogger().info("Poker enabled.");
    }

    @Override
    public void onDisable() {
        // Tables first: this voids hands in progress and refunds their contributions while the economy is still up.
        if (tableManager != null) tableManager.shutdown();
        if (placeholderExpansion != null) placeholderExpansion.unregister();
        if (statsManager != null) statsManager.shutdown();
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public EconomyProvider getEconomyProvider() {
        return economyProvider;
    }

    public StatsManager getStatsManager() {
        return statsManager;
    }

    public CardResourcePack getCardResourcePack() {
        return cardResourcePack;
    }

    public TableManager getTableManager() {
        return tableManager;
    }

    public BuyInMenu getBuyInMenu() {
        return buyInMenu;
    }

    public ActionMenu getActionMenu() {
        return actionMenu;
    }
}
