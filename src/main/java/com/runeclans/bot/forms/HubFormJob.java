package com.runeclans.bot.forms;

import com.runeclans.bot.commands.ticket.TicketService;
import com.runeclans.bot.discord.DiscordLookups;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import com.runeclans.bot.forms.WebForms.Answer;
import com.runeclans.bot.forms.WebForms.Form;
import com.runeclans.bot.hubcommunity.HubCommunityClient;
import com.runeclans.bot.hubcommunity.HubCommunityMaps;
import com.runeclans.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Discord side of a form answered on the website while the hub keeps forms ({@code hub-community}, ticket F3): the hub's
 * {@code form.submitted} feed entry (queued by {@code hubcommunity.HubCommunityFeed}) opens the form's ticket, posts the answers to its channel
 * and hands out its role, the same three steps as {@link SiteApplicationJob}. The panel, channel and role are the bot's settings for the hub
 * form ({@code hub_form_map}); the answers ride in the job. Not idempotent: a second run would post twice.
 */
@BService
public class HubFormJob implements DiscordJobHandler {
    public static final String KIND = "hubcommunity.form-submitted";

    private final HubCommunityMaps maps;
    private final HubCommunityClient hub;
    private final WebFormSteps steps;

    public HubFormJob(HubCommunityMaps maps, HubCommunityClient hub, WebFormRepository forms, TicketRepository tickets, TicketService ticketService) {
        this.maps = maps;
        this.hub = hub;
        this.steps = new WebFormSteps(forms, tickets, ticketService);
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    /** The hub's answers ({@code [{questionId, label, value}]}), in order. */
    static List<Answer> answers(DataObject payload) {
        List<Answer> out = new ArrayList<>();
        DataArray list = payload.optArray("answers").orElse(DataArray.empty());
        for (int i = 0; i < list.length(); i++) {
            DataObject a = list.getObject(i);
            out.add(new Answer(a.getString("questionId", ""), a.getString("label", ""), a.getString("value", "")));
        }
        return out;
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        DataObject p = job.payload();
        UUID formId = UUID.fromString(p.getString("formId"));
        String discord = p.isNull("discordId") ? null : p.getString("discordId", null);
        if (discord == null || !discord.matches("[0-9]{15,20}")) throw new DiscordJobs.Refused("The answers came from someone with no Discord account, so nothing was passed on.");
        long userId = Long.parseLong(discord);
        HubCommunityMaps.FormRow row = maps.form(formId).filter(f -> f.guildId() == guild.getIdLong())
                .orElseThrow(() -> new DiscordJobs.Refused("The form has no Discord settings here any more; the answers are kept on RuneClans."));
        String name = hub.find("/v1/forms/" + formId).map(f -> f.getString("name", "Form")).orElse("Form");
        List<Answer> answers = answers(p);
        // the hub checked the answers against its questions; Discord gets each one as a written answer under its label
        Form base = new Form(0L, guild.getIdLong(), name, name, "", true, row.panelId(), row.channelId(), row.roleId(), false, 0, List.of());
        Form form = WebForms.asSiteForm(base, answers.stream().map(Answer::label).toList());
        if (form.questions().size() != answers.size()) answers = answers.subList(0, form.questions().size());
        String site = "site_application".equals(p.getString("formKind", "plain")) ? "RuneClans" : null;

        Member member = DiscordLookups.memberOrSlow(guild, userId);
        List<String> notes = new ArrayList<>();
        boolean ticketOpened = false;
        if (form.panelId() != null && member != null) {
            try {
                ticketOpened = steps.openTicket(guild, member, form, 0L, answers, notes); // no bot submission row: the hub keeps it
            } catch (RuntimeException e) {
                notes.add("Your answers were saved, but the ticket could not be opened. Someone will still see them.");
            }
        }
        if (form.channelId() != null) steps.postToChannel(guild, member, userId, form, answers, site);
        if (form.roleId() != null && member != null) steps.giveRole(guild, member, form, notes);
        DataArray said = DataArray.empty();
        notes.forEach(said::add);
        return DataObject.empty().put("ticketOpened", ticketOpened).put("notes", said);
    }
}
