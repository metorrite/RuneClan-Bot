package com.runeclans.bot.commands.teamforming;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import com.runeclans.bot.internal.MemberApi;
import com.runeclans.bot.branding.BrandingService;
import com.runeclans.bot.config.BotConfig;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.requests.restaction.RoleAction;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the teamforming panels and resolves interactions to actual Discord role assignments.
 * <p>
 * There are two messages:
 * <ul>
 *     <li>The <b>public panel</b> ({@link #buildPublicPanelMessage(Guild)}), posted once by an admin via
 *     {@code /teamforming post}. It's the same for everyone and just has an entry-point button —
 *     it can't show per-viewer state (see below).</li>
 *     <li>The <b>personal panel</b> ({@link #buildPersonalPanelComponents}), opened fresh (ephemeral,
 *     so only its viewer can see it) every time a member clicks that button. Every dropdown's
 *     checkmarks and the Monthly Mass button's color reflect that specific member's actual current
 *     roles, because — unlike the public panel — an ephemeral message <i>is</i> personalizable.</li>
 * </ul>
 * <b>Why two messages:</b> Discord does not support per-viewer default/checked values on a shared,
 * persistent message's components — a public message looks identical to every viewer, always. An
 * ephemeral reply has no such restriction, since it's generated fresh for one specific viewer each
 * time. Building the personal panel this way is what makes accurate checkmarks (and an accurately
 * colored Monthly Mass button) possible at all.
 * <p>
 * Editing a selection or the Monthly Mass button inside the personal panel stages the change and
 * re-renders that same message in place (no new messages); clicking <b>Update Roles</b> diffs the
 * final on-screen state against the member's live roles and applies exactly that difference in one
 * {@code modifyMemberRoles} call. The only in-memory state is that staging area
 * ({@link #pendingByUserId}) — losing it on a restart just means re-picking before applying, it can
 * never affect an already-applied role, since actual role assignments are never cached; they're
 * always read live from Discord at the moment a panel is opened or applied.
 */
@BService
public class TeamformingService {
    private static final Logger log = LoggerFactory.getLogger(TeamformingService.class);

    static final Color PANEL_ACCENT_COLOR = new Color(0xB3, 0x00, 0x00); // dark red, matches the clan logo
    private static final String LOGO_RESOURCE = "images/clan_logo.png";
    private static final String LOGO_FILENAME = "clan_logo.png";

    public static final String OPEN_PANEL_BUTTON_ID = "teamforming_open";
    public static final String TOGGLE_PREFIX = "teamforming_toggle:";
    public static final String SELECT_PREFIX = "teamforming_select:";
    public static final String UPDATE_ROLES_BUTTON_ID = "teamforming_update_roles";

    private final BrandingService branding;
    private final BotConfig botConfig;

    public TeamformingService(BrandingService branding, BotConfig botConfig) {
        this.branding = branding;
        this.botConfig = botConfig;
    }

    /** Per-user staged selections for the personal panel, applied together on "Update Roles". */
    private final Map<Long, PendingChanges> pendingByUserId = new ConcurrentHashMap<>();

    private static final class PendingChanges {
        // sectionKey -> the exact set of role names checked for that section, last time it was submitted.
        // A section absent here hasn't been touched this session, so it's read from live roles instead.
        final Map<String, Set<String>> sectionSelections = new ConcurrentHashMap<>();
        volatile Boolean monthlyMassDesired; // null = unchanged from live state
    }

    /** What "Update Roles" actually changed, as resolved {@link Role}s for mention-friendly replies. */
    public record BatchResult(List<Role> added, List<Role> removed) {
        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty();
        }
    }

    // --- Public panel (posted once by an admin) ---

    public MessageCreateData buildPublicPanelMessage(Guild guild) {
        List<ContainerChildComponent> children = new ArrayList<>();

        children.add(buildHeader(guild));

        StringBuilder categories = new StringBuilder();
        for (TeamformingSection section : TeamformingCatalog.SECTIONS) {
            if (!categories.isEmpty()) categories.append("   ");
            categories.append(section.emoji()).append(" ").append(section.title());
        }
        children.add(TextDisplay.of(categories.toString()));

        children.add(ActionRow.of(Button.primary(OPEN_PANEL_BUTTON_ID, "🎯 Manage My Teamforming Tags")));
        children.add(TextDisplay.of("-# Click above to see your current tags and update them."));

        Container container = Container.of(children).withAccentColor(PANEL_ACCENT_COLOR);

        return new MessageCreateBuilder()
                .useComponentsV2()
                .setComponents(container)
                .build();
    }

    /**
     * Header block: clan logo thumbnail (if the resource is present) next to the title/blurb.
     * <p>
     * Note: Discord's {@link Section} component is fixed as "content on the left, accessory on
     * the right" — there's no supported way to put the thumbnail on the left instead.
     */
    private ContainerChildComponent buildHeader(Guild guild) {
        TextDisplay headerText = TextDisplay.of(
                "### " + branding.displayName(guild) + " Teamforming\nClick the button below to assign yourself boss event roles!");

        // The bundled picture is the home clan's logo, so it is only used in the home server; every other server shows its own icon.
        Long home = botConfig.getGuildId();
        if (home == null || home == guild.getIdLong()) {
            FileUpload logo = loadLogo();
            if (logo != null) return Section.of(Thumbnail.fromFile(logo).withDescription("Clan logo"), headerText);
        }
        String icon = guild.getIconUrl();
        if (icon == null) return headerText;
        return Section.of(Thumbnail.fromUrl(icon).withDescription("Server icon"), headerText);
    }

    private FileUpload loadLogo() {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(LOGO_RESOURCE)) {
            if (stream == null) {
                log.warn("Teamforming logo resource '{}' not found on the classpath; posting panel without a thumbnail.",
                        LOGO_RESOURCE);
                return null;
            }
            return FileUpload.fromData(stream.readAllBytes(), LOGO_FILENAME);
        } catch (IOException e) {
            log.warn("Failed to load teamforming logo resource '{}'; posting panel without a thumbnail.",
                    LOGO_RESOURCE, e);
            return null;
        }
    }

    // --- Personal panel (ephemeral, rebuilt for/by whoever clicks "Manage My Teamforming Tags") ---

    /**
     * Builds the personal panel's components for this specific member: every dropdown defaults to
     * exactly what they currently hold (blended with anything they've already changed this
     * session — see {@link #effectiveSectionSelection}), and the Monthly Mass button is colored
     * to match its effective state.
     */
    public List<ContainerChildComponent> buildPersonalPanelComponents(Member member) {
        List<ContainerChildComponent> children = new ArrayList<>();

        children.add(TextDisplay.of("### Your Teamforming Tags\nCheck or uncheck tags below, then click **Update Roles**."));
        children.add(ActionRow.of(buildMonthlyMassButton(member)));
        children.add(Separator.createDivider(Separator.Spacing.LARGE));

        for (int i = 0; i < TeamformingCatalog.SECTIONS.size(); i++) {
            TeamformingSection section = TeamformingCatalog.SECTIONS.get(i);
            children.add(TextDisplay.of("**" + section.emoji() + " " + section.title() + "**\n" + section.prompt()));
            children.add(ActionRow.of(buildPersonalSelectMenu(member, section)));
            if (i < TeamformingCatalog.SECTIONS.size() - 1) {
                children.add(Separator.createDivider(Separator.Spacing.LARGE));
            }
        }

        children.add(ActionRow.of(Button.success(UPDATE_ROLES_BUTTON_ID, "✅ Update Roles")));

        return children;
    }

    private Button buildMonthlyMassButton(Member member) {
        TeamformingToggle toggle = TeamformingCatalog.TOGGLES.getFirst();
        String label = toggle.emoji() + " " + toggle.label();
        String customId = TOGGLE_PREFIX + toggle.roleName();

        return effectiveMonthlyMass(member)
                ? Button.success(customId, label)
                : Button.secondary(customId, label);
    }

    private StringSelectMenu buildPersonalSelectMenu(Member member, TeamformingSection section) {
        Set<String> defaults = effectiveSectionSelection(member, section);

        StringSelectMenu.Builder builder = StringSelectMenu.create(SELECT_PREFIX + section.key())
                .setPlaceholder(section.selectPlaceholder())
                .setRequiredRange(0, section.options().size());

        for (TeamformingOption option : section.options()) {
            builder.addOption(option.label(), option.roleName(), option.description());
        }
        if (!defaults.isEmpty()) {
            builder.setDefaultValues(defaults);
        }

        return builder.build();
    }

    /** This section's role names the member currently holds, live from Discord — no caching. */
    private Set<String> liveSectionSelection(Member member, TeamformingSection section) {
        Set<String> optionRoleNames = new HashSet<>();
        for (TeamformingOption option : section.options()) optionRoleNames.add(option.roleName());

        Set<String> held = new LinkedHashSet<>();
        for (Role role : member.getRoles()) {
            if (optionRoleNames.contains(role.getName())) held.add(role.getName());
        }
        return held;
    }

    /** What a section's dropdown should show as checked right now: staged pick if touched this session, else live roles. */
    private Set<String> effectiveSectionSelection(Member member, TeamformingSection section) {
        PendingChanges pending = pendingByUserId.get(member.getIdLong());
        if (pending != null) {
            Set<String> staged = pending.sectionSelections.get(section.key());
            if (staged != null) return staged;
        }
        return liveSectionSelection(member, section);
    }

    /** Whether the Monthly Mass button should render as "on" right now: staged toggle if set, else live role. */
    private boolean effectiveMonthlyMass(Member member) {
        PendingChanges pending = pendingByUserId.get(member.getIdLong());
        if (pending != null && pending.monthlyMassDesired != null) return pending.monthlyMassDesired;

        String roleName = TeamformingCatalog.TOGGLES.getFirst().roleName();
        for (Role role : member.getRoles()) {
            if (role.getName().equals(roleName)) return true;
        }
        return false;
    }

    /** Stages a section's dropdown submission — replaces (not merges with) any earlier pick for that section this session. */
    public void stageSectionSelection(long userId, String sectionKey, List<String> selectedRoleNames) {
        pendingByUserId.computeIfAbsent(userId, id -> new PendingChanges())
                .sectionSelections.put(sectionKey, new LinkedHashSet<>(selectedRoleNames));
    }

    /** Stages the Monthly Mass button's new desired on/off state. */
    public void stageMonthlyMass(long userId, boolean desired) {
        pendingByUserId.computeIfAbsent(userId, id -> new PendingChanges()).monthlyMassDesired = desired;
    }

    /**
     * Applies (and clears) everything currently shown as checked/on in this member's personal
     * panel: diffs the effective state of every section plus Monthly Mass against their live
     * roles, and applies exactly the difference in one {@code modifyMemberRoles} call.
     */
    public BatchResult applyPersonalPanel(Guild guild, Member member) {
        List<Role> toAdd = new ArrayList<>();
        List<Role> toRemove = new ArrayList<>();

        for (TeamformingSection section : TeamformingCatalog.SECTIONS) {
            Set<String> desired = effectiveSectionSelection(member, section);
            Set<String> live = liveSectionSelection(member, section);

            for (TeamformingOption option : section.options()) {
                String roleName = option.roleName();
                boolean wants = desired.contains(roleName);
                boolean has = live.contains(roleName);

                if (wants && !has) {
                    toAdd.add(ensureRole(guild, roleName));
                } else if (!wants && has) {
                    guild.getRolesByName(roleName, true).stream().findFirst().ifPresent(toRemove::add);
                }
            }
        }

        TeamformingToggle monthlyMass = TeamformingCatalog.TOGGLES.getFirst();
        boolean wantsMonthlyMass = effectiveMonthlyMass(member);
        boolean hasMonthlyMass = member.getRoles().stream().anyMatch(r -> r.getName().equals(monthlyMass.roleName()));
        if (wantsMonthlyMass && !hasMonthlyMass) {
            toAdd.add(ensureRole(guild, monthlyMass.roleName()));
        } else if (!wantsMonthlyMass && hasMonthlyMass) {
            guild.getRolesByName(monthlyMass.roleName(), true).stream().findFirst().ifPresent(toRemove::add);
        }

        // an existing role with the option's name is reused, so it must pass the shared hand-out check (B-03)
        toAdd.removeIf(role -> !MemberApi.safeToAssign(guild, role));
        toRemove.removeIf(role -> !MemberApi.safeToAssign(guild, role));
        if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
            guild.modifyMemberRoles(member, toAdd, toRemove).complete();
        }

        pendingByUserId.remove(member.getIdLong());
        return new BatchResult(toAdd, toRemove);
    }

    // --- Role management ---

    /**
     * Finds an existing role by exact (case-insensitive) name, creating it (with its catalog
     * color) if none exists. If it already exists but its color doesn't match the catalog, its
     * color is updated to match — so re-running {@code /teamforming post} after a color change in
     * the catalog fixes already-created roles too.
     */
    public Role ensureRole(Guild guild, String roleName) {
        Color catalogColor = TeamformingCatalog.colorForRoleName(roleName);

        List<Role> existing = guild.getRolesByName(roleName, true);
        if (!existing.isEmpty()) {
            Role role = existing.getFirst();
            if (catalogColor != null) {
                int currentRgb = role.getColors().getPrimaryRaw() & 0xFFFFFF;
                int catalogRgb = catalogColor.getRGB() & 0xFFFFFF;
                if (currentRgb != catalogRgb) {
                    role.getManager().setColor(catalogColor).complete();
                }
            }
            return role;
        }

        RoleAction action = guild.createRole().setName(roleName).setMentionable(true);
        if (catalogColor != null) {
            action = action.setColor(catalogColor);
        }

        Role created = action.complete();
        log.info("Created teamforming role '{}' in guild {}", roleName, guild.getIdLong());
        return created;
    }

    /**
     * Ensures every role in the teamforming catalog exists (and is colored correctly) in the
     * guild, creating any that are missing. Meant to be run once from {@code /teamforming post},
     * so every dropdown/button works immediately once the panel is live.
     *
     * @return the names of roles that were newly created (empty if they all already existed)
     */
    public List<String> ensureAllRolesExist(Guild guild) {
        List<String> created = new ArrayList<>();
        for (String roleName : TeamformingCatalog.allRoleNames()) {
            boolean existed = !guild.getRolesByName(roleName, true).isEmpty();
            try {
                ensureRole(guild, roleName);
                if (!existed) created.add(roleName);
            } catch (Exception e) {
                log.warn("Failed to create/color teamforming role '{}' in guild {}", roleName, guild.getIdLong(), e);
            }
        }
        return created;
    }

    /**
     * Deletes every role in the teamforming catalog that currently exists in the guild. Intended
     * for cleaning up a dev/test bot's server after testing (the dev bot targets the same live
     * Discord server, since there's no separate test server) — run this before switching back to
     * the production bot so the same role names can be created cleanly there.
     *
     * @return the names of roles that were actually found and deleted
     */
    public List<String> deleteAllCatalogRoles(Guild guild) {
        List<String> deleted = new ArrayList<>();
        for (String roleName : TeamformingCatalog.allRoleNames()) {
            for (Role role : guild.getRolesByName(roleName, true)) {
                try {
                    role.delete().complete();
                    deleted.add(roleName);
                    log.info("Deleted teamforming role '{}' from guild {}", roleName, guild.getIdLong());
                } catch (Exception e) {
                    log.warn("Failed to delete teamforming role '{}' from guild {}", roleName, guild.getIdLong(), e);
                }
            }
        }
        return deleted;
    }
}
