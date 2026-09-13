package com.servercore.gui;

import com.servercore.claim.Claim;
import com.servercore.claim.ClaimResult;
import com.servercore.claim.ClaimService;
import com.servercore.claim.TrustLevel;
import com.servercore.core.Scheduling;
import com.servercore.notify.Messages;
import com.servercore.notify.NotificationService;
import com.servercore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One member's trust level on a claim.
 *
 * <p>Exists because removing a member was previously a right-click, which a
 * Bedrock touchscreen cannot reliably produce -- so a Bedrock claim owner could
 * add trusted players but never remove one.
 *
 * <p>Each trust level is its own labelled button rather than a click-to-cycle,
 * which also removes the need to guess what the next level in the cycle is.
 */
public final class ClaimMemberMenu extends Menu {

    /** Slots for the four trust levels, left to right. */
    private static final int[] LEVEL_SLOTS = {10, 12, 14, 16};

    private final ClaimService claims;
    private final NotificationService notifications;
    private final Scheduling scheduling;
    private final String claimId;
    private final UUID member;

    private Claim claim;

    public ClaimMemberMenu(MenuManager menus,
                           Player viewer,
                           ClaimService claims,
                           NotificationService notifications,
                           Scheduling scheduling,
                           String claimId,
                           UUID member) {
        super(menus, viewer, Text.mm("<dark_gray>Member</dark_gray>"), 4);
        this.claims = claims;
        this.notifications = notifications;
        this.scheduling = scheduling;
        this.claimId = claimId;
        this.member = member;
    }

    @Override
    public void open() {
        claim = claims.byId(claimId).orElse(null);
        if (claim == null) {
            viewer.sendMessage(Text.mm("<red>That claim no longer exists.</red>"));
            return;
        }
        super.open();
    }

    @Override
    protected void build() {
        fillEmpty(Material.GRAY_STAINED_GLASS_PANE);

        TrustLevel current = claim.trustOf(member);
        String name = nameOf(member);

        set(0, 4, Button.display(ItemBuilder.head(member)
                .name("<white><bold>" + name + "</bold></white>")
                .lore("<gray>Claim:</gray> <white>" + claim.name() + "</white>",
                        "<gray>Current trust:</gray> " + (current == null
                                ? "<red>none</red>"
                                : "<aqua>" + current.displayName() + "</aqua>"))
                .clean().build()));

        TrustLevel[] levels = TrustLevel.values();
        for (int i = 0; i < levels.length && i < LEVEL_SLOTS.length; i++) {
            set(LEVEL_SLOTS[i], levelButton(levels[i], current, name));
        }

        set(2, 4, Button.of(ItemBuilder.of(Material.BARRIER)
                .name("<red><bold>Remove from claim</bold></red>")
                .lore("<gray>Revokes all access for</gray> <white>" + name + "</white><gray>.</gray>",
                        "<gray>Click to remove.</gray>")
                .clean().build(), click -> apply(click,
                () -> claims.removeMember(click.player().getUniqueId(), claimId, member),
                "claim.untrusted", name, true)));

        if (hasParent()) {
            set(3, 0, Button.of(ItemBuilder.of(Material.ARROW)
                    .name("<green>Back</green>").clean().build(), click -> parent().open()));
        }
        set(3, 8, Button.of(ItemBuilder.of(Material.BARRIER)
                .name("<red>Close</red>").clean().build(), ClickContext::close));
    }

    private Button levelButton(TrustLevel level, TrustLevel current, String name) {
        boolean active = level == current;

        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>" + level.description() + "</dark_gray>");
        lore.add("");
        // Spell out what each level includes, so the hierarchy is visible rather
        // than something the player has to infer.
        for (TrustLevel included : TrustLevel.values()) {
            if (level.atLeast(included)) {
                lore.add("<green>+</green> <gray>" + included.description() + "</gray>");
            }
        }
        lore.add("");
        lore.add(active ? "<aqua>This is their current level.</aqua>" : "<gray>Click to set.</gray>");

        return Button.of(ItemBuilder.of(active ? Material.LIME_CONCRETE : Material.LIGHT_GRAY_CONCRETE)
                .name((active ? "<green>" : "<white>") + level.displayName()
                        + (active ? "</green>" : "</white>"))
                .lore(lore)
                .glow(active)
                .clean().build(), click -> {
            if (active) {
                return;
            }
            apply(click, () -> claims.setMember(click.player().getUniqueId(), claimId, member, level),
                    "claim.trusted", name, false);
        });
    }

    private void apply(ClickContext click,
                       java.util.function.Supplier<ClaimResult> action,
                       String successKey,
                       String name,
                       boolean returnToParent) {
        scheduling.thenSync(
                scheduling.supplyAsync(action),
                result -> {
                    if (result.isSuccess()) {
                        notifications.success(click.player(), successKey, Messages.of(
                                "name", Text.escape(name),
                                "level", result.claim() == null ? "updated"
                                        : String.valueOf(describeTrust(result.claim()))));
                        if (returnToParent && hasParent()) {
                            parent().open();
                            return;
                        }
                    } else {
                        notifications.error(click.player(), result.messageKey(), Messages.of());
                    }
                    open();
                },
                error -> notifications.error(click.player(), "error.internal", Messages.of()));
    }

    private String describeTrust(Claim updated) {
        TrustLevel level = updated.trustOf(member);
        return level == null ? "removed" : level.displayName();
    }

    static String nameOf(UUID id) {
        Player online = Bukkit.getPlayer(id);
        String name = online != null ? online.getName() : Bukkit.getOfflinePlayer(id).getName();
        return name == null ? id.toString().substring(0, 8) : name;
    }
}
