package com.runeclans.bot.runescape;

import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.internal.MemberApi;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps a linked player's Discord roles in step with whether their RSN is in the clan: adds a configured
 * "verified" role and removes a configured "unverified" role when a player link is created (called from
 * both the admin-review approval flow and the admin's manual-verify path), and swaps Member for Guest and
 * back when the clan roster changes (called from {@link ClanSyncService} for a name that joined or left).
 * <p>
 * Which "verified" role depends on whether {@code rsn} is a currently-tracked clan member (see
 * {@link ClanMemberRepository#isActiveMember}): {@link GuildSettings#verifiedClanRoleId()} if so, otherwise
 * {@link GuildSettings#verifiedNonClanRoleId()} — the latter is meant for a role something else
 * (e.g. a server join flow) usually already grants, so this only fills it in for a verified member
 * who doesn't have it yet. All three role slots (both verified variants, plus
 * {@link GuildSettings#unverifiedRoleId()}) are independently optional — configured via a role
 * dropdown under {@code /configure}'s Verification section, not typed by name, so there's no name
 * to mistype and nothing to look up by string match. A slot left unset (or pointing at a role no
 * longer in the guild) is simply skipped and logged, never guessed — same "fails closed" philosophy
 * as {@code AdminRoleFilter}.
 * <p>
 * The two roles can legitimately be the <em>same</em> role: with Guest as both the "unverified" and the
 * "not a clan member" role, a verified non-clan player keeps Guest while a clan member trades Guest for
 * Member. So the unverified role is never removed if it is the very role being granted, and both
 * changes are sent to Discord as one update — nobody is ever left holding neither role, even briefly.
 */
@BService
public class VerificationRoleSyncService {
    private static final Logger log = LoggerFactory.getLogger(VerificationRoleSyncService.class);

    private final GuildSettingsService guildSettingsService;
    private final ClanMemberRepository clanMemberRepository;
    private final PlayerLinkService linkService;

    public VerificationRoleSyncService(GuildSettingsService guildSettingsService, ClanMemberRepository clanMemberRepository,
                                       PlayerLinkService linkService) {
        this.guildSettingsService = guildSettingsService;
        this.clanMemberRepository = clanMemberRepository;
        this.linkService = linkService;
    }

    /** A link was created, or {@code rsn} just joined the clan: gives them the Member role (clan) or the verified-non-clan role, as the roster says. */
    public void syncRoles(Guild guild, long discordUserId, String rsn) {
        GuildSettings settings = guildSettingsService.getEffective(guild.getIdLong());
        boolean inClan = clanMemberRepository.isActiveMember(guild.getIdLong(), rsn);
        Long verifiedRoleId = inClan ? settings.verifiedClanRoleId() : settings.verifiedNonClanRoleId();
        Long unverifiedRoleId = settings.unverifiedRoleId();
        if (verifiedRoleId == null && unverifiedRoleId == null) return;

        apply(guild, discordUserId, rsn, (held, exists, canGive) -> plan(verifiedRoleId, unverifiedRoleId, held, exists, canGive));
    }

    /**
     * A linked RSN just left the clan: takes the Member role off and puts the verified-non-clan (Guest) role
     * on. Does nothing if the player has another linked account that is still in the clan, or if either of
     * the two roles isn't configured (taking Member away without a role to put in its place would leave
     * them with nothing).
     */
    public void syncLeftClan(Guild guild, long discordUserId) {
        long guildId = guild.getIdLong();
        boolean stillInClan = linkService.getLinksForUser(guildId, discordUserId).stream()
                .anyMatch(link -> clanMemberRepository.isActiveMember(guildId, link.rsn()));
        if (stillInClan) return;

        GuildSettings settings = guildSettingsService.getEffective(guildId);
        Long clanRoleId = settings.verifiedClanRoleId();
        Long nonClanRoleId = settings.verifiedNonClanRoleId();
        if (clanRoleId == null || nonClanRoleId == null) {
            log.info("A linked player (user {}) left the clan, but the Member and Guest roles aren't both configured in guild {} — roles left as they are.", discordUserId, guildId);
            return;
        }

        apply(guild, discordUserId, "left the clan", (held, exists, canGive) -> planLeave(clanRoleId, nonClanRoleId, held, exists, canGive));
    }

    /** Plans one member's change from the roles they hold, whether a role exists, and whether the bot may hand it out. */
    interface Planner {
        RolePlan plan(Set<Long> held, java.util.function.Predicate<Long> exists, java.util.function.Predicate<Long> canGive);
    }

    private void apply(Guild guild, long discordUserId, String why, Planner planner) {
        guild.retrieveMemberById(discordUserId).queue(member -> {
            Set<Long> held = new HashSet<>();
            for (Role role : member.getRoles()) held.add(role.getIdLong());

            // B-55: a saved role may since have been given powers or moved; the plan never hands out one that fails the shared check, and
            // then keeps the role it would have swapped out, so nobody is left holding neither
            RolePlan plan = planner.plan(held, id -> guild.getRoleById(id) != null, id -> MemberApi.safeToAssign(guild, guild.getRoleById(id)));
            plan.missing().forEach(id -> log.warn("Configured verification role {} not found in guild {}", id, guild.getIdLong()));
            plan.refused().forEach(id -> log.warn("Not giving verification role {} in guild {}: {}", id, guild.getIdLong(), MemberApi.handOutRefusal(guild, guild.getRoleById(id))));
            if (plan.add().isEmpty() && plan.remove().isEmpty()) return;

            List<Role> add = plan.add().stream().map(guild::getRoleById).toList();
            List<Role> remove = plan.remove().stream().map(guild::getRoleById).toList();
            log.info("Updating roles for user {} ({}): add {}, remove {}", discordUserId, why, plan.add(), plan.remove());
            // one request per role, never modifyMemberRoles: that writes the whole role list from this member snapshot and would undo a role
            // another feature (autorole, role keep, role rules) gave in the meantime (B-89 follow-up)
            for (Role role : add) {
                guild.addRoleToMember(member, role).reason("Verification roles").queue(success -> {},
                        error -> log.warn("Failed to give verification role {} to user {}", role.getId(), discordUserId, error));
            }
            for (Role role : remove) {
                guild.removeRoleFromMember(member, role).reason("Verification roles").queue(success -> {},
                        error -> log.warn("Failed to take verification role {} from user {}", role.getId(), discordUserId, error));
            }
        }, error -> log.warn("Failed to retrieve member {} for verification role sync", discordUserId, error));
    }

    /**
     * What to change for one member: role ids to add, role ids to remove, configured ids that no longer exist in the guild, and configured
     * ids the bot won't hand out ({@code MemberApi.safeToAssign}).
     */
    record RolePlan(Set<Long> add, Set<Long> remove, Set<Long> missing, Set<Long> refused) {
        RolePlan(Set<Long> add, Set<Long> remove, Set<Long> missing) {
            this(add, remove, missing, Set.of());
        }
    }

    /**
     * Pure decision, no Discord calls. The verified role is added if the member lacks it; the unverified
     * role is removed if the member holds it — unless it is the same role as the verified one, which is
     * simply kept. A configured role that no longer exists is skipped and reported, never guessed at.
     */
    static RolePlan plan(Long verifiedRoleId, Long unverifiedRoleId, Set<Long> memberRoleIds, java.util.function.Predicate<Long> roleExists) {
        return plan(verifiedRoleId, unverifiedRoleId, memberRoleIds, roleExists, id -> true);
    }

    /** As above; a verified role the bot won't hand out is not added, and then the unverified role is kept too. */
    static RolePlan plan(Long verifiedRoleId, Long unverifiedRoleId, Set<Long> memberRoleIds, java.util.function.Predicate<Long> roleExists,
                         java.util.function.Predicate<Long> canGive) {
        Set<Long> add = new HashSet<>();
        Set<Long> remove = new HashSet<>();
        Set<Long> missing = new HashSet<>();
        Set<Long> refused = new HashSet<>();

        if (verifiedRoleId != null) {
            if (!roleExists.test(verifiedRoleId)) missing.add(verifiedRoleId);
            else if (!memberRoleIds.contains(verifiedRoleId) && !canGive.test(verifiedRoleId)) refused.add(verifiedRoleId);
            else if (!memberRoleIds.contains(verifiedRoleId)) add.add(verifiedRoleId);
        }
        if (!refused.isEmpty()) return new RolePlan(add, remove, missing, refused);
        if (unverifiedRoleId != null) {
            if (!roleExists.test(unverifiedRoleId)) missing.add(unverifiedRoleId);
            else if (memberRoleIds.contains(unverifiedRoleId) && !unverifiedRoleId.equals(verifiedRoleId)) remove.add(unverifiedRoleId);
        }
        return new RolePlan(add, remove, missing);
    }

    /**
     * Pure decision for a player who left the clan: the non-clan (Guest) role is added if they lack it, and
     * the clan (Member) role is removed if they hold it — but only when the Guest role exists to take its
     * place, and never when the two are the same role. The unverified role is not touched: someone who
     * linked an account is verified whether or not they are in the clan.
     */
    static RolePlan planLeave(Long clanRoleId, Long nonClanRoleId, Set<Long> memberRoleIds, java.util.function.Predicate<Long> roleExists) {
        return planLeave(clanRoleId, nonClanRoleId, memberRoleIds, roleExists, id -> true);
    }

    /** As above; a Guest role the bot won't hand out changes nothing, so the Member role is not taken away with nothing in its place. */
    static RolePlan planLeave(Long clanRoleId, Long nonClanRoleId, Set<Long> memberRoleIds, java.util.function.Predicate<Long> roleExists,
                              java.util.function.Predicate<Long> canGive) {
        Set<Long> add = new HashSet<>();
        Set<Long> remove = new HashSet<>();
        Set<Long> missing = new HashSet<>();
        if (clanRoleId == null || nonClanRoleId == null) return new RolePlan(add, remove, missing);

        if (!roleExists.test(clanRoleId)) missing.add(clanRoleId);
        if (!roleExists.test(nonClanRoleId)) {
            missing.add(nonClanRoleId);
            return new RolePlan(add, remove, missing);
        }

        if (!memberRoleIds.contains(nonClanRoleId) && !canGive.test(nonClanRoleId)) return new RolePlan(add, remove, missing, Set.of(nonClanRoleId));
        if (!memberRoleIds.contains(nonClanRoleId)) add.add(nonClanRoleId);
        if (roleExists.test(clanRoleId) && memberRoleIds.contains(clanRoleId) && !clanRoleId.equals(nonClanRoleId)) remove.add(clanRoleId);
        return new RolePlan(add, remove, missing);
    }
}
