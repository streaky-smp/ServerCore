package com.streakysmp.notify;

import com.streakysmp.config.ConfigManager;
import com.streakysmp.config.ConfigView;
import com.streakysmp.core.Service;
import com.streakysmp.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * Every player-visible string, loaded from {@code messages.yml}.
 *
 * <p>Keeping text out of code means an operator can translate or restyle the
 * whole plugin without a rebuild. Messages are MiniMessage markup and may use
 * {@code <name>} placeholders supplied by the caller.
 *
 * <p>A missing key renders as a visible marker rather than an empty string or an
 * exception. Silent blanks are the worst outcome: a player sees nothing happen
 * and reports a bug that looks like broken logic instead of a missing message.
 */
public final class Messages implements Service, ConfigManager.Reloadable {

    private volatile Map<String, String> messages = Map.of();
    private volatile String prefix = "";

    @Override
    public void load(ConfigManager.ConfigBundle configs) {
        ConfigView view = configs.file("messages.yml");
        this.prefix = view.getString("prefix", "");

        // messages.yml is a nested tree; flatten to dotted keys so lookups are
        // a single map access rather than a walk per message.
        Map<String, String> flat = new HashMap<>();
        FileConfiguration raw = rawOf(configs);
        for (String key : raw.getKeys(true)) {
            Object value = raw.get(key);
            if (value != null && !(value instanceof ConfigurationSection)) {
                flat.put(key, String.valueOf(value));
            }
        }
        this.messages = Map.copyOf(flat);
    }

    private static FileConfiguration rawOf(ConfigManager.ConfigBundle configs) {
        int index = configs.names().indexOf("messages.yml");
        return configs.configs().get(index);
    }

    /** The raw MiniMessage template for a key. */
    public String raw(String key) {
        String value = messages.get(key);
        return value != null ? value : "<red>[missing message: " + key + "]</red>";
    }

    /** Renders a message with no placeholders. */
    public Component get(String key) {
        return Text.mm(raw(key));
    }

    /** Renders a message with {@code <name>} placeholders substituted. */
    public Component get(String key, Map<String, String> placeholders) {
        return Text.mm(raw(key), placeholders);
    }

    /** Renders a message with the configured chat prefix in front. */
    public Component prefixed(String key) {
        return Text.mm(prefix + raw(key));
    }

    public Component prefixed(String key, Map<String, String> placeholders) {
        return Text.mm(prefix + raw(key), placeholders);
    }

    public boolean has(String key) {
        return messages.containsKey(key);
    }

    public String prefix() {
        return prefix;
    }

    /** Builds a placeholder map from alternating key/value arguments. */
    public static Map<String, String> of(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Expected an even number of key/value arguments");
        }
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }
}
