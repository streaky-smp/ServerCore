package com.servercore.config;

import com.servercore.util.Durations;
import com.servercore.util.Numbers;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A validating, path-aware wrapper around a Bukkit {@link ConfigurationSection}.
 *
 * <p>Bukkit's own getters return silent defaults for missing or mistyped values.
 * That is the wrong behaviour for gameplay economics: a typo in a rent price
 * should stop the server, not charge players a different number. Every accessor
 * here fails with the full config path when the value is present but unusable.
 */
public final class ConfigView {

    private final ConfigurationSection section;
    private final String path;

    ConfigView(ConfigurationSection section, String path) {
        this.section = section;
        this.path = path;
    }

    private String pathOf(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    /** Navigates into a subsection, failing if it is absent. */
    public ConfigView section(String key) {
        ConfigurationSection child = section.getConfigurationSection(key);
        if (child == null) {
            throw new ConfigException(pathOf(key), "expected a section, found " + describe(section.get(key)));
        }
        return new ConfigView(child, pathOf(key));
    }

    /** Navigates into a subsection that may be absent. */
    public Optional<ConfigView> optionalSection(String key) {
        ConfigurationSection child = section.getConfigurationSection(key);
        return child == null ? Optional.empty() : Optional.of(new ConfigView(child, pathOf(key)));
    }

    public boolean has(String key) {
        return section.contains(key);
    }

    public List<String> keys() {
        return new ArrayList<>(section.getKeys(false));
    }

    public String getString(String key, String fallback) {
        Object raw = section.get(key);
        if (raw == null) {
            return fallback;
        }
        return String.valueOf(raw);
    }

    public String requireString(String key) {
        Object raw = section.get(key);
        if (raw == null) {
            throw new ConfigException(pathOf(key), "required value is missing");
        }
        String value = String.valueOf(raw);
        if (value.isBlank()) {
            throw new ConfigException(pathOf(key), "required value is blank");
        }
        return value;
    }

    public boolean getBoolean(String key, boolean fallback) {
        Object raw = section.get(key);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("yes")) {
            return true;
        }
        if (s.equals("false") || s.equals("no")) {
            return false;
        }
        throw new ConfigException(pathOf(key), "expected true/false, found '" + raw + "'");
    }

    public int getInt(String key, int fallback, int min, int max) {
        Object raw = section.get(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number n)) {
            throw new ConfigException(pathOf(key), "expected a number, found '" + raw + "'");
        }
        long value = n.longValue();
        if (value < min || value > max) {
            throw new ConfigException(pathOf(key), "must be between " + min + " and " + max + ", found " + value);
        }
        return (int) value;
    }

    public long getLong(String key, long fallback, long min, long max) {
        Object raw = section.get(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number n)) {
            throw new ConfigException(pathOf(key), "expected a number, found '" + raw + "'");
        }
        long value = n.longValue();
        if (value < min || value > max) {
            throw new ConfigException(pathOf(key), "must be between " + min + " and " + max + ", found " + value);
        }
        return value;
    }

    /**
     * Reads a money amount written in major units (what an operator types, e.g.
     * {@code 25000} or {@code 4.50}) and returns it in minor units.
     *
     * <p>Config is authored in whole currency for readability; everything past
     * this boundary is integer minor units so no rounding can occur later.
     */
    public long getMoney(String key, long fallbackMinor, int fractionDigits) {
        Object raw = section.get(key);
        if (raw == null) {
            return fallbackMinor;
        }
        Optional<Long> parsed = Numbers.parseMoney(String.valueOf(raw), fractionDigits);
        return parsed.orElseThrow(() -> new ConfigException(pathOf(key),
                "expected a non-negative money amount with at most " + fractionDigits
                        + " decimal places, found '" + raw + "'"));
    }

    /**
     * Reads a duration such as {@code 48h}, {@code 7d} or {@code 1d12h}.
     *
     * <p>Accepts {@code 0}, {@code none}, {@code off} and {@code disabled} as zero,
     * because "no cooldown" is a setting an operator will reasonably want to
     * express and {@code 0s} is not a duration the parser can represent.
     */
    public Duration getDuration(String key, Duration fallback) {
        Object raw = section.get(key);
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (value.equals("0") || value.equals("none") || value.equals("off") || value.equals("disabled")) {
            return Duration.ZERO;
        }
        return Durations.parse(value).orElseThrow(() -> new ConfigException(pathOf(key),
                "expected a duration like 30m, 48h, 7d or 1d12h (or 0 for none), found '" + raw + "'"));
    }

    public List<String> getStringList(String key, List<String> fallback) {
        if (!section.contains(key)) {
            return fallback;
        }
        return section.getStringList(key);
    }

    /** Reads an enum constant case-insensitively, listing valid options on failure. */
    public <E extends Enum<E>> E getEnum(String key, Class<E> type, E fallback) {
        Object raw = section.get(key);
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw).trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(value)) {
                return constant;
            }
        }
        StringBuilder options = new StringBuilder();
        for (E constant : type.getEnumConstants()) {
            if (!options.isEmpty()) {
                options.append(", ");
            }
            options.append(constant.name().toLowerCase(Locale.ROOT));
        }
        throw new ConfigException(pathOf(key), "expected one of [" + options + "], found '" + raw + "'");
    }

    private static String describe(Object value) {
        if (value == null) {
            return "nothing";
        }
        return value.getClass().getSimpleName();
    }
}
