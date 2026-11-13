package com.runeclans.bot.internal;

import com.runeclans.bot.events.CompetitionTemplate;
import com.runeclans.bot.plugin.RuneSyncPanelService;
import com.runeclans.bot.plugin.RuneSyncRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * A clan's plugin switches for the dashboard's Clan plugin page ({@code GET plugin-settings}, {@code PUT plugin-settings}). Today that is
 * one switch, {@code clanPanel}: "Allow the in-game clan panel" (T-206), off until staff turn it on. When it is on, RuneSync shows members
 * of this server the clan's open and running events and their own points and progress. And {@code competitionXpSource} (T-537): where new
 * competitions count XP from unless the organiser picks otherwise, {@code runemetrics}, {@code plugin} (RuneSync, through the RuneClans hub) or
 * {@code auto} (RuneSync for players who run it, RuneMetrics for the rest, T-557),
 * offered only while the bot talks to the hub ({@code pluginXpOffered}); events that already exist never change. Reached only through {@link TicketAdminApi},
 * which has already checked the asker may use the dashboard in this server and audit-logs the change.
 */
@BService
public class PluginSettingsAdminApi implements AdminRouteExtension {
    private final RuneSyncRepository repository;
    private final RuneSyncPanelService panel;

    public PluginSettingsAdminApi(RuneSyncRepository repository, RuneSyncPanelService panel) {
        this.repository = repository;
        this.panel = panel;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("plugin-settings")) return null;
        long guildId = guild.getIdLong();
        if (method.equals("PUT")) {
            DataObject input = body.get();
            boolean panelSent = input.hasKey("clanPanel");
            boolean sourceSent = input.hasKey("competitionXpSource");
            if (!sourceSent && !panelSent || panelSent && !input.isType("clanPanel", DataType.BOOLEAN)) {
                throw AdminRouteExtension.refuse(400, "Say whether the in-game clan panel is allowed (clanPanel: true or false).");
            }
            String source = sourceSent && !input.isNull("competitionXpSource") ? String.valueOf(input.get("competitionXpSource")) : null;
            if (source != null && !CompetitionTemplate.validSource(source)) {
                throw AdminRouteExtension.refuse(400, "New competitions count XP from the Jagex hiscores, the RuneSync plugin or auto (competitionXpSource: runemetrics, plugin or auto).");
            }
            if (CompetitionTemplate.needsHub(source) && !CompetitionTemplate.pluginSourceOffered()) {
                throw AdminRouteExtension.refuse(409, "Counting competitions from RuneSync needs the bot connected to the RuneClans hub, and it isn't.");
            }
            if (panelSent) {
                repository.setClanPanel(guildId, input.getBoolean("clanPanel"), actor.getIdLong(), Instant.now());
                panel.forget();
            }
            if (sourceSent) repository.setCompetitionXpSource(guildId, source, actor.getIdLong(), Instant.now());
        } else {
            AdminRouteExtension.requireMethod(method, "GET");
        }
        String source = repository.competitionXpSource(guildId);
        return DataObject.empty().put("clanPanel", repository.clanPanelOn(guildId))
                .put("competitionXpSource", CompetitionTemplate.validSource(source) ? source : CompetitionTemplate.SOURCE_RUNEMETRICS)
                .put("pluginXpOffered", CompetitionTemplate.pluginSourceOffered());
    }
}
