package com.runeclans.bot.commands.signup;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.discord.Pagination;
import com.runeclans.bot.signup.SignupStore;
import com.runeclans.bot.util.TtlCache;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@BService
public class SignupService {
    private static final Logger log = LoggerFactory.getLogger(SignupService.class);

    /**
     * How long a lookup that found no open signup is remembered. A stale button clicked over and over then costs one query per window,
     * and a signup another process makes (the website's {@code api} process) is never hidden for longer than this.
     */
    static final Duration MISS_TTL = Duration.ofSeconds(10);

    // Postgres is the source of truth; this map is a cache of the open signups (warmed at start-up, filled on a miss by
    // getSessionById, so a signup made by another process works without a restart).
    // ConcurrentHashMap: JDA/BotCommands can dispatch interaction callbacks (button clicks,
    // modal submits) from a pooled executor rather than a single thread, so this map can be
    // read/written concurrently from separate signups' interactions.
    private final Map<Long, SignupSession> activeSignupsById = new ConcurrentHashMap<>();
    private final TtlCache<Long, Boolean> recentMisses = new TtlCache<>(MISS_TTL);
    // Keyed by the Discord user building it — one in-progress /signupbuilder SUBMISSION draft per
    // user at a time. Never persisted: abandoned drafts just sit here harmlessly until finished,
    // cancelled, or the bot restarts (same tradeoff TeamformingService makes for its own staged state).
    private final Map<Long, SubmissionDraft> submissionDraftsByUserId = new ConcurrentHashMap<>();
    private final SignupStore signupRepository;

    /** {@code signupRepository}: the bot's own tables or the hub, by the {@code hub-community} switch ({@code hubcommunity.CommunityStores}). */
    public SignupService(SignupStore signupRepository) {
        this.signupRepository = signupRepository;
        loadOpenSignupsFromDatabase();
    }

    private void loadOpenSignupsFromDatabase() {
        List<SignupSession> signups = signupRepository.getOpenSignups();

        for (SignupSession signup : signups) {
            activeSignupsById.put(signup.signupId(), signup);
            log.info("Loaded {} signup {} '{}' for guild {} from database.",
                    signup.type(), signup.signupId(), signup.title(), signup.guildId());
        }
    }

    /**
     * The open signup with this id, or null. A cache miss reads the one row from the database (load-through), so a signup made by
     * another process (the website) works here without a restart. A miss that finds nothing is remembered for {@link #MISS_TTL} only.
     */
    public SignupSession getSessionById(long signupId) {
        SignupSession cached = activeSignupsById.get(signupId);
        return cached != null ? cached : loadThrough(signupId);
    }

    private SignupSession loadThrough(long signupId) {
        if (recentMisses.get(signupId).isPresent()) return null;
        SignupSession stored;
        try {
            stored = signupRepository.getOpenSignupById(signupId);
        } catch (RuntimeException e) {
            log.warn("Could not look up signup {} after a cache miss", signupId, e);
            return null; // a failed read is not remembered
        }
        if (stored == null) {
            recentMisses.put(signupId, Boolean.TRUE);
            return null;
        }
        SignupSession raced = activeSignupsById.putIfAbsent(signupId, stored);
        return raced != null ? raced : stored;
    }

    /** Keeps the open signups a list query just read, so the per-signup lookups that follow it don't each go back to the database. */
    private List<SignupSession> remember(List<SignupSession> open) {
        for (SignupSession session : open) activeSignupsById.putIfAbsent(session.signupId(), session);
        return open;
    }

    public SignupSession getSessionFromComponentId(String componentId) {
        long signupId = parseSignupId(componentId);
        return getSessionById(signupId);
    }

    public long parseSignupId(String componentId) {
        String[] parts = componentId.split(":");

        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid signup component ID: " + componentId);
        }

        return Long.parseLong(parts[1]);
    }

    // --- Creation ---

    public void createQueueSession(Guild guild, TextChannel signupChannel, TextChannel adminChannel,
                                    String title, String notificationMessage, Integer maxSignups,
                                    long createdByUserId) {
        createQueueSignup(guild, signupChannel, adminChannel, title, notificationMessage, maxSignups, createdByUserId);
    }

    /** Creates a queue signup and posts its panels, returning its id (used by events, which attach a signup to themselves). */
    public long createQueueSignup(Guild guild, TextChannel signupChannel, TextChannel adminChannel,
                                  String title, String notificationMessage, Integer maxSignups,
                                  long createdByUserId) {
        long signupId = signupRepository.createSignup(
                guild.getIdLong(), title, SignupType.QUEUE,
                notificationMessage, null, maxSignups, createdByUserId, null
        );

        SignupSession session = new SignupSession(
                signupId, guild.getIdLong(), title, notificationMessage,
                maxSignups, SignupType.QUEUE, null, null
        );

        activeSignupsById.put(signupId, session);
        postNewPanels(guild, signupChannel, adminChannel, session);
        log.info("Created QUEUE signup {} '{}' in guild {}", signupId, title, guild.getIdLong());
        return signupId;
    }

    public void createGroupSession(Guild guild, TextChannel signupChannel, TextChannel adminChannel,
                                    String title, long createdByUserId) {
        String roleName = title.length() > 100 ? title.substring(0, 100) : title;

        guild.createRole()
                .setName(roleName)
                .setMentionable(true)
                .queue(role -> {
                    long roleId = role.getIdLong();

                    long signupId = signupRepository.createSignup(
                            guild.getIdLong(), title, SignupType.GROUP,
                            null, null, null, createdByUserId, roleId
                    );

                    SignupSession session = new SignupSession(
                            signupId, guild.getIdLong(), title, null,
                            null, SignupType.GROUP, null, roleId
                    );

                    activeSignupsById.put(signupId, session);
                    postNewPanels(guild, signupChannel, adminChannel, session);
                    log.info("Created GROUP signup {} '{}' with role {} in guild {}",
                            signupId, title, roleId, guild.getIdLong());

                }, failure -> log.error("Failed to create Discord role for GROUP signup '{}'", title, failure));
    }

    public void createSubmissionSession(Guild guild, TextChannel signupChannel, TextChannel adminChannel,
                                         String title, List<SubmissionField> fields, Integer maxEntries,
                                         long createdByUserId) {
        String serializedFields = SubmissionField.serialize(fields);

        long signupId = signupRepository.createSignup(
                guild.getIdLong(), title, SignupType.SUBMISSION,
                null, serializedFields, maxEntries, createdByUserId, null
        );

        SignupSession session = new SignupSession(
                signupId, guild.getIdLong(), title, null,
                maxEntries, SignupType.SUBMISSION, serializedFields, null
        );

        activeSignupsById.put(signupId, session);
        postNewPanels(guild, signupChannel, adminChannel, session);
        log.info("Created SUBMISSION signup {} '{}' with {} field(s) in guild {}",
                signupId, title, fields.size(), guild.getIdLong());
    }

    private void postNewPanels(Guild guild, TextChannel signupChannel, TextChannel adminChannel,
                                SignupSession session) {
        long signupId = session.signupId();

        signupChannel.sendMessageComponents(List.of(buildPublicContainer(session)))
                .useComponentsV2(true)
                .queue(publicMessage -> {
                    signupRepository.saveMessage(signupId, guild.getIdLong(),
                            signupChannel.getIdLong(), publicMessage.getIdLong(), "PUBLIC");

                    adminChannel.sendMessageComponents(List.of(buildAdminContainer(session)))
                            .useComponentsV2(true)
                            .queue(adminMessage -> signupRepository.saveMessage(
                                    signupId, guild.getIdLong(),
                                    adminChannel.getIdLong(), adminMessage.getIdLong(), "ADMIN"));
                });
    }

    // --- /signupbuilder SUBMISSION drafts ---

    // No Discord event fires when a user dismisses the ephemeral message a draft lives behind, so
    // an abandoned draft has no natural end — this bounds how long one lingers in memory instead.
    private static final Duration DRAFT_TTL = Duration.ofMinutes(10);

    public void startSubmissionDraft(long userId, long guildId, long adminChannelId, long publicChannelId,
                                      String title, Integer maxEntries) {
        submissionDraftsByUserId.put(userId, new SubmissionDraft(
                guildId, adminChannelId, publicChannelId, title, maxEntries, List.of(), Instant.now()));
    }

    /** Returns {@code null} (and evicts) if there's no draft for this user, or it's gone stale past {@link #DRAFT_TTL}. */
    public SubmissionDraft getSubmissionDraft(long userId) {
        SubmissionDraft draft = submissionDraftsByUserId.get(userId);
        if (draft == null) return null;

        if (Duration.between(draft.lastTouchedAt(), Instant.now()).compareTo(DRAFT_TTL) > 0) {
            submissionDraftsByUserId.remove(userId);
            return null;
        }

        return draft;
    }

    /** Returns false without changing anything if there's no live draft for this user, or it's already full (3 fields). */
    public boolean addDraftField(long userId, SubmissionField field) {
        SubmissionDraft draft = getSubmissionDraft(userId); // staleness-checked
        if (draft == null || draft.fields().size() >= 3) return false;

        submissionDraftsByUserId.put(userId, draft.withField(field));
        return true;
    }

    public void cancelSubmissionDraft(long userId) {
        submissionDraftsByUserId.remove(userId);
    }

    /** Signups live on the RuneClans hub right now ({@code hub-community}): the hub keeps them and runs their upkeep. */
    public boolean onHub() {
        return signupRepository.hub();
    }

    /** Forgets a sheet this process has cached (the hub feed said it changed), so the next look reads it again. */
    public void forget(long signupId) {
        activeSignupsById.remove(signupId);
        recentMisses.remove(signupId);
    }

    // --- Status ---

    public boolean isSignupActive(long signupId) {
        String status = signupRepository.getSignupStatus(signupId);
        return "ACTIVE".equalsIgnoreCase(status);
    }

    public String togglePause(long signupId) {
        String currentStatus = signupRepository.getSignupStatus(signupId);
        if (currentStatus == null) return null;

        String newStatus = "ACTIVE".equalsIgnoreCase(currentStatus) ? "PAUSED" : "ACTIVE";
        signupRepository.setSignupStatus(signupId, newStatus);

        SignupSession session = getSessionById(signupId);
        if (session != null) {
            log.info("Toggled signup {} '{}' to {}", signupId, session.title(), newStatus);
        }

        return newStatus;
    }

    // --- Entry management ---

    public boolean addUser(Guild guild, long signupId, long userId, String rsn, String submissionValue) {
        SignupSession session = getSessionById(signupId);
        if (session == null) return false;
        if (!isSignupActive(signupId)) return false;

        if (session.maxSignups() != null
                && signupRepository.getEntryCount(signupId) >= session.maxSignups()) {
            return false;
        }

        boolean added = signupRepository.addEntry(signupId, userId, rsn, submissionValue, userId, session.type());

        if (added && session.type() == SignupType.GROUP && session.groupRoleId() != null) {
            assignGroupRole(guild, session.groupRoleId(), userId);
        }

        return added;
    }

    public boolean addManualUser(Guild guild, long signupId, long userId, String rsn,
                                  String submissionValue, long addedByUserId) {
        SignupSession session = getSessionById(signupId);
        if (session == null) return false;

        if (session.maxSignups() != null
                && signupRepository.getEntryCount(signupId) >= session.maxSignups()) {
            return false;
        }

        boolean added = signupRepository.addEntry(signupId, userId, rsn, submissionValue, addedByUserId, session.type());

        if (added && session.type() == SignupType.GROUP && session.groupRoleId() != null) {
            assignGroupRole(guild, session.groupRoleId(), userId);
        }

        return added;
    }

    public boolean removeUser(Guild guild, long signupId, long userId) {
        SignupSession session = getSessionById(signupId);
        if (session == null) return false;

        boolean removed = signupRepository.removeEntryByUserId(signupId, userId);

        if (removed && session.type() == SignupType.GROUP && session.groupRoleId() != null) {
            removeGroupRole(guild, session.groupRoleId(), userId);
        }

        return removed;
    }

    public List<SignupEntry> getEntries(long signupId) {
        if (getSessionById(signupId) == null) return List.of();
        return signupRepository.getEntries(signupId);
    }

    public List<SignupSession> getVisibleSignups(long guildId) {
        List<SignupSession> open = remember(signupRepository.getVisibleSignups(guildId));
        // Deleted by another process: drop them here too (a later lookup reads the row again, so a race here only costs a query).
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (SignupSession session : open) ids.add(session.signupId());
        activeSignupsById.values().removeIf(session -> session.guildId() == guildId && !ids.contains(session.signupId()));
        return open;
    }

    public List<SignupSession> searchVisibleSignups(long guildId, String query) {
        return remember(signupRepository.searchVisibleSignups(guildId, query));
    }

    public String getSignupStatus(long signupId) {
        return signupRepository.getSignupStatus(signupId);
    }

    // --- Queue-only operations ---

    public SignupEntry getFirst(long signupId) {
        if (getSessionById(signupId) == null) return null;
        List<SignupEntry> entries = signupRepository.getEntries(signupId);
        return entries.isEmpty() ? null : entries.getFirst();
    }

    public SignupEntry next(long signupId) {
        if (getSessionById(signupId) == null) return null;
        return signupRepository.next(signupId);
    }

    public SignupEntry skip(long signupId) {
        if (getSessionById(signupId) == null) return null;
        return signupRepository.skipFirst(signupId);
    }

    public SignupEntry remove(long signupId) {
        if (getSessionById(signupId) == null) return null;
        return signupRepository.removeFirst(signupId);
    }

    public void clear(Guild guild, long signupId) {
        SignupSession session = getSessionById(signupId);
        if (session == null) return;

        if (session.type() == SignupType.GROUP && session.groupRoleId() != null) {
            Role role = guild.getRoleById(session.groupRoleId());
            if (role != null) {
                for (SignupEntry entry : signupRepository.getEntries(signupId)) {
                    guild.removeRoleFromMember(UserSnowflake.fromId(entry.userId()), role).queue(
                            null, failure -> log.warn("Could not remove group role from user {}", entry.userId()));
                }
            }
        }

        signupRepository.clearEntries(signupId);
    }

    // --- Random pick ---

    /** Picks a random entry without removing it or closing the signup. */
    public SignupEntry pickRandom(long signupId) {
        if (getSessionById(signupId) == null) return null;

        List<SignupEntry> entries = signupRepository.getEntries(signupId);
        if (entries.isEmpty()) return null;

        return entries.get(ThreadLocalRandom.current().nextInt(entries.size()));
    }

    private static final Color COLOR_GOLD = new Color(255, 215, 0);

    /** Builds the winner announcement container posted in the public channel. */
    public Container buildWinnerContainer(SignupSession session, SignupEntry winner) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### 🎉 " + session.title() + " — Winner!"));

        switch (session.type()) {
            case GROUP -> children.add(TextDisplay.of(
                    "**<@" + winner.userId() + ">** has been selected!\n\nCongratulations! 🎊"));

            case SUBMISSION -> {
                List<SubmissionField> fields = SubmissionField.deserialize(session.submissionFields());
                List<String> values = SubmissionField.parseValues(winner.submissionValue());

                StringBuilder desc = new StringBuilder(
                        "**<@" + winner.userId() + ">** was randomly selected!\n\n");

                String firstImageUrl = null;

                for (int i = 0; i < fields.size(); i++) {
                    SubmissionField field = fields.get(i);
                    String value = i < values.size() ? values.get(i) : "";

                    if (value.isBlank() && !field.required()) continue;

                    if (SubmissionField.TYPE_IMAGE.equals(field.type())) {
                        desc.append("**").append(field.label()).append(":** ")
                                .append("[View Image](").append(value).append(")\n");
                        if (firstImageUrl == null && !value.isBlank()) firstImageUrl = value;
                    } else if (SubmissionField.TYPE_LINK.equals(field.type())) {
                        desc.append("**").append(field.label()).append(":** ")
                                .append("[Link](").append(value).append(")\n");
                    } else {
                        desc.append("**").append(field.label()).append(":** ")
                                .append(value.isBlank() ? "*(not provided)*" : value).append("\n");
                    }
                }

                desc.append("\nCongratulations! 🎊");
                children.add(TextDisplay.of(desc.toString()));

                // Show the first image field inline in the winner card
                if (firstImageUrl != null) {
                    children.add(MediaGallery.of(MediaGalleryItem.fromUrl(firstImageUrl)));
                }
            }

            default -> children.add(TextDisplay.of("**<@" + winner.userId() + ">** was selected! 🎊"));
        }

        return Containers.card(COLOR_GOLD, children);
    }

    // --- Delete ---

    public void deleteSignup(Guild guild, long signupId) {
        SignupSession session = getSessionById(signupId);
        if (session == null) return;

        if (session.type() == SignupType.GROUP && session.groupRoleId() != null) {
            Role role = guild.getRoleById(session.groupRoleId());
            if (role != null) {
                for (SignupEntry entry : signupRepository.getEntries(signupId)) {
                    guild.removeRoleFromMember(UserSnowflake.fromId(entry.userId()), role).queue(
                            null, failure -> log.warn("Could not remove group role from user {} on delete", entry.userId()));
                }
                role.delete().queue(
                        success -> log.info("Deleted group role {} for signup {}", session.groupRoleId(), signupId),
                        failure -> log.warn("Could not delete group role {} for signup {}", session.groupRoleId(), signupId));
            }
        }

        deleteMessages(guild, signupId);

        signupRepository.deleteSignupForAdminArchive(signupId);
        activeSignupsById.remove(signupId);
        log.info("Deleted signup {} '{}'", signupId, session.title());
    }

    /**
     * A sheet deleted somewhere else (the website, with {@code hub-community} on): the hub has no sheet left to read, so this takes its
     * Discord messages down without looking it up, and forgets it.
     */
    public void removeDeleted(Guild guild, long signupId) {
        SignupSession cached = activeSignupsById.get(signupId);
        if (cached != null && cached.type() == SignupType.GROUP && cached.groupRoleId() != null) {
            Role role = guild.getRoleById(cached.groupRoleId());
            if (role != null) role.delete().queue(null, failure -> log.warn("Could not delete group role {} for signup {}", cached.groupRoleId(), signupId));
        }
        deleteMessages(guild, signupId);
        forget(signupId);
    }

    private void deleteMessages(Guild guild, long signupId) {
        List<SignupMessage> messages = signupRepository.getActiveMessages(signupId);

        for (SignupMessage signupMessage : messages) {
            TextChannel channel = guild.getTextChannelById(signupMessage.channelId());
            final long messageId = signupMessage.messageId();

            if (channel == null) {
                signupRepository.markMessageInactive(messageId);
                continue;
            }

            channel.deleteMessageById(messageId).queue(
                    success -> signupRepository.markMessageInactive(messageId),
                    failure -> {
                        signupRepository.markMessageInactive(messageId);
                        log.warn("Could not delete signup message {} ({})", messageId, signupMessage.messageType());
                    }
            );
        }
    }

    // --- Maintenance (dev bulk-close, auto-close, purge) ---

    /** Closes every currently-tracked active signup, across every guild the bot can still resolve. Returns how many. */
    public int closeAllActiveSignups(JDA jda) {
        int count = 0;
        for (SignupSession session : List.copyOf(activeSignupsById.values())) {
            Guild guild = Shards.guild(jda, session.guildId());
            if (guild == null) continue;
            deleteSignup(guild, session.signupId());
            count++;
        }
        return count;
    }

    /** Closes signups with no new entries since before {@code cutoff}. Returns how many were closed. */
    public int autoCloseInactiveSignups(JDA jda, Instant cutoff) {
        int count = 0;
        for (SignupSession session : signupRepository.getInactiveSignups(cutoff)) {
            Guild guild = Shards.guild(jda, session.guildId());
            if (guild == null) continue;
            deleteSignup(guild, session.signupId());
            count++;
            log.info("Auto-closed signup {} '{}' in guild {} — inactive since before {}",
                    session.signupId(), session.title(), session.guildId(), cutoff);
        }
        return count;
    }

    /** Hard-deletes signups soft-deleted before {@code cutoff}. Returns how many were purged. */
    public int purgeOldDeletedSignups(Instant cutoff) {
        return signupRepository.purgeDeletedBefore(cutoff);
    }

    /** All currently-tracked active signups, across every guild. Used by the dev-only cross-server list. */
    public List<SignupSession> getAllActiveSignups() {
        return List.copyOf(activeSignupsById.values());
    }

    public void deletePingMessages(Guild guild, long signupId) {
        List<SignupMessage> pings = signupRepository.getActivePingMessages(signupId);
        for (SignupMessage msg : pings) {
            TextChannel channel = guild.getTextChannelById(msg.channelId());
            final long messageId = msg.messageId();
            if (channel == null) {
                signupRepository.markMessageInactive(messageId);
                continue;
            }
            channel.deleteMessageById(messageId).queue(
                    success -> signupRepository.markMessageInactive(messageId),
                    failure -> {
                        signupRepository.markMessageInactive(messageId);
                        log.warn("Could not delete ping message {}", messageId);
                    }
            );
        }
    }

    public void savePingMessage(long signupId, long guildId, long channelId, long messageId) {
        signupRepository.saveMessage(signupId, guildId, channelId, messageId, "PING");
    }

    // --- Panel posting ---

    public SignupMessage getFirstActivePublicMessage(long signupId) {
        if (getSessionById(signupId) == null) return null;
        return signupRepository.getFirstActivePublicMessage(signupId);
    }

    public void postSignupEmbed(Guild guild, TextChannel channel, long signupId, SignupPanelType panelType) {
        SignupSession session = getSessionById(signupId);

        if (session == null) {
            throw new IllegalArgumentException("No active signup found for ID: " + signupId);
        }

        if (panelType == SignupPanelType.ADMIN) {
            channel.sendMessageComponents(List.of(buildAdminContainer(session)))
                    .useComponentsV2(true)
                    .queue(message -> {
                        signupRepository.saveMessage(signupId, guild.getIdLong(),
                                channel.getIdLong(), message.getIdLong(), "ADMIN");
                        log.info("Posted additional ADMIN panel {} for signup {} in channel {}",
                                message.getIdLong(), signupId, channel.getIdLong());
                    });
            return;
        }

        channel.sendMessageComponents(List.of(buildPublicContainer(session)))
                .useComponentsV2(true)
                .queue(message -> {
                    signupRepository.saveMessage(signupId, guild.getIdLong(),
                            channel.getIdLong(), message.getIdLong(), "PUBLIC");
                    log.info("Posted additional PUBLIC panel {} for signup {} in channel {}",
                            message.getIdLong(), signupId, channel.getIdLong());
                });
    }

    public void updateMessages(Guild guild, long signupId) {
        SignupSession session = getSessionById(signupId);
        if (session == null) return;

        List<SignupMessage> messages = signupRepository.getActiveMessages(signupId);

        for (SignupMessage signupMessage : messages) {
            TextChannel channel = guild.getTextChannelById(signupMessage.channelId());

            if (channel == null) {
                signupRepository.markMessageInactive(signupMessage.messageId());
                continue;
            }

            channel.retrieveMessageById(signupMessage.messageId())
                    .queue(
                            message -> {
                                if ("ADMIN".equalsIgnoreCase(signupMessage.messageType())) {
                                    message.editMessageComponents(List.of(buildAdminContainer(session)))
                                            .useComponentsV2(true)
                                            .queue();
                                } else {
                                    message.editMessageComponents(List.of(buildPublicContainer(session)))
                                            .useComponentsV2(true)
                                            .queue();
                                }
                            },
                            failure -> {
                                signupRepository.markMessageInactive(signupMessage.messageId());
                                log.warn("Signup message {} could not be retrieved and was marked inactive.",
                                        signupMessage.messageId());
                            }
                    );
        }
    }

    // --- Panel containers ---

    public Container buildPublicContainer(SignupSession session) {
        String status = signupRepository.getSignupStatus(session.signupId());
        Color color = "PAUSED".equalsIgnoreCase(status) ? Color.RED : Color.GREEN;

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + session.title()));
        children.addAll(buildEntriesSection(session));
        children.add(TextDisplay.of("-# " + buildFooterText(session, status)));
        children.add(buildPublicActionRow(session));

        return Containers.card(color, children);
    }

    public Container buildAdminContainer(SignupSession session) {
        String status = signupRepository.getSignupStatus(session.signupId());
        Color color = "PAUSED".equalsIgnoreCase(status) ? Color.RED : Color.GREEN;

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + session.title() + " - Admin Controls"));
        children.addAll(buildEntriesSection(session));
        children.add(TextDisplay.of("-# " + buildFooterText(session, status)));
        children.addAll(buildAdminActionRows(session));

        return Containers.card(color, children);
    }

    /**
     * The entries block shown inline on a panel: a header naming what's listed, up to one page
     * worth of entries, and — only when there's more than one page — a count line plus a "View
     * Full List" button that opens {@link #buildEntriesListContainer} as a private, per-viewer
     * pager (see {@code SignupInteractionListener#handleViewFullList}) instead of trying to cram
     * everything into the shared panel.
     */
    private List<ContainerChildComponent> buildEntriesSection(SignupSession session) {
        List<SignupEntry> entries = signupRepository.getEntries(session.signupId());

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        if (session.type() == SignupType.SUBMISSION) {
            String fieldsLine = buildFieldsLine(session);
            if (fieldsLine != null) children.add(TextDisplay.of(fieldsLine));
        }

        children.add(TextDisplay.of("**" + entriesHeaderLabel(session.type()) + "** (" + entries.size() + ")"));

        if (entries.isEmpty()) {
            children.add(TextDisplay.of(emptyEntriesMessage(session.type())));
        } else {
            var page = Pagination.paginate(entries, 0);
            children.add(TextDisplay.of(formatEntries(session, page.items(), 0)));

            if (!page.isSinglePage()) {
                children.add(TextDisplay.of("-# Showing " + page.items().size() + " of " + entries.size() + "."));
                children.add(ActionRow.of(Button.secondary("signup_view_full:" + session.signupId(), "View Full List")));
            }
        }

        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        return children;
    }

    /** The private, paginated full-entries view opened from "View Full List" — see {@code SignupInteractionListener#handleViewFullList}. */
    public Container buildEntriesListContainer(SignupSession session, int pageIndex) {
        List<SignupEntry> entries = signupRepository.getEntries(session.signupId());
        var page = Pagination.paginate(entries, pageIndex);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + session.title() + " — " + entriesHeaderLabel(session.type())));
        children.add(TextDisplay.of("-# This is a private view only you can see — paging here doesn't affect the shared panel."));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        if (entries.isEmpty()) {
            children.add(TextDisplay.of(emptyEntriesMessage(session.type())));
        } else {
            children.add(TextDisplay.of(formatEntries(session, page.items(), page.pageIndex() * Pagination.DEFAULT_PAGE_SIZE)));
        }

        if (!page.isSinglePage()) {
            children.add(Pagination.navRow(page, "signup_view_full_page:" + session.signupId() + ":"));
        }

        return Containers.card(Containers.INFO, children);
    }

    private String entriesHeaderLabel(SignupType type) {
        return switch (type) {
            case QUEUE -> "Queue";
            case GROUP -> "Members";
            case SUBMISSION -> "Submissions";
        };
    }

    private String emptyEntriesMessage(SignupType type) {
        return switch (type) {
            case QUEUE -> "*Nobody is signed up yet.*";
            case GROUP -> "*No members yet.*";
            case SUBMISSION -> "*No submissions yet.*";
        };
    }

    private String formatEntries(SignupSession session, List<SignupEntry> entries, int startIndex) {
        return switch (session.type()) {
            case QUEUE      -> formatQueueEntries(entries, startIndex);
            case GROUP      -> formatGroupEntries(entries);
            case SUBMISSION -> formatSubmissionEntries(session, entries, startIndex);
        };
    }

    private String formatQueueEntries(List<SignupEntry> entries, int startIndex) {
        StringBuilder sb = new StringBuilder();
        int position = startIndex + 1;
        for (SignupEntry entry : entries) {
            sb.append("**").append(position++).append(".** ")
                    .append(entry.username())
                    .append(" — <@").append(entry.userId()).append(">\n");
        }
        return sb.toString();
    }

    private String formatGroupEntries(List<SignupEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (SignupEntry entry : entries) {
            sb.append("• <@").append(entry.userId()).append(">\n");
        }
        return sb.toString();
    }

    private String formatSubmissionEntries(SignupSession session, List<SignupEntry> entries, int startIndex) {
        List<SubmissionField> fields = SubmissionField.deserialize(session.submissionFields());
        StringBuilder sb = new StringBuilder();
        int position = startIndex + 1;

        for (SignupEntry entry : entries) {
            List<String> values = SubmissionField.parseValues(entry.submissionValue());

            sb.append("**").append(position++).append(".** <@").append(entry.userId()).append(">\n");

            for (int i = 0; i < fields.size(); i++) {
                SubmissionField field = fields.get(i);
                String value = i < values.size() ? values.get(i) : "";

                if (value.isBlank() && !field.required()) continue;

                sb.append("   **").append(field.label()).append(":** ");

                if (SubmissionField.TYPE_IMAGE.equals(field.type())) {
                    sb.append("[View Image](").append(value).append(")");
                } else if (SubmissionField.TYPE_LINK.equals(field.type())) {
                    sb.append("[Link](").append(value).append(")");
                } else {
                    sb.append(value.isBlank() ? "*(not provided)*" : value);
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private String buildFieldsLine(SignupSession session) {
        List<SubmissionField> fields = SubmissionField.deserialize(session.submissionFields());
        if (fields.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("**Fields:** ");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(" • ");
            sb.append(fields.get(i).label());
            String meta = buildFieldMeta(fields.get(i));
            if (!meta.isEmpty()) sb.append(" *(").append(meta).append(")*");
        }
        return sb.toString();
    }

    private String buildFieldMeta(SubmissionField field) {
        boolean nonText = !SubmissionField.TYPE_TEXT.equals(field.type());
        if (nonText && !field.required()) return field.type().toLowerCase() + ", optional";
        if (nonText) return field.type().toLowerCase();
        if (!field.required()) return "optional";
        return "";
    }

    private String buildFooterText(SignupSession session, String status) {
        String limitPart = session.maxSignups() == null ? "No limit" : "Limit: " + session.maxSignups();
        return limitPart + " • Status: " + status + " • Type: " + session.type().name();
    }

    // --- Action row builders ---

    private ActionRow buildPublicActionRow(SignupSession session) {
        long id = session.signupId();
        return switch (session.type()) {
            case QUEUE -> ActionRow.of(
                    Button.primary("signup_join:" + id, "Sign up"),
                    Button.danger("signup_leave:" + id, "Leave Queue")
            );
            case GROUP -> ActionRow.of(
                    Button.success("signup_join:" + id, "Join Group"),
                    Button.danger("signup_leave:" + id, "Leave Group")
            );
            case SUBMISSION -> ActionRow.of(
                    Button.primary("signup_join:" + id, "Submit"),
                    Button.danger("signup_leave:" + id, "Remove Submission")
            );
        };
    }

    /**
     * Admin controls grouped by what they act on, not just crammed across two flat rows: acting
     * on the current entries/members, managing who's on the list (or the list's own status), and
     * the two big destructive actions — each group gets a small subtext header, with dividers
     * between groups.
     */
    private List<ContainerChildComponent> buildAdminActionRows(SignupSession session) {
        long id = session.signupId();
        Button pauseResume = isSignupActive(id)
                ? Button.secondary("signup_pause:" + id, "Pause")
                : Button.success("signup_pause:" + id, "Resume");

        List<ContainerChildComponent> children = new ArrayList<>();
        Separator divider = Separator.createDivider(Separator.Spacing.SMALL);

        switch (session.type()) {
            case QUEUE -> {
                children.add(TextDisplay.of("-# Queue Actions"));
                children.add(ActionRow.of(
                        Button.success("signup_next:" + id, "Next"),
                        Button.secondary("signup_skip:" + id, "Skip"),
                        Button.secondary("signup_remove:" + id, "Remove"),
                        Button.primary("signup_notify:" + id, "Notify")
                ));
                children.add(divider);
                children.add(TextDisplay.of("-# List Management"));
                children.add(ActionRow.of(
                        Button.secondary("signup_admin_add:" + id, "Add"),
                        pauseResume
                ));
                children.add(divider);
                children.add(TextDisplay.of("-# Danger Zone"));
                children.add(ActionRow.of(
                        Button.danger("signup_clear:" + id, "Clear all"),
                        Button.danger("signup_delete:" + id, "Delete")
                ));
            }
            case GROUP -> {
                children.add(TextDisplay.of("-# Member Actions"));
                children.add(ActionRow.of(
                        Button.primary("signup_notify_all:" + id, "Notify All"),
                        Button.success("signup_pick_winner:" + id, "Pick Winner")
                ));
                children.add(divider);
                children.add(TextDisplay.of("-# List Management"));
                children.add(ActionRow.of(
                        Button.secondary("signup_admin_add:" + id, "Add Member"),
                        Button.secondary("signup_admin_remove:" + id, "Remove Member"),
                        pauseResume
                ));
                children.add(divider);
                children.add(TextDisplay.of("-# Danger Zone"));
                children.add(ActionRow.of(
                        Button.danger("signup_clear:" + id, "Clear all"),
                        Button.danger("signup_delete:" + id, "Delete")
                ));
            }
            case SUBMISSION -> {
                children.add(TextDisplay.of("-# Entry Actions"));
                children.add(ActionRow.of(Button.success("signup_pick_random:" + id, "Pick Random")));
                children.add(divider);
                children.add(TextDisplay.of("-# List Management"));
                children.add(ActionRow.of(
                        Button.secondary("signup_admin_add:" + id, "Add Entry"),
                        Button.secondary("signup_admin_remove:" + id, "Remove Entry"),
                        pauseResume
                ));
                children.add(divider);
                children.add(TextDisplay.of("-# Danger Zone"));
                children.add(ActionRow.of(
                        Button.danger("signup_clear:" + id, "Clear all"),
                        Button.danger("signup_delete:" + id, "Delete")
                ));
            }
        }

        return children;
    }

    // --- Role helpers ---

    private void assignGroupRole(Guild guild, long roleId, long userId) {
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            log.warn("Group role {} not found in guild {}", roleId, guild.getIdLong());
            return;
        }
        guild.addRoleToMember(UserSnowflake.fromId(userId), role).queue(
                null,
                failure -> log.warn("Could not assign group role {} to user {}", roleId, userId, failure));
    }

    private void removeGroupRole(Guild guild, long roleId, long userId) {
        Role role = guild.getRoleById(roleId);
        if (role == null) return;
        guild.removeRoleFromMember(UserSnowflake.fromId(userId), role).queue(
                null,
                failure -> log.warn("Could not remove group role {} from user {}", roleId, userId, failure));
    }
}
