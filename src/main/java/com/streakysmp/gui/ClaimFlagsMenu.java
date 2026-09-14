package com.streakysmp.gui;

import com.streakysmp.claim.Claim;
import com.streakysmp.claim.ClaimFlag;
import com.streakysmp.claim.ClaimService;
import com.streakysmp.core.Scheduling;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-claim permission flags.
 *
 * <p>Each flag has three states, cycled by clicking: default, public, and
 * private. "Default" is shown explicitly rather than folded into one of the other
 * two, because an operator changing a flag's default should affect claims that
 * never overrode it -- and a claim owner should be able to see which of their
 * settings are their own choices.
 */
public final class ClaimFlagsMenu extends Menu {

    /** Interior slots, avoiding the border and the bottom row. */
    private static final int[] FLAG_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    private final ClaimService claims;
    private final NotificationService notifications;
    private final Scheduling scheduling;
    private final String claimId;

    public ClaimFlagsMenu(MenuManager menus,
                          Player viewer,
                          ClaimService claims,
                          NotificationService notifications,
                          Scheduling scheduling,
                          String claimId) {
        super(menus, viewer, Text.mm("<dark_gray>Claim Permissions</dark_gray>"), 6);
        this.claims = claims;
        this.notifications = notifications;
        this.scheduling = scheduling;
        this.claimId = claimId;
    }

    @Override
    protected void build() {
        fillBorder(Material.GRAY_STAINED_GLASS_PANE);

        Claim claim = claims.byId(claimId).orElse(null);
        if (claim == null) {
            set(22, Button.display(ItemBuilder.of(Material.BARRIER)
                    .name("<red>This claim no longer exists</red>").clean().build()));
            navigation();
            return;
        }

        ClaimFlag[] flags = ClaimFlag.values();
        for (int i = 0; i < flags.length && i < FLAG_SLOTS.length; i++) {
            set(FLAG_SLOTS[i], flagButton(claim, flags[i]));
        }
        navigation();
    }

    private Button flagButton(Claim claim, ClaimFlag flag) {
        Boolean override = claim.publicFlags().get(flag);
        boolean effective = claim.isPublic(flag);

        String state;
        Material icon;
        if (override == null) {
            state = "<gray>Default</gray> <dark_gray>("
                    + (flag.publicByDefault() ? "public" : "trusted only") + ")</dark_gray>";
            icon = Material.LIGHT_GRAY_DYE;
        } else if (override) {
            state = "<green>Public</green> <dark_gray>(anyone)</dark_gray>";
            icon = Material.LIME_DYE;
        } else {
            state = "<red>Private</red> <dark_gray>(trusted only)</dark_gray>";
            icon = Material.RED_DYE;
        }

        List<String> lore = new ArrayList<>();
        lore.add("<gray>State:</gray> " + state);
        lore.add("<gray>Needs trust:</gray> <white>"
                + flag.requiredTrust().displayName() + "</white> <dark_gray>when private</dark_gray>");
        lore.add("<gray>Right now:</gray> "
                + (effective ? "<green>anyone may</green>" : "<red>trusted only</red>"));
        lore.add("");
        lore.add("<gray>Click to cycle:</gray>");
        lore.add("<dark_gray>default > public > private</dark_gray>");

        return Button.of(ItemBuilder.of(icon)
                .name("<white>" + flag.displayName() + "</white>")
                .lore(lore)
                .clean().build(), click -> {
            Boolean next = cycle(override);
            scheduling.thenSync(
                    scheduling.supplyAsync(() -> claims.setFlag(
                            click.player().getUniqueId(), claimId, flag, next)),
                    result -> {
                        if (!result.isSuccess()) {
                            notifications.error(click.player(), result.messageKey(), Messages.of());
                        }
                        click.refresh();
                    },
                    error -> notifications.error(click.player(), "error.internal", Messages.of()));
        });
    }

    /** default (null) to public (true) to private (false) and back. */
    private static Boolean cycle(Boolean current) {
        if (current == null) {
            return Boolean.TRUE;
        }
        return current ? Boolean.FALSE : null;
    }

    private void navigation() {
        if (hasParent()) {
            set(rows() - 1, 0, Button.of(ItemBuilder.of(Material.ARROW)
                    .name("<green>Back</green>").clean().build(), click -> parent().open()));
        }
        set(rows() - 1, 8, Button.of(ItemBuilder.of(Material.BARRIER)
                .name("<red>Close</red>").clean().build(), ClickContext::close));
    }
}
