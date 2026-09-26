package com.vortex.poker.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

/**
 * Keeps user-owned YAML files compatible with the bundled defaults.
 */
public final class ConfigFileUpdater {
    private ConfigFileUpdater() {
    }

    public static YamlConfiguration update(JavaPlugin plugin, String resourceName, File targetFile) {
        ensureFileExists(plugin, resourceName, targetFile);

        YamlConfiguration currentConfig = YamlConfiguration.loadConfiguration(targetFile);
        YamlConfiguration defaultConfig = loadBundledDefaults(plugin, resourceName);
        if (defaultConfig == null) {
            return currentConfig;
        }

        int changedValues = 0;
        changedValues += addMissingDefaults(currentConfig, defaultConfig);

        if (changedValues > 0) {
            saveWithBackup(plugin, resourceName, targetFile, currentConfig, changedValues);
        }

        return currentConfig;
    }

    private static void ensureFileExists(JavaPlugin plugin, String resourceName, File targetFile) {
        if (targetFile.exists()) {
            return;
        }

        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for " + resourceName);
            return;
        }

        try {
            plugin.saveResource(resourceName, false);
            plugin.getLogger().info("Created " + resourceName + " from bundled defaults.");
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Bundled " + resourceName + " was not found.", e);
        }
    }

    private static YamlConfiguration loadBundledDefaults(JavaPlugin plugin, String resourceName) {
        try (InputStream defaultStream = plugin.getResource(resourceName)) {
            if (defaultStream == null) {
                plugin.getLogger().warning("Could not update " + resourceName + ": bundled defaults are missing.");
                return null;
            }

            try (InputStreamReader reader = new InputStreamReader(defaultStream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read bundled " + resourceName + " defaults.", e);
            return null;
        }
    }

    private static int addMissingDefaults(YamlConfiguration currentConfig, YamlConfiguration defaultConfig) {
        int added = 0;

        for (String path : defaultConfig.getKeys(true)) {
            if (defaultConfig.isConfigurationSection(path) || currentConfig.contains(path)) {
                continue;
            }

            currentConfig.set(path, defaultConfig.get(path));
            added++;
        }

        return added;
    }

    private static void saveWithBackup(JavaPlugin plugin, String resourceName, File targetFile,
                                       YamlConfiguration currentConfig, int changedValues) {
        try {
            File backupFile = new File(targetFile.getParentFile(), resourceName + ".pre-update.bak");
            Files.copy(targetFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            currentConfig.save(targetFile);
            plugin.getLogger().info("Updated " + resourceName + " with " + changedValues
                + " missing or migrated value(s). Existing values were preserved. Backup: " + backupFile.getName());
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not update " + resourceName + ".", e);
        }
    }
}
