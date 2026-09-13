package com.servercore.claim;

import com.servercore.core.Scheduling;
import com.servercore.core.Service;
import com.servercore.integration.IntegrationManager;
import com.servercore.util.Text;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows claim boundaries with particles, visible only to the viewer.
 *
 * <p>Particles rather than blocks, as the spec requires: placing marker blocks to
 * show a boundary means a crash or a reload leaves them behind, permanently, in
 * somebody's build.
 *
 * <h2>Bedrock</h2>
 * Particles do render through Geyser, but they are smaller, fade sooner, and are
 * easy to lose against a bright background on a phone screen. Visualising
 * therefore <em>also</em> prints the corner coordinates and size to chat. That is
 * the reliable channel on every platform, and it is useful on Java too -- a
 * player who wants to write the numbers down should not have to squint at
 * particles.
 *
 * <h2>Cost control</h2>
 * A 256x256 claim has over a thousand perimeter blocks. Emitting a particle at
 * each, several times a second, for every player visualising at once, is a
 * genuine server cost. The step between points scales with the perimeter so a
 * board of any size costs a bounded number of particles.
 */
public final class ClaimVisualiser implements Service {

    /** Upper bound on particle points per claim per frame. */
    private static final int MAX_POINTS_PER_CLAIM = 220;

    /** How often a frame is drawn, in ticks. */
    private static final long FRAME_INTERVAL_TICKS = 10L;

    /** Vertical column drawn at each corner so they stand out from the edges. */
    private static final int CORNER_HEIGHT = 3;

    /** How far to look for neighbouring claims to show alongside. */
    private static final int NEARBY_RADIUS = 48;

    private final Scheduling scheduling;
    private final ClaimService claims;
    private final IntegrationManager integrations;

    private final Map<UUID, BukkitTask> active = new ConcurrentHashMap<>();

    public ClaimVisualiser(Scheduling scheduling,
                           ClaimService claims,
                           IntegrationManager integrations) {
        this.scheduling = scheduling;
        this.claims = claims;
        this.integrations = integrations;
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : active.values()) {
            task.cancel();
        }
        active.clear();
    }

    /**
     * Visualises the claim the player is standing in, plus its neighbours.
     *
     * @return false if there is no claim here
     */
    public boolean visualiseHere(Player player, int seconds) {
        Location at = player.getLocation();
        Claim target = claims.claimAt(at.getWorld().getName(),
                at.getBlockX(), at.getBlockZ()).orElse(null);
        if (target == null) {
            // Still useful: show what is nearby so a player can see where there
            // is room to claim.
            List<Claim> nearby = claims.near(at.getWorld().getName(),
                    at.getBlockX(), at.getBlockZ(), NEARBY_RADIUS);
            if (nearby.isEmpty()) {
                return false;
            }
            start(player, null, nearby, seconds);
            return true;
        }
        List<Claim> nearby = new ArrayList<>(claims.near(at.getWorld().getName(),
                at.getBlockX(), at.getBlockZ(), NEARBY_RADIUS));
        nearby.removeIf(claim -> claim.id().equals(target.id()));
        start(player, target, nearby, seconds);
        return true;
    }

    /** Visualises one specific claim. */
    public void visualise(Player player, Claim claim, int seconds) {
        start(player, claim, List.of(), seconds);
    }

    private void start(Player player, Claim target, List<Claim> nearby, int seconds) {
        stop(player);
        describe(player, target, nearby);

        long frames = Math.max(1L, (seconds * 20L) / FRAME_INTERVAL_TICKS);
        long[] remaining = {frames};

        BukkitTask task = scheduling.syncTimer(() -> {
            if (!player.isOnline() || remaining[0]-- <= 0) {
                stop(player);
                return;
            }
            World world = player.getWorld();
            double y = player.getLocation().getY() + 0.5d;

            if (target != null && target.worldName().equals(world.getName())) {
                drawOutline(player, target, y, Color.LIME);
            }
            for (Claim neighbour : nearby) {
                if (neighbour.worldName().equals(world.getName())) {
                    drawOutline(player, neighbour, y, Color.RED);
                }
            }
        }, 0L, FRAME_INTERVAL_TICKS);

        active.put(player.getUniqueId(), task);
    }

    /** Cancels any visualisation running for a player. */
    public void stop(Player player) {
        BukkitTask existing = active.remove(player.getUniqueId());
        if (existing != null) {
            existing.cancel();
        }
    }

    /**
     * Draws the perimeter, with taller columns at the four corners.
     *
     * <p>Drawn at the viewer's own elevation: a claim is full-height, so there is
     * no single correct Y, and the viewer's level is the one they can actually see.
     */
    private void drawOutline(Player player, Claim claim, double y, Color colour) {
        Particle.DustOptions dust = new Particle.DustOptions(colour, 1.0f);

        int width = claim.width();
        int depth = claim.depth();
        int perimeter = 2 * (width + depth);
        int step = Math.max(1, perimeter / MAX_POINTS_PER_CLAIM);

        double minX = claim.minX();
        double maxX = claim.maxX() + 1.0d;
        double minZ = claim.minZ();
        double maxZ = claim.maxZ() + 1.0d;

        for (double x = minX; x <= maxX; x += step) {
            point(player, x, y, minZ, dust);
            point(player, x, y, maxZ, dust);
        }
        for (double z = minZ; z <= maxZ; z += step) {
            point(player, minX, y, z, dust);
            point(player, maxX, y, z, dust);
        }

        // Corners: a short vertical column makes the shape readable at a glance,
        // which matters most on a small screen.
        Particle.DustOptions cornerDust = new Particle.DustOptions(colour, 1.6f);
        for (int i = 0; i < CORNER_HEIGHT; i++) {
            double cy = y + i;
            point(player, minX, cy, minZ, cornerDust);
            point(player, maxX, cy, minZ, cornerDust);
            point(player, minX, cy, maxZ, cornerDust);
            point(player, maxX, cy, maxZ, cornerDust);
        }
    }

    private void point(Player player, double x, double y, double z, Particle.DustOptions dust) {
        // Sent to this player only, so one person visualising does not spray
        // particles across everyone else's screen.
        player.spawnParticle(Particle.DUST, x, y, z, 1, 0.0d, 0.0d, 0.0d, 0.0d, dust);
    }

    /**
     * Prints the boundary in text.
     *
     * <p>Always sent, not only for Bedrock players: it is the accessible channel,
     * and coordinates are more useful than particles when you are planning an
     * expansion.
     */
    private void describe(Player player, Claim target, List<Claim> nearby) {
        if (target != null) {
            player.sendMessage(Text.mm("<green>Showing</green> <white>" + target.name()
                    + "</white> <dark_gray>(" + target.width() + " x " + target.depth()
                    + " = " + target.area() + " blocks)</dark_gray>"));
            player.sendMessage(Text.mm("<gray>Corners:</gray> <white>"
                    + target.minX() + ", " + target.minZ() + "</white> <dark_gray>to</dark_gray> <white>"
                    + target.maxX() + ", " + target.maxZ() + "</white>"));
        } else {
            player.sendMessage(Text.mm("<gray>You are not standing in a claim.</gray>"));
        }
        if (!nearby.isEmpty()) {
            player.sendMessage(Text.mm("<red>" + nearby.size() + "</red> <gray>nearby claim"
                    + (nearby.size() == 1 ? "" : "s") + " shown in red.</gray>"));
        }
        if (integrations.isBedrockPlayer(player)) {
            player.sendMessage(Text.mm(
                    "<dark_gray>Particles can be faint on Bedrock; the coordinates above "
                            + "are the authoritative boundary.</dark_gray>"));
        }
    }

    public boolean isVisualising(Player player) {
        return active.containsKey(player.getUniqueId());
    }
}
