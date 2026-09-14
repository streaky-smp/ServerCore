package com.streakysmp.gui;

import com.streakysmp.claim.Claim;
import com.streakysmp.claim.ClaimResult;
import com.streakysmp.claim.ClaimService;
import com.streakysmp.claim.ClaimVisualiser;
import com.streakysmp.command.PlayerLookup;
import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Management screen for a single claim, matching the layout in the specification.
 *
 * <p>Reads the claim from the service on every render rather than holding a
 * snapshot, so a change made from a subscreen or a command is reflected
 * immediately. The index lookup is in-memory, so this costs nothing.
 */
public final class ClaimMenu extends Menu {

    private final ClaimService claims;
    private final ClaimVisualiser visualiser;
    private final EconomyService economy;
    private final NotificationService notifications;
    private final PlayerLookup lookup;
    private final Scheduling scheduling;
    private final ChatInput chatInput;
    private final String claimId;

    public ClaimMenu(MenuManager menus,
                     ChatInput chatInput,
                     Player viewer,
                     ClaimService claims,
                     ClaimVisualiser visualiser,
                     EconomyService economy,
                     NotificationService notifications,
                     PlayerLookup lookup,
                     Scheduling scheduling,
                     String claimId) {
        super(menus, viewer, Text.mm("<dark_gray>Claim</dark_gray>"), 4);
        this.chatInput = chatInput;
        this.claims = claims;
        this.visualiser = visualiser;
        this.economy = economy;
        this.notifications = notifications;
        this.lookup = lookup;
        this.scheduling = scheduling;
        this.claimId = claimId;
    }

    @Override
    protected void build() {
        fillEmpty(Material.GRAY_STAINED_GLASS_PANE);

        Claim claim = claims.byId(claimId).orElse(null);
        if (claim == null) {
            set(1, 4, Button.display(ItemBuilder.of(Material.BARRIER)
                    .name("<red>This claim no longer exists</red>")
                    .clean().build()));
            backAndClose();
            return;
        }

        boolean owner = claim.isOwner(viewer.getUniqueId());

        set(0, 4, Button.display(ItemBuilder.of(Material.GRASS_BLOCK)
                .name("<white><bold>" + claim.name() + "</bold></white>")
                .lore(summary(claim))
                .clean().build()));

        set(1, 1, Button.of(ItemBuilder.of(Material.ENDER_EYE)
                .name("<aqua>Visualise</aqua>")
                .lore("<gray>Show the boundary with particles</gray>",
                        "<gray>and print the corner coordinates.</gray>")
                .clean().build(), click -> {
            click.close();
            visualiser.visualise(click.player(), claim, claims.settings().visualiseSeconds());
        }));

        set(1, 2, Button.of(ItemBuilder.of(Material.PLAYER_HEAD)
                .name("<aqua>Members</aqua>")
                .lore("<gray>" + claim.members().size() + " trusted player"
                                + (claim.members().size() == 1 ? "" : "s") + "</gray>",
                        "<gray>Click to manage.</gray>")
                .clean().build(), click -> {
            ClaimMembersMenu menu = new ClaimMembersMenu(menus, chatInput, click.player(), claims,
                    notifications, lookup, scheduling, claimId);
            menu.withParent(this);
            menu.open();
        }));

        set(1, 3, Button.of(ItemBuilder.of(Material.REDSTONE_TORCH)
                .name("<aqua>Permissions</aqua>")
                .lore("<gray>Choose what visitors may do.</gray>",
                        owner ? "<gray>Click to manage.</gray>" : "<red>Owner only.</red>")
                .clean().build(), click -> {
            if (!owner) {
                notifications.error(click.player(), "claim.error.not-permitted", Messages.of());
                return;
            }
            ClaimFlagsMenu menu = new ClaimFlagsMenu(menus, click.player(), claims,
                    notifications, scheduling, claimId);
            menu.withParent(this);
            menu.open();
        }));

        if (owner) {
            set(1, 5, expandButton(claim));
            set(1, 6, renameButton(claim));
            set(2, 8, deleteButton(claim));
        }

        backAndClose();
    }

    private List<String> summary(Claim claim) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Owner:</gray> <white>"
                + org.bukkit.Bukkit.getOfflinePlayer(claim.owner()).getName() + "</white>");
        lore.add("<gray>Size:</gray> <white>" + claim.width() + " x " + claim.depth()
                + "</white> <dark_gray>(" + claim.area() + " blocks)</dark_gray>");
        lore.add("<gray>World:</gray> <white>" + claim.worldName() + "</white>");
        lore.add("<gray>Corners:</gray> <white>" + claim.minX() + ", " + claim.minZ()
                + "</white> <dark_gray>to</dark_gray> <white>"
                + claim.maxX() + ", " + claim.maxZ() + "</white>");
        lore.add("<gray>Paid so far:</gray> <white>" + economy.money().format(claim.paid()) + "</white>");
        return lore;
    }

    private Button expandButton(Claim claim) {
        // Cost of one ring of blocks around the current bounds.
        long extra = (long) (claim.width() + 2) * (claim.depth() + 2) - claim.area();
        long cost = claims.settings().expansionPricePerBlock() * extra;

        return Button.of(ItemBuilder.of(Material.SCAFFOLDING)
                .name("<green>Expand</green>")
                .lore("<gray>Grow the claim outwards.</gray>",
                        "<gray>One block on every side:</gray> <white>"
                                + economy.money().format(cost) + "</white>",
                        "",
                        "<gray>Click to expand by 1.</gray>",
                        "<gray>Right-click to expand by 8.</gray>",
                        "<dark_gray>Or use /claim expand [blocks]</dark_gray>")
                .clean().build(), click -> {
            int by = click.isSecondary() ? 8 : 1;
            scheduling.thenSync(
                    scheduling.supplyAsync(() -> claims.resize(click.player().getUniqueId(), claimId,
                            claim.minX() - by, claim.minZ() - by,
                            claim.maxX() + by, claim.maxZ() + by)),
                    result -> {
                        report(click.player(), result, "claim.resized");
                        click.refresh();
                    },
                    error -> notifications.error(click.player(), "error.internal", Messages.of()));
        });
    }

    private Button renameButton(Claim claim) {
        return Button.of(ItemBuilder.of(Material.NAME_TAG)
                .name("<aqua>Rename</aqua>")
                .lore("<gray>Current:</gray> <white>" + claim.name() + "</white>",
                        "<gray>Click to type a new name.</gray>")
                .clean().build(), click -> {
            // Chat input rather than an anvil: it round-trips reliably on Bedrock.
            closeLater();
            chatInput.prompt(click.player(),
                    "<aqua>Type a new name for this claim, or <white>cancel</white>.</aqua>",
                    input -> scheduling.thenSync(
                            scheduling.supplyAsync(() ->
                                    claims.rename(click.player().getUniqueId(), claimId, input)),
                            result -> {
                                report(click.player(), result, "claim.renamed");
                                open();
                            },
                            error -> notifications.error(click.player(), "error.internal",
                                    Messages.of())),
                    this::open);
        });
    }

    private Button deleteButton(Claim claim) {
        long refund = claims.settings().refundFor(claim.paid());
        return Button.of(ItemBuilder.of(Material.TNT)
                .name("<red><bold>Delete</bold></red>")
                .lore("<gray>Removes protection permanently.</gray>",
                        refund > 0
                                ? "<gray>Refund:</gray> <green>"
                                        + economy.money().format(refund) + "</green>"
                                : "<gray>No refund.</gray>",
                        "",
                        "<red>You will be asked to confirm.</red>")
                .clean().build(), click -> ConfirmMenu.open(menus, click.player(),
                Text.mm("<dark_gray>Delete claim</dark_gray>"),
                List.of("<gray>Claim:</gray> <white>" + claim.name() + "</white>",
                        "<gray>Size:</gray> <white>" + claim.width() + " x " + claim.depth() + "</white>",
                        refund > 0
                                ? "<gray>Refund:</gray> <green>"
                                        + economy.money().format(refund) + "</green>"
                                : "<gray>No refund.</gray>",
                        "",
                        "<red>This cannot be undone.</red>"),
                "Delete",
                () -> scheduling.thenSync(
                        scheduling.supplyAsync(() ->
                                claims.delete(click.player().getUniqueId(), claimId)),
                        result -> {
                            report(click.player(), result, "claim.deleted");
                            if (hasParent()) {
                                parent().open();
                            }
                        },
                        error -> notifications.error(click.player(), "error.internal", Messages.of())),
                this::open));
    }

    private void backAndClose() {
        if (hasParent()) {
            set(3, 0, Button.of(ItemBuilder.of(Material.ARROW)
                    .name("<green>Back</green>")
                    .clean().build(), click -> parent().open()));
        }
        set(3, 8, Button.of(ItemBuilder.of(Material.BARRIER)
                .name("<red>Close</red>")
                .clean().build(), ClickContext::close));
    }

    private void report(Player player, ClaimResult result, String successKey) {
        if (result.isSuccess()) {
            notifications.success(player, successKey, Messages.of(
                    "name", result.claim() == null ? "" : result.claim().name(),
                    "size", result.claim() == null ? ""
                            : result.claim().width() + " x " + result.claim().depth(),
                    "cost", economy.money().format(result.cost()),
                    "refund", economy.money().format(result.cost())));
        } else {
            notifications.error(player, result.messageKey(), Messages.of(
                    "needed", economy.money().format(result.shortfall()),
                    "cost", economy.money().format(result.cost()),
                    "min", String.valueOf(claims.settings().minSideLength()),
                    "max", String.valueOf(claims.settings().maxSideLength()),
                    "limit", String.valueOf(claims.settings().maxClaimsPerPlayer())));
        }
    }
}
