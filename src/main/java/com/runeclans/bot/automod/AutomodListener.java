package com.runeclans.bot.automod;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.moderation.ModAction;
import com.runeclans.bot.moderation.ModCase;
import com.runeclans.bot.moderation.ModService;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.automod.AutomodRepository.Config;
import com.runeclans.bot.automod.AutomodRules.Rule;
import com.runeclans.bot.warning.AutopunishService;
import com.runeclans.bot.warning.WarningRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Checks every message against its server's automod rules. A message that breaks one is deleted and the writer is told why in a note that
 * removes itself; if the rule is set to warn, the writer also gets a recorded warning (which counts toward Autopunish). Bots, webhooks,
 * anyone who can manage messages in the channel, and the server's exempt roles and channels are never checked. Registered with JDA by
 * {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class AutomodListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(AutomodListener.class);
    private static final int NOTICE_SECONDS = 8;
    private static final int SWEEP_EVERY = 2000;

    private final AutomodRepository repository;
    private final WarningRepository warnings;
    private final AutopunishService autopunish;
    private final PhishingList phishing;
    private final ModService mod;
    private final Map<String, AutomodRules.History> histories = new ConcurrentHashMap<>();
    private int seen;

    public AutomodListener(AutomodRepository repository, WarningRepository warnings, AutopunishService autopunish, PhishingList phishing, ModService mod) {
        this.mod = mod;
        this.repository = repository;
        this.phishing = phishing;
        this.warnings = warnings;
        this.autopunish = autopunish;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (AutoPostRouter.skipSweep("automod")) return; // deletes, times out and alerts in the real server: only the live bot does that (B-82 follow-up)
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()) return;
        try {
            Config config = repository.get(event.getGuild().getIdLong());
            if (!config.enabled() || config.exemptChannels().contains(event.getChannel().getIdLong())) return;
            if (!(event.getChannel() instanceof GuildMessageChannel channel)) return;
            Member member = event.getMember();
            if (member == null || member.hasPermission(channel, Permission.MESSAGE_MANAGE) || member.hasPermission(Permission.ADMINISTRATOR)) return;
            if (member.getRoles().stream().anyMatch(r -> config.exemptRoles().contains(r.getIdLong()))) return;
            if (!event.getGuild().getSelfMember().hasPermission(channel, Permission.MESSAGE_MANAGE)) return;

            Message message = event.getMessage();
            long now = System.currentTimeMillis();
            AutomodRules.History history = histories.computeIfAbsent(event.getGuild().getId() + ":" + event.getAuthor().getId(), k -> new AutomodRules.History());
            var mentions = message.getMentions();
            int pinged = mentions.getUsers().size() + mentions.getRoles().size() + (mentions.mentionsEveryone() ? 1 : 0);
            int images = (int) message.getAttachments().stream().filter(a -> a.isImage() || (a.getContentType() != null && a.getContentType().startsWith("image/"))).count();
            AutomodRules.Facts facts = new AutomodRules.Facts(message.getContentRaw(), pinged, images, message.getStickers().size(), phishing::isListed);
            Rule broken = AutomodRules.firstViolation(config.rules(), facts, history, now);
            sweep(now);
            if (broken == null) return;

            message.delete().queue(ok -> {}, error -> log.debug("Couldn't delete a message in {}: {}", channel.getId(), error.getMessage()));
            AutomodRules.RuleConfig ruleConfig = config.rules().get(broken);
            String note = ruleConfig.note(broken, event.getAuthor().getAsMention());
            if (ruleConfig.warn()) note = warn(event, member, broken) + " " + note;
            logTo(event, ruleConfig, broken, channel, message.getContentDisplay());
            if (channel.canTalk()) {
                channel.sendMessage(note).setAllowedMentions(EnumSet.of(Message.MentionType.USER))
                        .queue(sent -> sent.delete().queueAfter(NOTICE_SECONDS, TimeUnit.SECONDS, ok -> {}, error -> {}), error -> {});
            }
        } catch (Exception e) {
            log.error("Automod failed for a message in guild {}", event.getGuild().getId(), e);
        }
    }

    /** Tells the rule's own log channel (when it has one) what was removed. Never pings anyone, and the quoted text is cut short. */
    private void logTo(MessageReceivedEvent event, AutomodRules.RuleConfig ruleConfig, Rule rule, GuildMessageChannel from, String content) {
        if (ruleConfig.logChannelId() == 0) return;
        GuildMessageChannel log = event.getGuild().getChannelById(GuildMessageChannel.class, ruleConfig.logChannelId());
        if (log == null || !log.canTalk()) return;
        String quoted = content.length() > 500 ? content.substring(0, 500) + "…" : content;
        quoted = quoted.isBlank() ? "(no text)" : quoted.replace("\n", "\n> ");
        String text = "**Automod, " + rule.label() + ":** removed a message from " + event.getAuthor().getAsMention() + " in " + from.getAsMention() + ".\n> " + quoted;
        log.sendMessage(text).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue(ok -> {}, error -> {});
    }

    /** Records a warning for the break and applies any Autopunish rule it reaches. Returns a short note for the channel (empty when nothing more happened). */
    private String warn(MessageReceivedEvent event, Member member, Rule rule) {
        long guildId = event.getGuild().getIdLong();
        warnings.add(guildId, member.getIdLong(), event.getJDA().getSelfUser().getIdLong(), "Automod: " + rule.label());
        int total = warnings.countFor(guildId, member.getIdLong());
        mod.recordOnly(event.getGuild(), member.getIdLong(), member.getUser().getName(), event.getJDA().getSelfUser().getIdLong(), event.getGuild().getSelfMember().getEffectiveName(),
                ModAction.WARN, "Automod: " + rule.label(), null, ModCase.AUTOMOD);
        member.getUser().openPrivateChannel()
                .flatMap(dm -> dm.sendMessage("You have been warned in **" + event.getGuild().getName() + "** by automod for " + rule.reason() + "."))
                .queue(ok -> {}, error -> {});
        String punished = autopunish.applyFor(event.getGuild(), member, total);
        return punished == null ? "" : punished;
    }

    private void sweep(long now) {
        if (++seen < SWEEP_EVERY) return;
        seen = 0;
        histories.values().removeIf(h -> h.idleFor(now, 300));
    }
}
