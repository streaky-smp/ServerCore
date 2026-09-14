package com.streakysmp.claim;

import com.streakysmp.core.Service;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.permission.PermissionService;
import com.streakysmp.permission.Permissions;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces claim protection.
 *
 * <p>Every handler follows the same shape: work out which {@link ClaimFlag} the
 * action corresponds to, ask the claim, and cancel if refused. The decision logic
 * lives in {@link Claim#allows}, which is unit-tested; this class is the mapping
 * from Bukkit events onto those flags.
 *
 * <h2>Performance</h2>
 * Block break and place fire constantly. Each check is one chunk-bucket lookup
 * plus a scan of the few claims sharing that chunk, and the whole listener
 * short-circuits when the server has no claims at all -- which is the common case
 * for a new install and costs a single integer comparison.
 *
 * <h2>What is deliberately not protected</h2>
 * Unclaimed land is left alone entirely. A claim system that restricted actions
 * outside claims would be a world-protection plugin, which is not what was asked
 * for.
 */
public final class ClaimProtectionListener implements Service, Listener {

    /** Minimum gap between "you cannot do that here" messages, in milliseconds. */
    private static final long MESSAGE_COOLDOWN_MILLIS = 2_000L;

    private final Plugin plugin;
    private final ClaimService claims;
    private final NotificationService notifications;
    private final PermissionService permissions;

    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    public ClaimProtectionListener(Plugin plugin,
                                   ClaimService claims,
                                   NotificationService notifications,
                                   PermissionService permissions) {
        this.plugin = plugin;
        this.claims = claims;
        this.notifications = notifications;
        this.permissions = permissions;
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        lastMessage.clear();
    }

    // ------------------------------------------------------------- blocks

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (refused(event.getPlayer(), event.getBlock().getLocation(), ClaimFlag.BLOCK_BREAK)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (refused(event.getPlayer(), event.getBlock().getLocation(), ClaimFlag.BLOCK_PLACE)) {
            event.setCancelled(true);
        }
    }

    /**
     * Containers, doors, switches and farmland.
     *
     * <p>Handled in one place because they all arrive as the same event, and the
     * flag depends on what was clicked rather than on how.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        ClaimFlag flag = flagForBlock(block);
        if (flag == null) {
            return;
        }
        if (refused(event.getPlayer(), block.getLocation(), flag)) {
            event.setCancelled(true);
        }
    }

    /** Maps a clicked block onto the flag that governs it, or null if unguarded. */
    private static ClaimFlag flagForBlock(Block block) {
        // Containers first: a chest is a container regardless of its block data.
        if (block.getState() instanceof Container) {
            return ClaimFlag.CONTAINER_ACCESS;
        }
        BlockData data = block.getBlockData();
        if (data instanceof Door || data instanceof Gate || data instanceof TrapDoor) {
            return ClaimFlag.DOOR_USE;
        }
        // Switch covers both buttons and levers in the Bukkit data model.
        if (data instanceof Switch) {
            return ClaimFlag.BUTTON_USE;
        }
        if (block.getType() == org.bukkit.Material.FARMLAND
                || block.getType() == org.bukkit.Material.COMPOSTER) {
            return ClaimFlag.FARMLAND_USE;
        }
        return null;
    }

    // ------------------------------------------------------------ entities

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (refused(event.getPlayer(), event.getRightClicked().getLocation(),
                ClaimFlag.ENTITY_INTERACT)) {
            event.setCancelled(true);
        }
    }

    /**
     * Damage to animals, villagers and other players.
     *
     * <p>Player-versus-player uses the {@link ClaimFlag#PVP} flag, which is public
     * by default; damage to owned animals uses {@link ClaimFlag#ENTITY_DAMAGE},
     * which is not. Conflating the two would either make claims permanent safe
     * zones or leave farms unprotected.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null) {
            return;
        }
        Entity victim = event.getEntity();
        ClaimFlag flag = victim instanceof Player
                ? ClaimFlag.PVP
                : (victim instanceof Animals || victim instanceof Villager)
                        ? ClaimFlag.ENTITY_DAMAGE
                        : null;
        if (flag == null) {
            return;
        }
        if (refused(attacker, victim.getLocation(), flag)) {
            event.setCancelled(true);
        }
    }

    /** Unwraps an arrow or other projectile back to the player who fired it. */
    private static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        // Silent: a denial message every time a player walks past a dropped item
        // would be unusable.
        if (!allowed(player, event.getItem().getLocation(), ClaimFlag.ITEM_PICKUP)) {
            event.setCancelled(true);
        }
    }

    /**
     * Stops creeper and other entity explosions destroying claimed blocks.
     *
     * <p>Filters the affected block list rather than cancelling outright, so an
     * explosion straddling a claim border still damages the unclaimed side. The
     * explosion is not attributable to a player, so any claim coverage protects.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (claims.claimCount() == 0) {
            return;
        }
        String world = event.getLocation().getWorld().getName();
        event.blockList().removeIf(block ->
                claims.claimAt(world, block.getX(), block.getZ()).isPresent());
    }

    // ------------------------------------------------------------- helpers

    /**
     * Whether an action is permitted, with no message on refusal.
     *
     * <p>Short-circuits on an empty index and on the administrative bypass.
     */
    private boolean allowed(Player player, Location location, ClaimFlag flag) {
        if (claims.claimCount() == 0) {
            return true;
        }
        if (permissions.has(player, Permissions.CLAIM_ADMIN)) {
            return true;
        }
        return claims.allows(player.getUniqueId(), location.getWorld().getName(),
                location.getBlockX(), location.getBlockZ(), flag);
    }

    /**
     * Whether an action is refused, telling the player why at most once every
     * couple of seconds.
     *
     * <p>The rate limit matters: holding down left-click on a protected wall fires
     * the break event every tick, and twenty messages a second is worse than none.
     */
    private boolean refused(Player player, Location location, ClaimFlag flag) {
        if (allowed(player, location, flag)) {
            return false;
        }
        Claim claim = claims.claimAt(location.getWorld().getName(),
                location.getBlockX(), location.getBlockZ()).orElse(null);
        notifyRefusal(player, claim, flag);
        return true;
    }

    private void notifyRefusal(Player player, Claim claim, ClaimFlag flag) {
        long now = System.currentTimeMillis();
        Long previous = lastMessage.get(player.getUniqueId());
        if (previous != null && now - previous < MESSAGE_COOLDOWN_MILLIS) {
            return;
        }
        lastMessage.put(player.getUniqueId(), now);

        notifications.error(player, "claim.error.protected", Messages.of(
                "owner", claim == null ? "someone" : ownerName(claim),
                "claim", claim == null ? "a claim" : claim.name(),
                "action", flag.displayName().toLowerCase(java.util.Locale.ROOT)));
    }

    private static String ownerName(Claim claim) {
        var offline = org.bukkit.Bukkit.getOfflinePlayer(claim.owner());
        String name = offline.getName();
        return name == null ? "someone" : name;
    }
}
