package com.streakysmp.integration;

import com.streakysmp.core.Service;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects optional third-party plugins and exposes what they provide.
 *
 * <p>Nothing here is required. Every capability degrades to a working default, so
 * a Java-only server with none of these installed runs the full plugin.
 *
 * <h2>Why reflection for Geyser and Floodgate</h2>
 * Their APIs are published only as snapshot artifacts. Compiling against a moving
 * snapshot means the build can break without a single line of our code changing,
 * and shipping against one risks a {@link NoClassDefFoundError} at runtime if the
 * server has a different build. The surface we actually need is one method --
 * "is this player on Bedrock" -- so reflection costs almost nothing and removes
 * both risks. If Floodgate is absent we fall back to the UUID shape Floodgate
 * itself assigns, which is stable and documented.
 */
public final class IntegrationManager implements Service {

    /**
     * Floodgate issues Bedrock players a UUID whose first eight bytes are zero.
     * Used only when the Floodgate API itself is unavailable.
     */
    private static final long BEDROCK_UUID_HIGH_BITS = 0L;

    private final Logger logger;

    private boolean geyser;
    private boolean floodgate;

    /** Resolved once at startup; null when Floodgate is absent or incompatible. */
    private Object floodgateApiInstance;
    private Method isFloodgatePlayerMethod;

    public IntegrationManager(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void onEnable() {
        // Geyser's Spigot plugin has been published under more than one name.
        geyser = isEnabled("Geyser-Spigot") || isEnabled("Geyser");
        floodgate = isEnabled("floodgate") || isEnabled("Floodgate");

        if (floodgate) {
            bindFloodgateApi();
        }
        logDetection();
    }

    private static boolean isEnabled(String pluginName) {
        return Bukkit.getPluginManager().getPlugin(pluginName) != null
                && Bukkit.getPluginManager().isPluginEnabled(pluginName);
    }

    private void bindFloodgateApi() {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Method getInstance = apiClass.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            Method isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            if (instance == null) {
                throw new IllegalStateException("FloodgateApi.getInstance() returned null");
            }
            this.floodgateApiInstance = instance;
            this.isFloodgatePlayerMethod = isFloodgatePlayer;
        } catch (Throwable t) {
            // Downgrade rather than fail: the UUID heuristic still identifies
            // Bedrock players correctly for GUI purposes.
            logger.log(Level.WARNING, "Floodgate is installed but its API could not be bound; "
                    + "falling back to UUID-based Bedrock detection", t);
            this.floodgateApiInstance = null;
            this.isFloodgatePlayerMethod = null;
        }
    }

    private void logDetection() {
        Map<String, Boolean> detected = new LinkedHashMap<>();
        detected.put("Geyser", geyser);
        detected.put("Floodgate", floodgate);

        StringBuilder present = new StringBuilder();
        StringBuilder absent = new StringBuilder();
        detected.forEach((name, found) -> {
            StringBuilder target = found ? present : absent;
            if (!target.isEmpty()) {
                target.append(", ");
            }
            target.append(name);
        });

        logger.info("Optional integrations detected: " + (present.isEmpty() ? "none" : present));
        if (!absent.isEmpty()) {
            logger.info("Not present (features degrade cleanly): " + absent);
        }
        if (geyser && !floodgate) {
            logger.info("Geyser is present without Floodgate. Bedrock players will still be served, "
                    + "but cannot be identified individually, so GUI layouts use Java defaults.");
        }
    }

    /**
     * Whether a player is connected from Bedrock.
     *
     * <p>Used to adapt GUI layouts, never to gate functionality: a Bedrock player
     * must be able to reach everything a Java player can.
     */
    public boolean isBedrockPlayer(UUID playerId) {
        if (isFloodgatePlayerMethod != null && floodgateApiInstance != null) {
            try {
                Object result = isFloodgatePlayerMethod.invoke(floodgateApiInstance, playerId);
                if (result instanceof Boolean bedrock) {
                    return bedrock;
                }
            } catch (Throwable t) {
                logger.log(Level.FINE, "Floodgate lookup failed; using UUID heuristic", t);
            }
        }
        return looksLikeBedrockUuid(playerId);
    }

    public boolean isBedrockPlayer(Player player) {
        return isBedrockPlayer(player.getUniqueId());
    }

    /**
     * Floodgate's UUID convention for Bedrock accounts: the high 64 bits are zero.
     * A genuine Mojang UUID is version 4 and never has this shape.
     */
    static boolean looksLikeBedrockUuid(UUID uuid) {
        return uuid.getMostSignificantBits() == BEDROCK_UUID_HIGH_BITS;
    }

    public boolean hasGeyser() {
        return geyser;
    }

    public boolean hasFloodgate() {
        return floodgate;
    }

    /** True when individual Bedrock players can be identified reliably. */
    public boolean canIdentifyBedrockPlayers() {
        return isFloodgatePlayerMethod != null;
    }

}
