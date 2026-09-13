package com.servercore.gui;

import com.servercore.economy.EconomyService;
import com.servercore.statistics.ProfileSnapshot;
import com.servercore.statistics.StatisticType;
import com.servercore.util.Durations;
import com.servercore.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A player's profile: balance, combat record, playtime and rankings.
 *
 * <p>Rendered entirely from a {@link ProfileSnapshot} captured before opening, so
 * drawing touches no database.
 */
public final class ProfileMenu extends Menu {

    private final EconomyService economy;
    private final ProfileSnapshot snapshot;
    private final boolean ownProfile;

    public ProfileMenu(MenuManager menus,
                       Player viewer,
                       EconomyService economy,
                       ProfileSnapshot snapshot) {
        super(menus, viewer, Text.mm("<dark_gray>Profile</dark_gray>"), 3);
        this.economy = economy;
        this.snapshot = snapshot;
        this.ownProfile = viewer.getUniqueId().equals(snapshot.uuid());
    }

    @Override
    protected void build() {
        fillEmpty(Material.GRAY_STAINED_GLASS_PANE);

        set(1, 4, Button.display(ItemBuilder.head(snapshot.uuid())
                .name("<white><bold>" + Text.escape(snapshot.name()) + "</bold></white>")
                .lore(headerLore())
                .clean().build()));

        set(1, 1, Button.display(ItemBuilder.of(Material.GOLD_INGOT)
                .name("<gold>Balance</gold>")
                .lore(rankedLore(economy.money().format(snapshot.balance()), snapshot.balanceRank()))
                .clean().build()));

        set(1, 2, Button.display(ItemBuilder.of(Material.CLOCK)
                .name("<aqua>Playtime</aqua>")
                .lore(rankedLore(
                        Durations.playtime(Duration.ofSeconds(snapshot.playtimeSeconds()), false),
                        snapshot.rank(StatisticType.PLAYTIME)))
                .clean().build()));

        set(1, 6, Button.display(ItemBuilder.of(Material.IRON_SWORD)
                .name("<red>Combat</red>")
                .lore(combatLore())
                .clean().build()));

        set(1, 7, Button.display(ItemBuilder.of(Material.ZOMBIE_HEAD)
                .name("<dark_green>Mobs killed</dark_green>")
                .lore("<white>" + snapshot.statistic(StatisticType.MOBS_KILLED) + "</white>")
                .clean().build()));

        if (hasParent()) {
            set(2, 0, Button.of(ItemBuilder.of(Material.ARROW)
                    .name("<green>Back</green>")
                    .clean().build(), click -> parent().open()));
        }
        set(2, 8, Button.of(ItemBuilder.of(Material.BARRIER)
                .name("<red>Close</red>")
                .clean().build(), ClickContext::close));
    }

    private List<String> headerLore() {
        List<String> lore = new ArrayList<>();
        lore.add(ownProfile ? "<dark_gray>This is you.</dark_gray>" : "<dark_gray>Player profile</dark_gray>");
        lore.add("");
        lore.add("<gray>Balance:</gray> <green>" + economy.money().format(snapshot.balance()) + "</green>");
        lore.add("<gray>Playtime:</gray> <white>"
                + Durations.playtime(Duration.ofSeconds(snapshot.playtimeSeconds()), false) + "</white>");
        lore.add("<gray>Kills:</gray> <white>" + snapshot.statistic(StatisticType.KILLS) + "</white>"
                + " <dark_gray>/</dark_gray> <gray>Deaths:</gray> <white>"
                + snapshot.statistic(StatisticType.DEATHS) + "</white>");
        return lore;
    }

    private List<String> combatLore() {
        long kills = snapshot.statistic(StatisticType.KILLS);
        long deaths = snapshot.statistic(StatisticType.DEATHS);

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Kills:</gray> <white>" + kills + "</white>"
                + rankSuffix(snapshot.rank(StatisticType.KILLS)));
        lore.add("<gray>Deaths:</gray> <white>" + deaths + "</white>"
                + rankSuffix(snapshot.rank(StatisticType.DEATHS)));
        lore.add("<gray>K/D:</gray> <white>"
                + String.format(java.util.Locale.ROOT, "%.2f", snapshot.killDeathRatio()) + "</white>");
        if (deaths == 0 && kills > 0) {
            lore.add("<dark_gray>No deaths yet, so K/D equals kills.</dark_gray>");
        }
        return lore;
    }

    private static List<String> rankedLore(String value, int rank) {
        List<String> lore = new ArrayList<>();
        lore.add("<white>" + value + "</white>");
        if (rank > 0) {
            lore.add("<dark_gray>Rank #" + rank + "</dark_gray>");
        }
        return lore;
    }

    private static String rankSuffix(int rank) {
        return rank > 0 ? " <dark_gray>(#" + rank + ")</dark_gray>" : "";
    }
}
