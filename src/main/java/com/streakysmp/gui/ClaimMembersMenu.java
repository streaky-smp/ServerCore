package com.streakysmp.gui;

import com.streakysmp.claim.Claim;
import com.streakysmp.claim.ClaimService;
import com.streakysmp.claim.TrustLevel;
import com.streakysmp.command.PlayerLookup;
import com.streakysmp.core.Scheduling;
import com.streakysmp.notify.Messages;
import com.streakysmp.notify.NotificationService;
import com.streakysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The trusted players on a claim.
 *
 * <p>Adding someone is done through a chat prompt for their name rather than a
 * player-picker grid: a picker can only offer players who are online, and trusting
 * an offline friend is the more common case.
 */
public final class ClaimMembersMenu extends Menu {

    private static final int[] MEMBER_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25
    };

    private final ClaimService claims;
    private final NotificationService notifications;
    private final PlayerLookup lookup;
    private final Scheduling scheduling;
    private final ChatInput chatInput;
    private final String claimId;

    public ClaimMembersMenu(MenuManager menus,
                            ChatInput chatInput,
                            Player viewer,
                            ClaimService claims,
                            NotificationService notifications,
                            PlayerLookup lookup,
                            Scheduling scheduling,
                            String claimId) {
        super(menus, viewer, Text.mm("<dark_gray>Claim Members</dark_gray>"), 4);
        this.chatInput = chatInput;
        this.claims = claims;
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
                    .name("<red>This claim no longer exists</red>").clean().build()));
            navigation();
            return;
        }

        List<Map.Entry<UUID, TrustLevel>> members = new ArrayList<>(claim.members().entrySet());
        members.sort((a, b) -> Integer.compare(b.getValue().rank(), a.getValue().rank()));

        if (members.isEmpty()) {
            set(1, 4, Button.display(ItemBuilder.of(Material.BARRIER)
                    .name("<gray>No trusted players</gray>")
                    .lore("<gray>Only you can build here.</gray>")
                    .clean().build()));
        }

        for (int i = 0; i < members.size() && i < MEMBER_SLOTS.length; i++) {
            set(MEMBER_SLOTS[i], memberButton(members.get(i)));
        }

        set(3, 4, Button.of(ItemBuilder.of(Material.EMERALD)
                .name("<green>Trust a player</green>")
                .lore("<gray>Click and type their name in chat.</gray>",
                        "<gray>They do not need to be online.</gray>")
                .clean().build(), click -> promptForMember(click.player())));

        navigation();
    }

    private Button memberButton(Map.Entry<UUID, TrustLevel> member) {
        UUID id = member.getKey();
        TrustLevel trust = member.getValue();
        String name = ClaimMemberMenu.nameOf(id);

        // A plain click into a dedicated screen, rather than cycling on left and
        // removing on right. A Bedrock touchscreen cannot reliably produce a
        // right-click, which previously made removal impossible for those players.
        return Button.of(ItemBuilder.head(id)
                .name("<white>" + Text.escape(name) + "</white>")
                .lore("<gray>Trust:</gray> <aqua>" + trust.displayName() + "</aqua>",
                        "<dark_gray>" + trust.description() + "</dark_gray>",
                        "",
                        "<gray>Click to change their level</gray>",
                        "<gray>or remove them.</gray>")
                .clean().build(), click -> {
            ClaimMemberMenu menu = new ClaimMemberMenu(menus, click.player(), claims,
                    notifications, scheduling, claimId, id);
            menu.withParent(this);
            menu.open();
        });
    }

    private void promptForMember(Player player) {
        closeLater();
        chatInput.prompt(player,
                "<aqua>Type the name of the player to trust, or <white>cancel</white>.</aqua>",
                input -> scheduling.thenSync(lookup.resolve(input), resolved -> {
                    if (resolved.isEmpty()) {
                        notifications.error(player, "error.unknown-player",
                                Messages.of("name", input));
                        open();
                        return;
                    }
                    var target = resolved.get();
                    scheduling.thenSync(
                            scheduling.supplyAsync(() -> claims.setMember(player.getUniqueId(),
                                    claimId, target.uuid(), TrustLevel.BUILD)),
                            result -> {
                                if (result.isSuccess()) {
                                    notifications.success(player, "claim.trusted", Messages.of(
                                            "name", Text.escape(target.name()),
                                            "level", TrustLevel.BUILD.displayName()));
                                } else {
                                    notifications.error(player, result.messageKey(), Messages.of());
                                }
                                open();
                            },
                            error -> {
                                notifications.error(player, "error.internal", Messages.of());
                                open();
                            });
                }, error -> {
                    notifications.error(player, "error.internal", Messages.of());
                    open();
                }),
                this::open);
    }

    private void navigation() {
        if (hasParent()) {
            set(3, 0, Button.of(ItemBuilder.of(Material.ARROW)
                    .name("<green>Back</green>").clean().build(), click -> parent().open()));
        }
        set(3, 8, Button.of(ItemBuilder.of(Material.BARRIER)
                .name("<red>Close</red>").clean().build(), ClickContext::close));
    }
}
