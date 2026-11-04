package com.runeclans.bot.events;

import com.runeclans.bot.discord.Mentions;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import com.runeclans.bot.events.QuickGroupRepository.Group;
import com.runeclans.bot.events.QuickGroupRepository.Member;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

/**
 * The Discord side of the group finder (T-589), run by the outbox (T-456) so the clan site never waits on Discord:
 * <ul>
 *   <li>{@code quickgroup.post}: posts a new group in the server's group finder channel. Not idempotent (a post is never sent twice).</li>
 *   <li>{@code quickgroup.sync}: edits a group's post to how it stands now (who took which role, or closed). Idempotent.</li>
 * </ul>
 * The posts carry member-written text, so they ping nobody ({@link Mentions#NONE}).
 */
@BService
public class QuickGroupJobs implements DiscordJobHandler {
    static final String POST = "quickgroup.post";
    static final String SYNC = "quickgroup.sync";
    private static final int OPEN_COLOUR = 0x2f9e6b;
    private static final int CLOSED_COLOUR = 0x6b7280;

    private final QuickGroupRepository repository;

    public QuickGroupJobs(QuickGroupRepository repository) {
        this.repository = repository;
    }

    @Override
    public String kind() {
        return POST;
    }

    @Override
    public List<String> kinds() {
        return List.of(POST, SYNC);
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    @Override
    public boolean idempotent(String kind) {
        return SYNC.equals(kind);
    }

    static DataObject payload(long groupId) {
        return DataObject.empty().put("groupId", Long.toString(groupId));
    }

    static String syncDedupe(long groupId) {
        return "quickgroup:sync:" + groupId;
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        long groupId = Long.parseLong(job.payload().getString("groupId"));
        Group group = repository.group(guild.getIdLong(), groupId).orElseThrow(() -> new DiscordJobs.Refused("That group was deleted before the bot got to it."));
        MessageEmbed embed = embed(group, repository.members(groupId), id -> {
            net.dv8tion.jda.api.entities.Member m = guild.getMemberById(id);
            return m == null ? null : m.getEffectiveName();
        });
        return switch (job.kind()) {
            case POST -> {
                if (repository.post(groupId).isPresent()) yield DataObject.empty().put("posted", false);
                long channelId = Long.parseLong(job.payload().getString("channelId"));
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
                if (channel == null) throw new DiscordJobs.Refused("The group finder's channel is gone. Choose another on the dashboard.");
                if (!channel.canTalk() || !guild.getSelfMember().hasPermission(channel, Permission.MESSAGE_EMBED_LINKS))
                    throw new DiscordJobs.Refused("The bot can't post in #" + channel.getName() + ". Give it Send Messages and Embed Links there.");
                Message sent = DiscordJobs.send(channel.sendMessageEmbeds(embed).setAllowedMentions(Mentions.NONE));
                repository.savePost(groupId, channel.getIdLong(), sent.getIdLong());
                yield DataObject.empty().put("posted", true).put("messageId", sent.getId());
            }
            case SYNC -> {
                var post = repository.post(groupId).orElse(null);
                if (post == null) yield DataObject.empty().put("edited", false);
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, post.channelId());
                if (channel == null) {
                    repository.forgetPost(groupId);
                    yield DataObject.empty().put("edited", false);
                }
                try {
                    DiscordJobs.send(channel.editMessageEmbedsById(post.messageId(), embed).setAllowedMentions(Mentions.NONE));
                } catch (ErrorResponseException e) {
                    if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) throw e;
                    repository.forgetPost(groupId); // someone deleted the post: nothing left to keep up to date
                    yield DataObject.empty().put("edited", false);
                }
                yield DataObject.empty().put("edited", true);
            }
            default -> throw new DiscordJobs.Refused("The bot no longer knows how to do this.");
        };
    }

    /**
     * The group as a Discord post: what it is for and when (in each reader's own time), the host, the note, and one line per role with who took it.
     * {@code nameOf} answers a member's name in the server, or null when the bot can't see them ("A member"). Pure, for the tests.
     */
    public static MessageEmbed embed(Group group, List<Member> members, LongFunction<String> nameOf) {
        List<QuickGroups.Slot> slots = QuickGroups.slotsOf(group.config());
        Map<String, Integer> taken = QuickGroups.taken(slots, members.stream().map(Member::role).toList());
        int filled = taken.values().stream().mapToInt(Integer::intValue).sum();
        int places = slots.stream().mapToInt(QuickGroups.Slot::places).sum();
        String link = QuickGroups.linkOf(group.config());

        EmbedBuilder b = new EmbedBuilder();
        String title = (group.open() ? "Group: " : "Closed: ") + group.activity();
        b.setTitle(title.length() > 256 ? title.substring(0, 256) : title, link);
        b.setColor(group.open() ? OPEN_COLOUR : CLOSED_COLOUR);
        StringBuilder about = new StringBuilder();
        if (group.startsAt() != null) {
            long epoch = group.startsAt().getEpochSecond();
            about.append("**When:** <t:").append(epoch).append(":F> (<t:").append(epoch).append(":R>)\n");
        }
        about.append("**Host:** ").append(name(nameOf.apply(group.hostId()))).append('\n');
        about.append("**Places:** ").append(filled).append(" of ").append(places).append(filled >= places ? " (full)" : "");
        if (group.note() != null && !group.note().isBlank()) about.append("\n\n").append(MarkdownSanitizer.escape(group.note()));
        b.setDescription(about.toString());
        for (QuickGroups.Slot slot : slots) {
            List<String> who = new ArrayList<>();
            for (Member m : members) {
                if (m.role() != null && m.role().equalsIgnoreCase(slot.role())) who.add(name(nameOf.apply(m.userId())));
            }
            String value = who.isEmpty() ? "Open" : String.join(", ", who);
            if (value.length() > 1000) value = value.substring(0, 1000) + "…";
            b.addField(MarkdownSanitizer.escape(slot.role()) + " (" + taken.getOrDefault(slot.role(), 0) + "/" + slot.places() + ")", value, true);
        }
        b.setFooter(group.open() ? (link != null ? "Take a place on the clan site (title link)." : "Take a place on the clan site.") : "This group is closed.");
        return b.build();
    }

    private static String name(String raw) {
        return raw == null || raw.isBlank() ? "A member" : MarkdownSanitizer.escape(raw);
    }
}
