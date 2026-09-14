package com.streakysmp.command;

import com.streakysmp.claim.Claim;
import com.streakysmp.claim.ClaimResult;
import com.streakysmp.claim.ClaimService;
import com.streakysmp.claim.ClaimSettings;
import com.streakysmp.claim.ClaimVisualiser;
import com.streakysmp.claim.TrustLevel;
import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.gui.ChatInput;
import com.streakysmp.gui.ClaimsMenu;
import com.streakysmp.gui.MenuManager;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.permission.PermissionService;
import com.streakysmp.permission.Permissions;
import com.streakysmp.util.Numbers;
import com.streakysmp.util.Text;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /claim} and its subcommands.
 *
 * <h2>Why a radius rather than corner selection</h2>
 * The common approach is a selection tool: hold an item, click two corners. That
 * needs two precise block clicks and a held item, both of which are awkward on a
 * touchscreen through Geyser. {@code /claim 16} claims a square around where the
 * player is standing, which works identically on every platform, and the GUI
 * offers expansion afterwards for anyone who wants a different shape.
 */
public final class ClaimCommand implements BasicCommand {

    private static final List<String> SUBCOMMANDS = List.of(
            "visualize", "visualise", "list", "info", "expand", "shrink",
            "rename", "trust", "untrust", "delete", "menu", "help");

    private final ClaimService claims;
    private final ClaimVisualiser visualiser;
    private final EconomyService economy;
    private final PlayerLookup lookup;
    private final PermissionService permissions;
    private final NotificationService notifications;
    private final MenuManager menus;
    private final ChatInput chatInput;
    private final Scheduling scheduling;

    public ClaimCommand(ClaimService claims,
                        ClaimVisualiser visualiser,
                        EconomyService economy,
                        PlayerLookup lookup,
                        PermissionService permissions,
                        NotificationService notifications,
                        MenuManager menus,
                        ChatInput chatInput,
                        Scheduling scheduling) {
        this.claims = claims;
        this.visualiser = visualiser;
        this.economy = economy;
        this.lookup = lookup;
        this.permissions = permissions;
        this.notifications = notifications;
        this.menus = menus;
        this.chatInput = chatInput;
        this.scheduling = scheduling;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            notifications.error(sender, "error.player-only", Messages.of());
            return;
        }

        if (args.length == 0) {
            help(player);
            return;
        }

        String first = args[0].toLowerCase(Locale.ROOT);

        // A bare number is the create shorthand: /claim 16
        Optional<Integer> radius = Numbers.parsePositiveInt(first, 1, 15_000);
        if (radius.isPresent()) {
            create(player, radius.get(), args.length >= 2
                    ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                    : "Claim");
            return;
        }

        switch (first) {
            case "visualize", "visualise", "show" -> visualise(player);
            case "list" -> list(player);
            case "info" -> info(player);
            case "expand" -> resizeBy(player, args, 1);
            case "shrink" -> resizeBy(player, args, -1);
            case "rename" -> rename(player, args);
            case "trust" -> trust(player, args);
            case "untrust" -> untrust(player, args);
            case "delete", "remove", "abandon" -> delete(player);
            case "menu", "gui" -> ClaimsMenu.openFor(menus, chatInput, player, claims,
                    visualiser, economy, notifications, lookup, scheduling);
            default -> help(player);
        }
    }

    // --------------------------------------------------------------- create

    private void create(Player player, int radius, String name) {
        if (!permissions.require(player, Permissions.CLAIM_CREATE)) {
            return;
        }
        ClaimSettings settings = claims.settings();
        if (!settings.enabled() || !settings.allowsWorld(player.getWorld().getName())) {
            notifications.error(player, "claim.error.disabled", Messages.of());
            return;
        }

        Location at = player.getLocation();
        int cx = at.getBlockX();
        int cz = at.getBlockZ();
        int x1 = cx - radius;
        int z1 = cz - radius;
        int x2 = cx + radius;
        int z2 = cz + radius;

        long area = (long) (radius * 2 + 1) * (radius * 2 + 1);
        long price = settings.priceForNew(area);
        String world = at.getWorld().getName();

        // Tell the player what it will cost before taking it. The confirmation
        // threshold is the same one /pay uses, so "expensive" means one thing.
        if (economy.settings().needsConfirmation(price)) {
            com.streakysmp.gui.ConfirmMenu.open(menus, player,
                    Text.mm("<dark_gray>Confirm claim</dark_gray>"),
                    List.of("<gray>Size:</gray> <white>" + (radius * 2 + 1) + " x "
                                    + (radius * 2 + 1) + "</white>",
                            "<gray>Area:</gray> <white>" + area + " blocks</white>",
                            "<gray>Cost:</gray> <yellow>" + economy.money().format(price) + "</yellow>"),
                    "Buy claim",
                    () -> submitCreate(player, world, x1, z1, x2, z2, name),
                    () -> notifications.info(player, "claim.cancelled", Messages.of()));
            return;
        }
        submitCreate(player, world, x1, z1, x2, z2, name);
    }

    private void submitCreate(Player player, String world, int x1, int z1, int x2, int z2, String name) {
        scheduling.thenSync(
                scheduling.supplyAsync(() ->
                        claims.create(player.getUniqueId(), world, x1, z1, x2, z2, name)),
                result -> {
                    if (!result.isSuccess()) {
                        reportFailure(player, result);
                        return;
                    }
                    Claim claim = result.claim();
                    notifications.success(player, "claim.created", Messages.of(
                            "name", claim.name(),
                            "size", claim.width() + " x " + claim.depth(),
                            "cost", economy.money().format(result.cost())));
                    visualiser.visualise(player, claim, claims.settings().visualiseSeconds());
                },
                error -> notifications.error(player, "error.internal", Messages.of()));
    }

    // ---------------------------------------------------------------- other

    private void visualise(Player player) {
        int seconds = claims.settings().visualiseSeconds();
        if (!visualiser.visualiseHere(player, seconds)) {
            notifications.info(player, "claim.no-claim-here", Messages.of());
        }
    }

    private void list(Player player) {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> claims.ownedBy(player.getUniqueId())),
                owned -> {
                    if (owned.isEmpty()) {
                        notifications.info(player, "claim.list-empty", Messages.of());
                        return;
                    }
                    player.sendMessage(Text.mm("<aqua>Your claims (" + owned.size() + ")</aqua>"));
                    for (Claim claim : owned) {
                        player.sendMessage(Text.mm("<gray>- <white>" + claim.name() + "</white> "
                                + "<dark_gray>" + claim.width() + "x" + claim.depth()
                                + " at " + claim.minX() + "," + claim.minZ()
                                + " in " + claim.worldName() + "</dark_gray>"));
                    }
                },
                error -> notifications.error(player, "error.internal", Messages.of()));
    }

    private void info(Player player) {
        Claim here = claimHere(player);
        if (here == null) {
            notifications.info(player, "claim.no-claim-here", Messages.of());
            return;
        }
        player.sendMessage(Text.mm("<aqua>" + here.name() + "</aqua>"));
        player.sendMessage(Text.mm("<gray>Owner:</gray> <white>"
                + org.bukkit.Bukkit.getOfflinePlayer(here.owner()).getName() + "</white>"));
        player.sendMessage(Text.mm("<gray>Size:</gray> <white>" + here.width() + " x "
                + here.depth() + "</white> <dark_gray>(" + here.area() + " blocks)</dark_gray>"));
        player.sendMessage(Text.mm("<gray>Members:</gray> <white>"
                + here.members().size() + "</white>"));
    }

    /**
     * Grows or shrinks the claim the player is standing in.
     *
     * @param direction 1 to expand, -1 to shrink
     */
    private void resizeBy(Player player, String[] args, int direction) {
        if (!permissions.require(player, Permissions.CLAIM_EXPAND)) {
            return;
        }
        Claim here = claimHere(player);
        if (here == null) {
            notifications.info(player, "claim.no-claim-here", Messages.of());
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Text.mm("<gray>Usage: /claim "
                    + (direction > 0 ? "expand" : "shrink") + " [blocks]</gray>"));
            return;
        }
        Optional<Integer> amount = Numbers.parsePositiveInt(args[1], 1, 10_000);
        if (amount.isEmpty()) {
            notifications.error(player, "error.invalid-amount", Messages.of("input", args[1]));
            return;
        }
        int delta = amount.get() * direction;

        String id = here.id();
        int x1 = here.minX() - delta;
        int z1 = here.minZ() - delta;
        int x2 = here.maxX() + delta;
        int z2 = here.maxZ() + delta;

        scheduling.thenSync(
                scheduling.supplyAsync(() ->
                        claims.resize(player.getUniqueId(), id, x1, z1, x2, z2)),
                result -> {
                    if (!result.isSuccess()) {
                        reportFailure(player, result);
                        return;
                    }
                    notifications.success(player, "claim.resized", Messages.of(
                            "size", result.claim().width() + " x " + result.claim().depth(),
                            "cost", economy.money().format(result.cost())));
                    visualiser.visualise(player, result.claim(), claims.settings().visualiseSeconds());
                },
                error -> notifications.error(player, "error.internal", Messages.of()));
    }

    private void rename(Player player, String[] args) {
        Claim here = claimHere(player);
        if (here == null) {
            notifications.info(player, "claim.no-claim-here", Messages.of());
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Text.mm("<gray>Usage: /claim rename [new name]</gray>"));
            return;
        }
        String newName = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        String id = here.id();
        scheduling.thenSync(
                scheduling.supplyAsync(() -> claims.rename(player.getUniqueId(), id, newName)),
                result -> {
                    if (result.isSuccess()) {
                        notifications.success(player, "claim.renamed",
                                Messages.of("name", result.claim().name()));
                    } else {
                        reportFailure(player, result);
                    }
                },
                error -> notifications.error(player, "error.internal", Messages.of()));
    }

    private void trust(Player player, String[] args) {
        Claim here = claimHere(player);
        if (here == null) {
            notifications.info(player, "claim.no-claim-here", Messages.of());
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Text.mm("<gray>Usage: /claim trust [player] [level]</gray>"));
            player.sendMessage(Text.mm("<gray>Levels: <white>access, container, build, manage</white></gray>"));
            return;
        }
        TrustLevel level = args.length >= 3
                ? TrustLevel.byName(args[2]).orElse(null)
                : TrustLevel.BUILD;
        if (level == null) {
            notifications.error(player, "claim.error.bad-trust", Messages.of("name", args[2]));
            return;
        }

        String id = here.id();
        String targetName = args[1];
        scheduling.thenSync(lookup.resolve(targetName), resolved -> {
            if (resolved.isEmpty()) {
                notifications.error(player, "error.unknown-player", Messages.of("name", targetName));
                return;
            }
            var target = resolved.get();
            scheduling.thenSync(
                    scheduling.supplyAsync(() -> claims.setMember(
                            player.getUniqueId(), id, target.uuid(), level)),
                    result -> {
                        if (result.isSuccess()) {
                            notifications.success(player, "claim.trusted", Messages.of(
                                    "name", Text.escape(target.name()),
                                    "level", level.displayName()));
                        } else {
                            reportFailure(player, result);
                        }
                    },
                    error -> notifications.error(player, "error.internal", Messages.of()));
        }, error -> notifications.error(player, "error.internal", Messages.of()));
    }

    private void untrust(Player player, String[] args) {
        Claim here = claimHere(player);
        if (here == null) {
            notifications.info(player, "claim.no-claim-here", Messages.of());
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Text.mm("<gray>Usage: /claim untrust [player]</gray>"));
            return;
        }
        String id = here.id();
        String targetName = args[1];
        scheduling.thenSync(lookup.resolve(targetName), resolved -> {
            if (resolved.isEmpty()) {
                notifications.error(player, "error.unknown-player", Messages.of("name", targetName));
                return;
            }
            var target = resolved.get();
            scheduling.thenSync(
                    scheduling.supplyAsync(() -> claims.removeMember(
                            player.getUniqueId(), id, target.uuid())),
                    result -> {
                        if (result.isSuccess()) {
                            notifications.success(player, "claim.untrusted",
                                    Messages.of("name", Text.escape(target.name())));
                        } else {
                            reportFailure(player, result);
                        }
                    },
                    error -> notifications.error(player, "error.internal", Messages.of()));
        }, error -> notifications.error(player, "error.internal", Messages.of()));
    }

    private void delete(Player player) {
        Claim here = claimHere(player);
        if (here == null) {
            notifications.info(player, "claim.no-claim-here", Messages.of());
            return;
        }
        long refund = claims.settings().refundFor(here.paid());
        String id = here.id();

        // Always confirm: deleting a claim removes protection from a build, and
        // there is no undo.
        com.streakysmp.gui.ConfirmMenu.open(menus, player,
                Text.mm("<dark_gray>Delete claim</dark_gray>"),
                List.of("<gray>Claim:</gray> <white>" + here.name() + "</white>",
                        "<gray>Size:</gray> <white>" + here.width() + " x " + here.depth() + "</white>",
                        refund > 0
                                ? "<gray>Refund:</gray> <green>"
                                        + economy.money().format(refund) + "</green>"
                                : "<gray>No refund.</gray>",
                        "",
                        "<red>This removes protection permanently.</red>"),
                "Delete",
                () -> scheduling.thenSync(
                        scheduling.supplyAsync(() -> claims.delete(player.getUniqueId(), id)),
                        result -> {
                            if (result.isSuccess()) {
                                notifications.success(player, "claim.deleted", Messages.of(
                                        "name", result.claim().name(),
                                        "refund", economy.money().format(result.cost())));
                            } else {
                                reportFailure(player, result);
                            }
                        },
                        error -> notifications.error(player, "error.internal", Messages.of())),
                () -> notifications.info(player, "claim.cancelled", Messages.of()));
    }

    // -------------------------------------------------------------- helpers

    private Claim claimHere(Player player) {
        Location at = player.getLocation();
        return claims.claimAt(at.getWorld().getName(), at.getBlockX(), at.getBlockZ()).orElse(null);
    }

    private void reportFailure(Player player, ClaimResult result) {
        ClaimSettings settings = claims.settings();
        notifications.error(player, result.messageKey(), Messages.of(
                "needed", economy.money().format(result.shortfall()),
                "cost", economy.money().format(result.cost()),
                "min", String.valueOf(settings.minSideLength()),
                "max", String.valueOf(settings.maxSideLength()),
                "limit", String.valueOf(settings.maxClaimsPerPlayer())));
    }

    private void help(Player player) {
        player.sendMessage(Text.mm("<aqua>Land claims</aqua>"));
        player.sendMessage(Text.mm("<gray>/claim [radius] [name]</gray> <dark_gray>-</dark_gray> "
                + "<white>buy a square claim around you</white>"));
        player.sendMessage(Text.mm("<gray>/claim visualize</gray> <dark_gray>-</dark_gray> "
                + "<white>show boundaries</white>"));
        player.sendMessage(Text.mm("<gray>/claim menu</gray> <dark_gray>-</dark_gray> "
                + "<white>manage your claims</white>"));
        player.sendMessage(Text.mm("<gray>/claim info | list | expand | shrink | rename</gray>"));
        player.sendMessage(Text.mm("<gray>/claim trust [player] [level] | untrust [player]</gray>"));
        player.sendMessage(Text.mm("<gray>/claim delete</gray>"));

        ClaimSettings settings = claims.settings();
        player.sendMessage(Text.mm("<dark_gray>Price: "
                + economy.money().format(settings.pricePerBlock()) + " per block. Sizes "
                + settings.minSideLength() + "-" + settings.maxSideLength() + ".</dark_gray>"));
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String option : SUBCOMMANDS) {
                if (option.startsWith(partial)) {
                    out.add(option);
                }
            }
            // Offer a couple of sensible radii alongside the subcommands.
            for (String radius : List.of("8", "16", "32")) {
                if (radius.startsWith(partial)) {
                    out.add(radius);
                }
            }
            return out;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("trust")
                || args[0].equalsIgnoreCase("untrust"))) {
            return lookup.suggestOnline(args[1],
                    source.getSender() instanceof Player p ? p : null);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("trust")) {
            String partial = args[2].toLowerCase(Locale.ROOT);
            List<String> levels = new ArrayList<>();
            for (TrustLevel level : TrustLevel.values()) {
                String name = level.name().toLowerCase(Locale.ROOT);
                if (name.startsWith(partial)) {
                    levels.add(name);
                }
            }
            return levels;
        }
        return List.of();
    }

    @Override
    public boolean canUse(@NotNull CommandSender sender) {
        return permissions.has(sender, Permissions.CLAIM_CREATE);
    }

    @Override
    public @NotNull String permission() {
        return Permissions.CLAIM_CREATE;
    }
}
