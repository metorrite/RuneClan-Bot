package com.runeclans.bot.suggestion;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.i18n.Text;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * The up and down buttons on a suggestion. Pressing one casts that vote; pressing it again takes it back; pressing the other switches.
 * Only the presser sees the answer. Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class SuggestionListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(SuggestionListener.class);

    private final SuggestionRepository repository;
    private final SuggestionService service;

    public SuggestionListener(SuggestionRepository repository, SuggestionService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        long suggestionId = SuggestionVote.suggestionOf(id);
        if (suggestionId < 0) return;
        try {
            Guild guild = event.getGuild();
            if (guild == null) return;
            Optional<Suggestion> found = repository.byId(suggestionId);
            if (found.isEmpty() || found.get().guildId() != guild.getIdLong()) {
                event.reply(Text.of(event, "suggestion.that_suggestion_doesn_t_exist_any")).setEphemeral(true).queue();
                return;
            }
            Suggestion s = found.get();
            if (!s.open()) {
                event.reply(Text.of(event, "suggestion.voting_on_this_suggestion_has_closed")).setEphemeral(true).queue();
                return;
            }
            int now = repository.castVote(s.id(), s.guildId(), event.getUser().getIdLong(), SuggestionVote.directionOf(id));
            int[] counts = repository.counts(s.id());
            // answering by editing the message keeps the counts current without a second call
            event.editMessage(SuggestionMessage.edit(s, counts[0], counts[1])).queue(ok -> {}, error -> {});
            log.debug("{} voted {} on suggestion {}", event.getUser().getId(), now, s.id());
        } catch (Exception e) {
            log.error("Unhandled exception in suggestion button '{}'", id, e);
            Containers.replyError(event); // always answer, or Discord shows "This interaction failed" (B-104)
        }
    }
}
