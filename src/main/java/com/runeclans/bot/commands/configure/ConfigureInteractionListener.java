package com.runeclans.bot.commands.configure;

import com.runeclans.bot.internal.MemberApi;
import com.runeclans.bot.announcement.AnnouncementRepository;
import com.runeclans.bot.commands.embed.EmbedService;
import com.runeclans.bot.configure.WebsiteLink;
import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.discord.DiscordLinks;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.permission.PermissionGroup;
import com.runeclans.bot.permission.PermissionGroupService;
import com.runeclans.bot.runescape.ClanVerificationService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Buttons/modal for {@link ConfigureCommand}'s panel. Only "Clan" and "Verification" sections exist
 * today — more sections (Coffer, Events, whatever) would each just be another button here and
 * another sub-panel, but nothing speculative is built ahead of an actual need.
 * <p>
 * Gated by Discord's native Administrator permission, same as {@link ConfigureCommand} — see that
 * class for why this doesn't use {@code AdminRoleFilter}.
 */
@BService
public class ConfigureInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(ConfigureInteractionListener.class);

    private final GuildSettingsService settingsService;
    private final ClanVerificationService clanVerificationService;
    private final AnnouncementRepository announcementRepository;
    private final EmbedService embedService;
    private final PermissionGroupService permissionGroups;

    public ConfigureInteractionListener(GuildSettingsService settingsService, ClanVerificationService clanVerificationService,
                                        AnnouncementRepository announcementRepository, EmbedService embedService, PermissionGroupService permissionGroups) {
        this.permissionGroups = permissionGroups;
        this.settingsService = settingsService;
        this.clanVerificationService = clanVerificationService;
        this.announcementRepository = announcementRepository;
        this.embedService = embedService;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("configure_")) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "configure.you_need_the_administrator_permission_to"));
                return;
            }

            switch (id.split(":")[0]) {
                case "configure_clan" -> event.editComponents(List.of(buildClanPanel(guild))).useComponentsV2(true).queue();
                case "configure_clan_name" -> doClanNamePrompt(event);
                case "configure_clan_website" -> doWebsitePrompt(event);
                case "configure_clan_toggle" -> doClanToggle(event, guild);
                case "configure_verification" -> event.editComponents(List.of(buildVerificationPanel(guild))).useComponentsV2(true).queue();
                case "configure_back" -> event.editComponents(List.of(buildMainPanel(guild))).useComponentsV2(true).queue();

                case "configure_rename_channel_link" -> doChannelLinkPrompt(event, "rename_channel");
                case "configure_verification_channel_link" -> doChannelLinkPrompt(event, "verification_channel");
                case "configure_verified_clan_role_link" -> doRoleLinkPrompt(event, "verified_clan_role");
                case "configure_verified_nonclan_role_link" -> doRoleLinkPrompt(event, "verified_nonclan_role");
                case "configure_unverified_role_link" -> doRoleLinkPrompt(event, "unverified_role");
                case "configure_onboarding_role_link" -> doRoleLinkPrompt(event, "onboarding_role");
            }
        } catch (Exception e) {
            log.error("Unhandled exception in configure button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getModalId();
        if (guild == null || member == null || !id.startsWith("configure_")) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "configure.you_need_the_administrator_permission_to"));
                return;
            }
            if (id.equals("configure_clan_name_modal:_")) {
                handleClanNameModal(event, guild);
            } else if (id.equals("configure_clan_website_modal:_")) {
                handleWebsiteModal(event, guild);
            } else if (id.startsWith("configure_channel_link_modal:")) {
                handleChannelLinkModal(event, guild, id.split(":", 2)[1]);
            } else if (id.startsWith("configure_role_link_modal:")) {
                handleRoleLinkModal(event, guild, id.split(":", 2)[1]);
            }
        } catch (Exception e) {
            log.error("Unhandled exception in configure modal interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private static final String NO_ROLE_VALUE = "none";
    private static final Set<String> ROLE_SELECT_IDS = Set.of(
            "configure_verified_clan_role", "configure_verified_nonclan_role",
            "configure_unverified_role", "configure_onboarding_role");

    /** The three Verification-panel role dropdowns — each applies immediately on selection (no separate Save), then re-renders the panel showing the new state. */
    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("configure_")) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "configure.you_need_the_administrator_permission_to"));
                return;
            }

            // Checked before touching event.getValues() at all — this listener isn't the only one
            // that reacts to a "configure_"-prefixed select (TrackingConfigInteractionListener's own
            // dropdowns share the prefix), and their values aren't role IDs, so parsing unconditionally
            // here used to blow up with a NumberFormatException before ever reaching this switch.
            String action = id.split(":")[0];
            if (!ROLE_SELECT_IDS.contains(action)) return;

            Long selected = roleIdOrNull(event.getValues().getFirst());
            String refusal = handOutRefusal(guild, selected);
            if (refusal != null) {
                Containers.replyEphemeral(event, Containers.WARNING, refusal);
                return;
            }
            GuildSettings current = settingsService.getEffective(guild.getIdLong());

            switch (action) {
                case "configure_verified_clan_role" -> settingsService.updateVerificationRoleSettings(
                        guild.getIdLong(), selected, current.verifiedNonClanRoleId(), current.unverifiedRoleId());
                case "configure_verified_nonclan_role" -> settingsService.updateVerificationRoleSettings(
                        guild.getIdLong(), current.verifiedClanRoleId(), selected, current.unverifiedRoleId());
                case "configure_unverified_role" -> settingsService.updateVerificationRoleSettings(
                        guild.getIdLong(), current.verifiedClanRoleId(), current.verifiedNonClanRoleId(), selected);
                case "configure_onboarding_role" -> settingsService.updateOnboardingRole(guild.getIdLong(), selected);
            }

            event.editComponents(List.of(buildVerificationPanel(guild))).useComponentsV2(true).queue();
        } catch (Exception e) {
            log.error("Unhandled exception in configure select interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    /**
     * Why the bot won't hand out the chosen verification or onboarding role, or null (also for no role or a role id the server lacks, which
     * the panel already handles). The same check as the dashboard's Setup page and every other role the bot gives (B-55).
     */
    private static String handOutRefusal(Guild guild, Long roleId) {
        if (roleId == null) return null;
        Role role = guild.getRoleById(roleId);
        return role == null || MemberApi.safeToAssign(guild, role) ? null : MemberApi.handOutRefusal(guild, role);
    }

    private static List<Long> selectedRoleIds(EntitySelectInteractionEvent event) {
        return event.getValues().stream().map(net.dv8tion.jda.api.entities.IMentionable::getIdLong).toList();
    }

    /** The Rename Alert and Verification Review channel pickers — same immediate-apply pattern as the role dropdowns above, just for a channel instead of a role. */
    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("configure_")) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "configure.you_need_the_administrator_permission_to"));
                return;
            }

            Long selectedChannelId = event.getValues().isEmpty() ? null : event.getValues().getFirst().getIdLong();

            switch (id.split(":")[0]) {
                case "configure_rename_channel" -> {
                    settingsService.updateRenameAlertChannel(guild.getIdLong(), selectedChannelId);
                    event.editComponents(List.of(buildVerificationPanel(guild))).useComponentsV2(true).queue();
                }
                case "configure_support_role" -> {
                    permissionGroups.setRoles(guild.getIdLong(), PermissionGroup.SUPPORT, selectedRoleIds(event));
                    event.editComponents(List.of(buildClanPanel(guild))).useComponentsV2(true).queue();
                }
                case "configure_developer_role" -> {
                    permissionGroups.setRoles(guild.getIdLong(), PermissionGroup.DEVELOPER, selectedRoleIds(event));
                    event.editComponents(List.of(buildClanPanel(guild))).useComponentsV2(true).queue();
                }
                case "configure_admin_role" -> {
                    permissionGroups.setRoles(guild.getIdLong(), PermissionGroup.ADMIN, selectedRoleIds(event));
                    event.editComponents(List.of(buildClanPanel(guild))).useComponentsV2(true).queue();
                }
                case "configure_verification_channel" -> {
                    settingsService.updateVerificationSettings(guild.getIdLong(), selectedChannelId);
                    event.editComponents(List.of(buildVerificationPanel(guild))).useComponentsV2(true).queue();
                }
            }
        } catch (Exception e) {
            log.error("Unhandled exception in configure entity select interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private static Long roleIdOrNull(String value) {
        return NO_ROLE_VALUE.equals(value) ? null : Long.parseLong(value);
    }

    /** Fallback for a channel that a native select can't surface (forum posts especially) or that the picker's own permission check hides. */
    private void doChannelLinkPrompt(ButtonInteractionEvent event, String targetField) {
        TextInput linkInput = TextInput.create("link_value", TextInputStyle.SHORT)
                .setPlaceholder("Paste a channel link, or just its ID — leave blank to clear")
                .setRequired(false)
                .build();

        Modal modal = Modal.create("configure_channel_link_modal:" + targetField, "Link a Channel")
                .addComponents(Label.of("Channel Link or ID", linkInput))
                .build();
        event.replyModal(modal).queue();
    }

    /** Fallback for a role beyond the dropdown's first 24, or one the picker otherwise doesn't show. */
    private void doRoleLinkPrompt(ButtonInteractionEvent event, String targetField) {
        TextInput idInput = TextInput.create("link_value", TextInputStyle.SHORT)
                .setPlaceholder("Paste the role's ID — leave blank to clear")
                .setRequired(false)
                .build();

        Modal modal = Modal.create("configure_role_link_modal:" + targetField, "Link a Role")
                .addComponents(Label.of("Role ID", idInput))
                .build();
        event.replyModal(modal).queue();
    }

    private void handleChannelLinkModal(ModalInteractionEvent event, Guild guild, String targetField) {
        String raw = event.getValue("link_value").getAsString();
        Long channelId = raw.isBlank() ? null : DiscordLinks.parseChannelId(raw);
        if (!raw.isBlank() && channelId == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "configure.couldn_t_find_a_channel_id"));
            return;
        }

        switch (targetField) {
            case "rename_channel" -> settingsService.updateRenameAlertChannel(guild.getIdLong(), channelId);
            case "verification_channel" -> settingsService.updateVerificationSettings(guild.getIdLong(), channelId);
        }
        refreshPanelAfterModal(event, buildVerificationPanel(guild), channelId != null ? "Linked <#" + channelId + ">." : "Cleared.");
    }

    /** The modal was opened from a panel button, so the panel itself can be re-rendered in place — the saved link shows up immediately instead of only in a throwaway confirmation. */
    private static void refreshPanelAfterModal(ModalInteractionEvent event, Container panel, String fallbackConfirmation) {
        if (event.getMessage() != null) {
            event.editComponents(List.of(panel)).useComponentsV2(true).queue();
        } else {
            Containers.replyEphemeral(event, Containers.SUCCESS, fallbackConfirmation);
        }
    }

    private void handleRoleLinkModal(ModalInteractionEvent event, Guild guild, String targetField) {
        String raw = event.getValue("link_value").getAsString();
        Long roleId = raw.isBlank() ? null : DiscordLinks.parseId(raw);
        if (!raw.isBlank() && roleId == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "configure.couldn_t_find_a_role_id"));
            return;
        }

        String refusal = handOutRefusal(guild, roleId);
        if (refusal != null) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal);
            return;
        }
        GuildSettings current = settingsService.getEffective(guild.getIdLong());
        switch (targetField) {
            case "verified_clan_role" -> settingsService.updateVerificationRoleSettings(
                    guild.getIdLong(), roleId, current.verifiedNonClanRoleId(), current.unverifiedRoleId());
            case "verified_nonclan_role" -> settingsService.updateVerificationRoleSettings(
                    guild.getIdLong(), current.verifiedClanRoleId(), roleId, current.unverifiedRoleId());
            case "unverified_role" -> settingsService.updateVerificationRoleSettings(
                    guild.getIdLong(), current.verifiedClanRoleId(), current.verifiedNonClanRoleId(), roleId);
            case "onboarding_role" -> settingsService.updateOnboardingRole(guild.getIdLong(), roleId);
        }
        refreshPanelAfterModal(event, buildVerificationPanel(guild), roleId != null ? "Linked <@&" + roleId + ">." : "Cleared.");
    }

    private void doClanNamePrompt(ButtonInteractionEvent event) {
        GuildSettings current = settingsService.getEffective(event.getGuild().getIdLong());

        TextInput.Builder builder = TextInput.create("clan_name", TextInputStyle.SHORT)
                .setPlaceholder("Exact in-game clan name — leave blank to remove the clan")
                .setRequired(false)
                .setMaxLength(30);
        if (current.savedClanName() != null) builder.setValue(current.savedClanName());
        TextInput clanNameInput = builder.build();

        Modal modal = Modal.create("configure_clan_name_modal:_", "Set Your Clan")
                .addComponents(Label.of("Clan Name", clanNameInput))
                .build();
        event.replyModal(modal).queue();
    }

    /**
     * Setting a clan is checked, not trusted: the clan must exist, and whoever's setting it must be a
     * verified member of it holding Admin or higher (see {@link ClanVerificationService}) — otherwise any
     * server admin could claim somebody else's clan. A blank name just removes the clan. A refusal leaves
     * the panel as it was and says exactly which requirements weren't met.
     */
    private void handleClanNameModal(ModalInteractionEvent event, Guild guild) {
        String clanName = blankToNull(event.getValue("clan_name").getAsString());

        if (clanName == null) {
            settingsService.updateClanName(guild.getIdLong(), null);
            event.editComponents(List.of(buildClanPanel(guild))).useComponentsV2(true).queue();
            return;
        }

        // The clan lookup is a network call, comfortably past Discord's 3-second acknowledgement window.
        event.deferEdit().queue();
        ClanVerificationService.Result result = clanVerificationService.verify(guild.getIdLong(), event.getUser().getIdLong(), clanName);

        if (!result.allMet()) {
            StringBuilder text = new StringBuilder("### Couldn't set **" + clanName + "** as this server's clan\n")
                    .append("To set a clan you need to meet every requirement below:\n");
            for (var check : result.checks()) {
                text.append(check.met() ? "✅ " : "❌ ").append(check.requirement()).append("\n-# ").append(check.detail()).append("\n");
            }
            event.getHook().sendMessageComponents(Containers.toast(Containers.DANGER, text.toString().trim()))
                    .useComponentsV2(true).setEphemeral(true).queue();
            return;
        }

        settingsService.updateClanName(guild.getIdLong(), clanName);
        settingsService.setClanEnabled(guild.getIdLong(), true);
        event.getHook().editOriginalComponents(List.of(buildClanPanel(guild))).useComponentsV2(true).queue();
        event.getHook().sendMessageComponents(Containers.toast(Containers.SUCCESS,
                        "✅ **" + clanName + "** is now this server's clan — you're verified as an admin of it."))
                .useComponentsV2(true).setEphemeral(true).queue();
    }

    private void doWebsitePrompt(ButtonInteractionEvent event) {
        GuildSettings current = settingsService.getEffective(event.getGuild().getIdLong());

        TextInput.Builder builder = TextInput.create("website_url", TextInputStyle.SHORT)
                .setPlaceholder("https://your-clan-site.com — leave blank to remove the link")
                .setRequired(false)
                .setMaxLength(WebsiteLink.MAX_LENGTH);
        if (current.websiteUrl() != null) builder.setValue(current.websiteUrl());

        Modal modal = Modal.create("configure_clan_website_modal:_", "Clan Website")
                .addComponents(Label.of("Website address", builder.build()))
                .build();
        event.replyModal(modal).queue();
    }

    /** A blank answer removes the link; anything else must be a real web address, which is cleaned before it is saved. */
    private void handleWebsiteModal(ModalInteractionEvent event, Guild guild) {
        String raw = blankToNull(event.getValue("website_url").getAsString());
        if (raw == null) {
            settingsService.updateWebsiteUrl(guild.getIdLong(), null);
            event.editComponents(List.of(buildClanPanel(guild))).useComponentsV2(true).queue();
            return;
        }

        var url = WebsiteLink.normalize(raw);
        if (url.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "configure.that_doesn_t_look_like_a", WebsiteLink.MAX_LENGTH));
            return;
        }
        settingsService.updateWebsiteUrl(guild.getIdLong(), url.get());
        event.editComponents(List.of(buildClanPanel(guild))).useComponentsV2(true).queue();
    }

    private void doClanToggle(ButtonInteractionEvent event, Guild guild) {
        GuildSettings settings = settingsService.getEffective(guild.getIdLong());
        if (settings.savedClanName() == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "configure.set_a_clan_first_there_nothing"));
            return;
        }
        settingsService.setClanEnabled(guild.getIdLong(), !settings.clanEnabled());
        event.editComponents(List.of(buildClanPanel(guild))).useComponentsV2(true).queue();
    }

    private static String blankToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The top-level panel: one section per area, each a button with a line under it saying what it's for. */
    public Container buildMainPanel(Guild guild) {
        GuildSettings settings = settingsService.getEffective(guild.getIdLong());

        Button clanButton;
        String clanStatus;
        if (settings.clanActive()) {
            clanButton = Button.success("configure_clan:_", "Clan Setup");
            clanStatus = "Set to **" + settings.clanName() + "**.";
        } else if (settings.savedClanName() != null) {
            clanButton = Button.secondary("configure_clan:_", "Clan Setup");
            clanStatus = "**" + settings.savedClanName() + "** is saved but clan features are switched off.";
        } else {
            clanButton = Button.danger("configure_clan:_", "Clan Setup");
            clanStatus = "**No clan set yet** — start here.";
        }

        // Everything the bot currently has posted as an embed: written posts plus the pre-made ones.
        int embeddedPosts = announcementRepository.countPostedEmbeds(guild.getIdLong()) + embedService.getPostedInGuild(guild.getIdLong()).size();

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("# Server Configuration\n-# Administrator only"));
        children.add(ActionRow.of(clanButton));
        children.add(TextDisplay.of("-# " + clanStatus + " The clan itself, whether clan features are on, and the bot's Admin role."));
        children.add(ActionRow.of(Button.secondary("configure_verification:_", "RSN Link")));
        children.add(TextDisplay.of("-# How members link their RuneScape name with `/rs`: the review channel, rename alerts, and the roles handed out."));
        children.add(ActionRow.of(Button.secondary("configure_announce_main:_", "Embedded Posts (" + embeddedPosts + ")")));
        children.add(TextDisplay.of("-# Messages the bot posts and keeps up to date as embeds in your channels, like rules or a welcome."));
        children.add(ActionRow.of(Button.secondary("configure_tracking_main:_", "Tracker Channels")));
        children.add(TextDisplay.of("-# Where clan activity is posted — drops, levels, quests, Citadel, joins and leaves, and the weekly reports."));
        children.add(ActionRow.of(Button.secondary("configure_cmdchan_main:_", "Command Only Channels")));
        children.add(TextDisplay.of("-# Channels where only bot commands are allowed — anything else typed there is deleted."));
        children.add(ActionRow.of(Button.secondary(PvmHelpConfigureListener.OPEN_ID, "PvM Help")));
        children.add(TextDisplay.of("-# The PVM Helper roles and guidelines, and when help tickets ping helpers for members and guests."));
        children.add(Containers.autoCloseNote());
        return Containers.card(Containers.PRIMARY, children);
    }

    Container buildClanPanel(Guild guild) {
        GuildSettings settings = settingsService.getEffective(guild.getIdLong());

        String status;
        if (settings.clanActive()) status = "🟢 **On**";
        else if (settings.savedClanName() != null) status = "⚫ **Off** — clan features are switched off";
        else status = "🔴 **Not set up**";

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Clan Setup\n" +
                "**Clan:** " + display(settings.savedClanName()) + "\n" +
                "**Status:** " + status + "\n" +
                "**Website:** " + (settings.websiteUrl() != null ? settings.websiteUrl() : "*not set*")));
        children.add(TextDisplay.of("-# Setting a clan checks that it exists and that you're a verified Admin-rank (or higher) member of it. " +
                "Turning clan features off hides the clan tools and pauses clan tracking, without forgetting the name. " +
                "If the clan has a website, add its address and the clan name at the top of `/rs` becomes a link to it."));
        children.add(ActionRow.of(
                Button.primary("configure_clan_name:_", settings.savedClanName() != null ? "Change Clan" : "Set Clan"),
                settings.clanEnabled()
                        ? Button.danger("configure_clan_toggle:_", "Turn Clan Features Off")
                        : Button.success("configure_clan_toggle:_", "Turn Clan Features On"),
                Button.secondary("configure_clan_website:_", settings.websiteUrl() != null ? "Change Website Link" : "Set Website Link")));

        children.add(TextDisplay.of("**Bot Admin Roles** — who can use `/rsadmin` and the admin-only tools. Anyone with a role ranked above the lowest of these counts too. " +
                "Add more groups of your own, such as a Web Dev group, in the website dashboard."));
        children.add(ActionRow.of(groupRoleMenu(guild, "configure_admin_role:_", PermissionGroup.ADMIN)));

        children.add(TextDisplay.of("**Bot Support Roles** — can review and verify RSN requests in `/rsadmin`, and nothing else there. Leave empty for none."));
        children.add(ActionRow.of(groupRoleMenu(guild, "configure_support_role:_", PermissionGroup.SUPPORT)));

        children.add(TextDisplay.of("**Bot Developer Roles** — may open the website's dashboard (ticket panels and settings), alongside the Admin roles and above. Leave empty for none."));
        children.add(ActionRow.of(groupRoleMenu(guild, "configure_developer_role:_", PermissionGroup.DEVELOPER)));

        children.add(ActionRow.of(Button.primary("configure_back:_", "Back")));
        children.add(Containers.autoCloseNote());
        return Containers.card(Containers.PRIMARY, children);
    }

    /** A role dropdown for one permission group, showing the group's roles that still exist. */
    private EntitySelectMenu groupRoleMenu(Guild guild, String id, String groupKey) {
        List<EntitySelectMenu.DefaultValue> current = permissionGroups.roleIds(guild.getIdLong(), groupKey).stream()
                .filter(roleId -> guild.getRoleById(roleId) != null)
                .map(EntitySelectMenu.DefaultValue::role)
                .toList();
        EntitySelectMenu.Builder menu = EntitySelectMenu.create(id, EntitySelectMenu.SelectTarget.ROLE)
                .setPlaceholder("Select roles (optional)")
                .setRequiredRange(0, PermissionGroupService.MAX_ROLES_PER_GROUP);
        if (!current.isEmpty()) menu.setDefaultValues(current);
        return menu.build();
    }

    private static String display(String value) {
        return value != null ? "**" + value + "**" : "*not set*";
    }

    Container buildVerificationPanel(Guild guild) {
        GuildSettings settings = settingsService.getEffective(guild.getIdLong());

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### RSN Link\n-# The makeover-mage appearance check is temporarily disabled — every `/rs` " +
                "link request is a plain admin call for now."));

        children.add(channelBlock(guild, "Review Channel", "where a new `/rs` link request is posted for an admin to Approve/Reject.",
                settings.verificationReviewChannelId()));
        children.add(buildChannelSelectRow(guild, "configure_verification_channel:_", "Select a channel (optional)", settings.verificationReviewChannelId()));
        children.add(Containers.linkButtonRow("configure_verification_channel_link:_"));

        children.add(channelBlock(guild, "Rename Alert Channel", "where a possible in-game RSN change is posted for an admin to Confirm/Reject, detected automatically during **Sync Clan**.",
                settings.renameAlertChannelId()));
        children.add(buildChannelSelectRow(guild, "configure_rename_channel:_", "Select a channel (optional)", settings.renameAlertChannelId()));
        children.add(Containers.linkButtonRow("configure_rename_channel_link:_"));

        children.add(TextDisplay.of("-# The roles below are all optional — pick \"No Role Assignment\" to leave one empty. Only the first 24 roles show; use **Link by ID** for anything past that."));
        children.add(TextDisplay.of("**Verified Role — Clan Member**\n-# Granted when an approved request's RSN is currently in the tracked clan roster."));
        children.add(buildRoleSelectRow(guild, "configure_verified_clan_role:_", "Select a role (optional)", settings.verifiedClanRoleId()));
        children.add(Containers.linkButtonRow("configure_verified_clan_role_link:_"));
        children.add(TextDisplay.of("**Verified Role — Not a Clan Member**\n-# Granted when an approved request's RSN *isn't* in the clan — usually a role something else (e.g. a join flow) already grants; this only fills it in if missing."));
        children.add(buildRoleSelectRow(guild, "configure_verified_nonclan_role:_", "Select a role (optional)", settings.verifiedNonClanRoleId()));
        children.add(Containers.linkButtonRow("configure_verified_nonclan_role_link:_"));
        children.add(TextDisplay.of("**Unverified Role**\n-# Removed from the member the moment their request is approved (either verified role above)."));
        children.add(buildRoleSelectRow(guild, "configure_unverified_role:_", "Select a role (optional)", settings.unverifiedRoleId()));
        children.add(Containers.linkButtonRow("configure_unverified_role_link:_"));
        children.add(TextDisplay.of("**Onboarding Role**\n-# Granted the moment someone *submits* `/rs` — before any admin has reviewed it. Separate from the three roles above, which only apply once a request is resolved."));
        children.add(buildRoleSelectRow(guild, "configure_onboarding_role:_", "Select a role (optional)", settings.onboardingRoleId()));
        children.add(Containers.linkButtonRow("configure_onboarding_role_link:_"));

        children.add(ActionRow.of(Button.primary("configure_back:_", "Back")));
        children.add(Containers.autoCloseNote());
        return Containers.card(Containers.PRIMARY, children);
    }

    // Discord caps a select menu at 25 options; "No Role Assignment" takes one, leaving room for
    // the guild's first 24 roles (highest-position first, same order guild.getRoles() returns them
    // in) — a server with more than that is a rare edge case not worth a second menu for here.
    private static final int ROLE_SELECT_LIMIT = 24;

    private ActionRow buildRoleSelectRow(Guild guild, String customId, String placeholder, Long currentRoleId) {
        StringSelectMenu.Builder menu = StringSelectMenu.create(customId).setPlaceholder(placeholder);
        menu.addOption("No Role Assignment", NO_ROLE_VALUE);

        List<Role> roles = guild.getRoles().stream().filter(role -> !role.isPublicRole()).limit(ROLE_SELECT_LIMIT).toList();
        for (Role role : roles) {
            menu.addOption(role.getName(), role.getId());
        }

        boolean currentStillListed = currentRoleId != null && roles.stream().anyMatch(role -> role.getIdLong() == currentRoleId);
        menu.setDefaultValues(currentStillListed ? String.valueOf(currentRoleId) : NO_ROLE_VALUE);

        return ActionRow.of(menu.build());
    }

    /**
     * A channel setting's heading, what it does, and what's actually linked now — all one text component
     * (this panel is close to Discord's per-message component cap). The dropdown below can only
     * pre-select a plain text channel, so a thread or forum post linked via Link by ID would otherwise
     * look like nothing was saved.
     */
    private static TextDisplay channelBlock(Guild guild, String title, String description, Long channelId) {
        String current;
        if (channelId == null) {
            current = "*not set*";
        } else if (guild.getChannelById(GuildMessageChannel.class, channelId) == null) {
            current = "<#" + channelId + "> — ⚠️ I can't see that channel (deleted, an archived thread, or I'm missing access), so nothing can be posted there.";
        } else {
            current = "<#" + channelId + ">";
        }
        return TextDisplay.of("**" + title + "** — " + description + "\n-# Currently: " + current);
    }

    /** A native Discord channel picker (text channels only) instead of typing/pasting an ID — {@code setRequiredRange(0, 1)} lets the admin clear a previously-picked channel back to "not set" by deselecting it. */
    private ActionRow buildChannelSelectRow(Guild guild, String customId, String placeholder, Long currentChannelId) {
        EntitySelectMenu.Builder menu = EntitySelectMenu.create(customId, EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder(placeholder)
                .setRequiredRange(0, 1);
        if (currentChannelId != null && guild.getTextChannelById(currentChannelId) != null) {
            menu.setDefaultValues(EntitySelectMenu.DefaultValue.channel(currentChannelId));
        }
        return ActionRow.of(menu.build());
    }
}
