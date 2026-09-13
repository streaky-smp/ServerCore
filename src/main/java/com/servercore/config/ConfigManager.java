package com.servercore.config;

import com.servercore.core.Service;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Loads and reloads the plugin's YAML configuration.
 *
 * <p>Configuration is split across several files so operators are not editing a
 * two-thousand-line document to change a rent price. Each file is read through
 * a {@link ConfigView}, which validates as it reads.
 *
 * <p>Reloads are transactional: every file is parsed and every listener's
 * validation runs against the new values before anything is swapped in. A bad
 * edit leaves the running configuration untouched and reports the error, rather
 * than half-applying and leaving the economy in an inconsistent state.
 */
public final class ConfigManager implements Service {

    /** Config files owned by this plugin, created from bundled defaults on first run. */
    private static final List<String> FILES = List.of(
            "config.yml",
            "messages.yml",
            "shop.yml");

    private final JavaPlugin plugin;
    private final List<Reloadable> listeners = new CopyOnWriteArrayList<>();

    private volatile Loaded loaded = new Loaded(List.of(), List.of());

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** A component that derives immutable settings from configuration. */
    @FunctionalInterface
    public interface Reloadable {
        /**
         * Reads and validates settings from {@code configs}.
         *
         * <p>Implementations must not apply anything to live state here; throwing
         * must leave the component exactly as it was. Applying happens only after
         * every listener has validated successfully.
         */
        void load(ConfigBundle configs) throws ConfigException;
    }

    /** The set of parsed config files, addressed by file name. */
    public record ConfigBundle(List<String> names, List<FileConfiguration> configs) {

        public ConfigView file(String name) {
            int index = names.indexOf(name);
            if (index < 0) {
                throw new ConfigException("No such config file: " + name);
            }
            return new ConfigView(configs.get(index), "");
        }

        /** Convenience for the main {@code config.yml}. */
        public ConfigView main() {
            return file("config.yml");
        }
    }

    private record Loaded(List<String> names, List<FileConfiguration> configs) {
    }

    @Override
    public void onEnable() throws Exception {
        for (String file : FILES) {
            writeDefaultIfAbsent(file);
        }
        reload();
    }

    /**
     * Registers a component to be (re)configured.
     *
     * <p>The listener is invoked immediately with the current configuration so
     * callers do not need a separate initial-load path.
     */
    public void addListener(Reloadable listener) {
        listeners.add(listener);
        listener.load(currentBundle());
    }

    /**
     * Re-reads every config file and re-applies it to all listeners.
     *
     * @throws ConfigException if any file fails to parse or validate; the
     *                         previously loaded configuration stays in effect
     */
    public void reload() {
        List<String> names = new ArrayList<>();
        List<FileConfiguration> configs = new ArrayList<>();

        for (String file : FILES) {
            File target = new File(plugin.getDataFolder(), file);
            YamlConfiguration parsed = new YamlConfiguration();
            try {
                parsed.load(target);
            } catch (Exception e) {
                throw new ConfigException("Failed to parse " + file + ": " + e.getMessage());
            }
            mergeMissingDefaults(parsed, file);
            names.add(file);
            configs.add(parsed);
        }

        ConfigBundle candidate = new ConfigBundle(List.copyOf(names), List.copyOf(configs));

        // Validate everything against the candidate before publishing it, so a
        // rejected reload cannot leave half the plugin on new settings.
        for (Reloadable listener : listeners) {
            listener.load(candidate);
        }

        this.loaded = new Loaded(candidate.names(), candidate.configs());
    }

    public ConfigBundle currentBundle() {
        Loaded snapshot = loaded;
        return new ConfigBundle(snapshot.names(), snapshot.configs());
    }

    /**
     * Copies a bundled default file into the data folder if the operator has not
     * created one. Existing files are never overwritten.
     */
    private void writeDefaultIfAbsent(String name) throws IOException {
        File target = new File(plugin.getDataFolder(), name);
        if (target.exists()) {
            return;
        }
        if (plugin.getResource(name) == null) {
            throw new IllegalStateException("Bundled default '" + name + "' is missing from the plugin jar");
        }
        if (!target.getParentFile().exists() && !target.getParentFile().mkdirs()) {
            throw new IOException("Could not create plugin data folder " + target.getParent());
        }
        plugin.saveResource(name, false);
    }

    /**
     * Fills in keys the operator's file is missing from the bundled defaults.
     *
     * <p>This is what lets a plugin update add new settings without operators
     * having to delete and re-create their configs. Their existing values always
     * win; only absent keys are supplied.
     */
    private void mergeMissingDefaults(YamlConfiguration target, String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key)) {
                    continue;
                }
                if (!target.contains(key)) {
                    target.set(key, defaults.get(key));
                    changed = true;
                }
            }
            if (changed) {
                plugin.getLogger().info("Added new default keys to " + name);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not merge defaults for " + name, e);
        }
    }
}
