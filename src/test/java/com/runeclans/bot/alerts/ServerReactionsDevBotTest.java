package com.runeclans.bot.alerts;

import com.runeclans.bot.afk.AfkListener;
import com.runeclans.bot.automod.AutomodListener;
import com.runeclans.bot.autodelete.AutoDeleteListener;
import com.runeclans.bot.autoreply.AutoReplyListener;
import com.runeclans.bot.ban.BanAnnouncementListener;
import com.runeclans.bot.chatlevel.ChatLevelListener;
import com.runeclans.bot.commandchannel.CommandChannelListener;
import com.runeclans.bot.events.LooseProofReminderListener;
import com.runeclans.bot.highlight.HighlightListener;
import com.runeclans.bot.honeypot.HoneypotListener;
import com.runeclans.bot.messagelog.MessageLogListener;
import com.runeclans.bot.modlog.ModLogListener;
import com.runeclans.bot.points.PointsKeywordListener;
import com.runeclans.bot.rolepanel.RolePanelReactionListener;
import com.runeclans.bot.starboard.StarboardListener;
import com.runeclans.bot.tempvoice.TempVoiceListener;
import com.runeclans.bot.voicetext.VoiceTextListener;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.guild.GuildBanEvent;
import net.dv8tion.jda.api.events.guild.GuildUnbanEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateTimeOutEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.mockito.stubbing.Answer;
import net.dv8tion.jda.api.entities.Message;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * B-82 follow-up: a bot that is not live does nothing by itself when people chat, react, join voice, get banned or timed out. Each listener is
 * built with mocks for everything it uses and handed every event it listens to: none of those mocks may be touched (so no setting is even read,
 * and nothing is posted, deleted, DMed or changed). The live bot runs them as before (each listener's own tests).
 */
class ServerReactionsDevBotTest {
    private static final List<Class<? extends GenericEvent>> EVENTS = List.of(MessageReceivedEvent.class, MessageUpdateEvent.class, MessageDeleteEvent.class,
            MessageBulkDeleteEvent.class, MessageReactionAddEvent.class, MessageReactionRemoveEvent.class, GuildVoiceUpdateEvent.class, GuildBanEvent.class,
            GuildUnbanEvent.class, GuildMemberUpdateTimeOutEvent.class, GuildMemberRoleAddEvent.class);

    static Stream<Class<? extends ListenerAdapter>> listeners() {
        return Stream.of(AutomodListener.class, AutoReplyListener.class, AutoDeleteListener.class, HighlightListener.class, MessageLogListener.class,
                AfkListener.class, PointsKeywordListener.class, LooseProofReminderListener.class, BanAnnouncementListener.class, CommandChannelListener.class,
                VoiceTextListener.class, TempVoiceListener.class, ModLogListener.class, StarboardListener.class, RolePanelReactionListener.class,
                HoneypotListener.class, ChatLevelListener.class);
    }

    /**
     * Events that look real enough to get past each listener's own early returns (from a server, with an attachment), so only the dev-bot gate
     * can stop them: without it the listener goes on to read its settings, and the test fails.
     */
    private static final Answer<Object> REAL_ENOUGH = invocation -> {
        String name = invocation.getMethod().getName();
        if (name.equals("isFromGuild")) return true;
        if (name.equals("getAttachments")) return List.of(mock(Message.Attachment.class, Answers.RETURNS_DEEP_STUBS));
        return Answers.RETURNS_DEEP_STUBS.answer(invocation);
    };

    @BeforeEach
    void notLive() {
        LiveSwitch.set(false);
    }

    @AfterEach
    void reset() {
        AutoPostRouter.reset();
    }

    @ParameterizedTest
    @MethodSource("listeners")
    void aBotThatIsNotLiveTouchesNothing(Class<? extends ListenerAdapter> type) throws Exception {
        Constructor<?> constructor = type.getConstructors()[0];
        List<Object> uses = new ArrayList<>();
        for (Class<?> parameter : constructor.getParameterTypes()) uses.add(mock(parameter));
        ListenerAdapter listener = (ListenerAdapter) constructor.newInstance(uses.toArray());

        for (Class<? extends GenericEvent> eventType : EVENTS) listener.onEvent(mock(eventType, REAL_ENOUGH));

        for (Object used : uses) verifyNoInteractions(used);
    }
}
