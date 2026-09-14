package com.streakysmp.gui;

import com.streakysmp.claim.Claim;
import com.streakysmp.claim.ClaimService;
import com.streakysmp.claim.ClaimVisualiser;
import com.streakysmp.command.PlayerLookup;
import com.streakysmp.core.Scheduling;
import com.streakysmp.economy.EconomyService;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The claims a player owns, plus the ones they are trusted in.
 */
public final class ClaimsMenu extends PaginatedMenu<Claim> {

    private final ClaimService claims;
    private final ClaimVisualiser visualiser;
    private final EconomyService economy;
    private final NotificationService notifications;
    private final PlayerLookup lookup;
    private final Scheduling scheduling;
    private final ChatInput chatInput;

    private List<Claim> loaded = List.of();

    public ClaimsMenu(MenuManager menus,
                      ChatInput chatInput,
                      Player viewer,
                      ClaimService claims,
                      ClaimVisualiser visualiser,
                      EconomyService economy,
                      NotificationService notifications,
                      PlayerLookup lookup,
                      Scheduling scheduling) {
        super(menus, chatInput, viewer, Text.mm("<dark_gray>My Claims</dark_gray>"), 6);
        this.chatInput = chatInput;
        this.claims = claims;
        this.visualiser = visualiser;
        this.economy = economy;
        this.notifications = notifications;
        this.lookup = lookup;
        this.scheduling = scheduling;
    }

    public static void openFor(MenuManager menus,
                               ChatInput chatInput,
                               Player player,
                               ClaimService claims,
                               ClaimVisualiser visualiser,
                               EconomyService economy,
                               NotificationService notifications,
                               PlayerLookup lookup,
                               Scheduling scheduling) {
        new ClaimsMenu(menus, chatInput, player, claims, visualiser, economy,
                notifications, lookup, scheduling).open();
    }

    /** Reloads the list on every open, so returning from a claim shows current state. */
    @Override
    public void open() {
        scheduling.thenSync(
                scheduling.supplyAsync(() -> {
                    List<Claim> all = new ArrayList<>(claims.ownedBy(viewer.getUniqueId()));
                    all.addAll(claims.memberOf(viewer.getUniqueId()));
                    return List.copyOf(all);
                }),
                list -> {
                    loaded = list;
                    super.open();
                },
                error -> viewer.sendMessage(Text.mm("<red>Could not load your claims.</red>")));
    }

    @Override
    protected List<Claim> source() {
        return loaded;
    }

    @Override
    protected boolean supportsSearch() {
        return true;
    }

    @Override
    protected boolean matches(Claim entry, String lowercaseQuery) {
        return entry.name().toLowerCase(Locale.ROOT).contains(lowercaseQuery)
                || entry.worldName().toLowerCase(Locale.ROOT).contains(lowercaseQuery);
    }

    @Override
    protected List<SortOption<Claim>> sortOptions() {
        return List.of(
                new SortOption<>("Newest first",
                        Comparator.comparingLong(Claim::createdAt).reversed()),
                new SortOption<>("Largest first",
                        Comparator.comparingLong(Claim::area).reversed()),
                new SortOption<>("Name (A-Z)", Comparator.comparing(Claim::name)));
    }

    @Override
    protected Button renderEntry(Claim claim) {
        boolean owned = claim.isOwner(viewer.getUniqueId());

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Size:</gray> <white>" + claim.width() + " x " + claim.depth()
                + "</white> <dark_gray>(" + claim.area() + " blocks)</dark_gray>");
        lore.add("<gray>World:</gray> <white>" + claim.worldName() + "</white>");
        lore.add("<gray>At:</gray> <white>" + claim.minX() + ", " + claim.minZ() + "</white>");
        if (owned) {
            lore.add("<gray>Members:</gray> <white>" + claim.members().size() + "</white>");
        } else {
            lore.add("<gray>Owner:</gray> <white>"
                    + org.bukkit.Bukkit.getOfflinePlayer(claim.owner()).getName() + "</white>");
            lore.add("<gray>Your trust:</gray> <aqua>"
                    + claim.trustOf(viewer.getUniqueId()).displayName() + "</aqua>");
        }
        lore.add("");
        lore.add("<gray>Click to manage.</gray>");

        return Button.of(ItemBuilder.of(owned ? Material.GRASS_BLOCK : Material.DIRT)
                .name("<white>" + claim.name() + "</white>")
                .lore(lore)
                .clean().build(), click -> {
            ClaimMenu menu = new ClaimMenu(menus, chatInput, click.player(), claims, visualiser,
                    economy, notifications, lookup, scheduling, claim.id());
            menu.withParent(this);
            menu.open();
        });
    }

    @Override
    protected void decorate() {
        if (loaded.isEmpty()) {
            set(22, Button.display(ItemBuilder.of(Material.BARRIER)
                    .name("<red>No claims yet</red>")
                    .lore("<gray>Use <white>/claim 16</white> to buy one</gray>",
                            "<gray>around where you are standing.</gray>",
                            "",
                            "<gray>Price:</gray> <white>"
                                    + economy.money().format(claims.settings().pricePerBlock())
                                    + " per block</white>")
                    .clean().build()));
        }

        set(rows() - 1, 1, Button.display(ItemBuilder.of(Material.GOLD_NUGGET)
                .name("<gold>Claim pricing</gold>")
                .lore("<gray>New land:</gray> <white>"
                                + economy.money().format(claims.settings().pricePerBlock())
                                + " per block</white>",
                        "<gray>Expansion:</gray> <white>"
                                + economy.money().format(claims.settings().expansionPricePerBlock())
                                + " per block</white>",
                        "<gray>Refund on delete:</gray> <white>"
                                + claims.settings().deleteRefundPercent() + "%</white>")
                .clean().build()));
    }
}
