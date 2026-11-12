package com.runeclans.bot.points;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.points.PointsExtrasRules.Trigger;
import com.runeclans.bot.points.PointsRules.Category;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pays a point (or a few) when a member says a trigger word and mentions someone: "thanks @Zezima" gives Zezima the trigger's points. Nothing
 * happens in a server that has no triggers (the check is one in-memory lookup) or whose points are switched off. The writer can't pay themselves,
 * bots are never paid, a message pays at most three people, the writer is limited by the trigger's cool-down, one writer paying the same person
 * for the same trigger only counts once a day, and a member can be paid by one trigger only up to its daily cap. Every payment goes through the
 * ledger with an idempotency key. Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class PointsKeywordListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(PointsKeywordListener.class);

    private final PointsExtrasRepository extras;
    private final PointsRepository repository;
    private final PointsService service;
    private final PointsExtrasRules.Cooldowns cooldowns = new PointsExtrasRules.Cooldowns();

    public PointsKeywordListener(PointsExtrasRepository extras, PointsRepository repository, PointsService service) {
        this.extras = extras;
        this.repository = repository;
        this.service = service;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (AutoPostRouter.skipSweep("points keyword")) return; // awards points and replies in the real server: only the live bot does that (B-82 follow-up)
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()) return;
        try {
            long guildId = event.getGuild().getIdLong();
            List<Trigger> triggers = extras.triggers(guildId);
            if (triggers.isEmpty()) return;
            Message message = event.getMessage();
            String text = message.getContentStripped();
            List<Trigger> hit = triggers.stream().filter(t -> t.enabled() && PointsExtrasRules.matches(t.keyword(), text)).toList();
            if (hit.isEmpty() || message.getMentions().getMembers().isEmpty()) return;
            if (!repository.settings(guildId).enabled()) return;

            long writer = event.getAuthor().getIdLong();
            List<Long> mentioned = message.getMentions().getMembers().stream().map(Member::getIdLong).toList();
            Set<Long> bots = new HashSet<>();
            message.getMentions().getMembers().forEach(m -> {
                if (m.getUser().isBot()) bots.add(m.getIdLong());
            });
            List<Long> receivers = PointsExtrasRules.receivers(writer, mentioned, bots);
            if (receivers.isEmpty()) return;

            long now = System.currentTimeMillis();
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            boolean paid = false;
            for (Trigger trigger : hit) {
                if (!cooldowns.tryUse(now, writer, trigger.id(), trigger.cooldownSeconds())) continue;
                for (long receiver : receivers) {
                    if (extras.countPayments(guildId, receiver, PointsExtrasRules.receivedPattern(trigger.id(), receiver, today)) >= trigger.dailyCap()) continue;
                    var result = service.apply(guildId, receiver, trigger.points(), "Said \"" + trigger.keyword() + "\" by " + event.getAuthor().getEffectiveName(),
                            Category.KEYWORD, PointsExtrasRules.key(trigger.id(), writer, receiver, today), writer);
                    paid |= result.outcome() == PointsService.Outcome.DONE;
                }
            }
            if (paid) message.addReaction(Emoji.fromUnicode("✨")).queue(ok -> {}, error -> {});
        } catch (Exception e) {
            log.error("Keyword points failed for a message in guild {}", event.getGuild().getId(), e);
        }
    }
}
