package com.runeclans.bot.runescape;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class RuneScapeDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(RuneScapeDatabaseInitializer.class);

    public RuneScapeDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "runescape", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // One confirmed RSN <-> Discord link per (guild, discord user). A member could
                // reasonably want more than one RSN linked (main + ironman, etc.) later, so this
                // intentionally isn't unique on discord_user_id alone.
                """
                CREATE TABLE IF NOT EXISTS younglings.player_link (
                    link_id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    discord_user_id BIGINT NOT NULL,
                    rsn TEXT NOT NULL,
                    is_primary BOOLEAN NOT NULL DEFAULT TRUE,
                    verified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    verification_method TEXT NOT NULL
                );
                """,

                // Postgres table constraints can't reference an expression like LOWER(rsn) directly
                // (only plain columns) — a unique index is the correct way to enforce this.
                """
                CREATE UNIQUE INDEX IF NOT EXISTS player_link_unique_rsn_lower
                ON younglings.player_link (guild_id, LOWER(rsn));
                """,

                """
                CREATE INDEX IF NOT EXISTS player_link_discord_user_idx
                ON younglings.player_link (guild_id, discord_user_id);
                """,

                // Tracks a member's own self-service "Poll Now" clicks under /rs specifically — kept
                // separate from snapshot_at (which every poll source touches: admin, auto-poll, this
                // one) so the 30-minute self-poll cooldown only ever reacts to the member's own
                // clicks, never reset or consumed by someone else polling them in the meantime.
                """
                ALTER TABLE younglings.player_link
                    ADD COLUMN IF NOT EXISTS last_self_poll_at TIMESTAMPTZ NULL;
                """,

                // In-progress makeover-mage verification: a random appearance is assigned, the
                // player applies it in-game, then an admin confirms it against the fetched avatar
                // image (see RsnVerificationService) before a player_link row is created.
                """
                CREATE TABLE IF NOT EXISTS younglings.player_verification_attempt (
                    attempt_id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    discord_user_id BIGINT NOT NULL,
                    rsn TEXT NOT NULL,
                    assigned_hairstyle TEXT NOT NULL,
                    assigned_hair_color TEXT NOT NULL,
                    assigned_skin_tone TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    resolved_at TIMESTAMPTZ NULL,
                    resolved_by_user_id BIGINT NULL
                );
                """,

                """
                CREATE INDEX IF NOT EXISTS player_verification_pending_idx
                ON younglings.player_verification_attempt (guild_id, status);
                """,

                // Where the request's card was posted for admin review, so the card can be edited to say how the
                // request ended (cancelled by the member, approved, rejected) instead of keeping live buttons.
                """
                ALTER TABLE younglings.player_verification_attempt
                    ADD COLUMN IF NOT EXISTS review_channel_id BIGINT NULL;
                """,

                """
                ALTER TABLE younglings.player_verification_attempt
                    ADD COLUMN IF NOT EXISTS review_message_id BIGINT NULL;
                """,

                // Periodic snapshot of a linked player's RuneMetrics profile — one row per poll
                // (manual only, see RosterPollScheduler/BotConfig#getRunescapeAutoPollEnabled),
                // so XP-gain-over-time can be derived by comparing rows rather than only ever
                // seeing the current total. skills_json is kept as a redundant denormalized copy —
                // player_skill_snapshot below is the real read path for per-skill history/queries.
                """
                CREATE TABLE IF NOT EXISTS younglings.player_stats_snapshot (
                    snapshot_id BIGSERIAL PRIMARY KEY,
                    rsn TEXT NOT NULL,
                    guild_id BIGINT NOT NULL,
                    snapshot_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    total_level INTEGER NOT NULL,
                    total_xp BIGINT NOT NULL,
                    combat_level INTEGER NOT NULL,
                    quests_complete INTEGER NOT NULL,
                    skills_json TEXT NOT NULL
                );
                """,

                """
                CREATE INDEX IF NOT EXISTS player_stats_snapshot_rsn_idx
                ON younglings.player_stats_snapshot (guild_id, LOWER(rsn), snapshot_at DESC);
                """,

                // Added after the table already existed in some environments — ADD COLUMN IF NOT
                // EXISTS instead of baking these into the CREATE TABLE above, since this
                // initializer has no other way to evolve a table that's already been created.
                """
                ALTER TABLE younglings.player_stats_snapshot
                    ADD COLUMN IF NOT EXISTS quests_started INTEGER NOT NULL DEFAULT 0;
                """,

                """
                ALTER TABLE younglings.player_stats_snapshot
                    ADD COLUMN IF NOT EXISTS quests_not_started INTEGER NOT NULL DEFAULT 0;
                """,

                // Per-skill history, one row per skill per snapshot — the normalized read path for
                // "show me Attack XP over time" style queries that a JSON blob can't do cheaply.
                """
                CREATE TABLE IF NOT EXISTS younglings.player_skill_snapshot (
                    id BIGSERIAL PRIMARY KEY,
                    snapshot_id BIGINT NOT NULL REFERENCES younglings.player_stats_snapshot(snapshot_id) ON DELETE CASCADE,
                    skill_id INTEGER NOT NULL,
                    level INTEGER NOT NULL,
                    xp BIGINT NOT NULL,
                    rank BIGINT NOT NULL
                );
                """,

                """
                CREATE INDEX IF NOT EXISTS player_skill_snapshot_snapshot_idx
                ON younglings.player_skill_snapshot (snapshot_id);
                """,

                // RuneMetrics' own "recent activities" feed (quest completions, level-ups, etc.),
                // captured every time we poll so we build up our own permanent history instead of
                // relying on Jagex's rolling feed (which only ever shows the most recent handful).
                // activity_date is kept as the API's own raw string ("23-Sep-2026 23:30") rather
                // than parsed into a timestamp — RuneScape doesn't document which timezone that's
                // in, and guessing would bake in a wrong assumption; recorded_at (when *we* saw it)
                // is what's actually reliable for our own ordering. The unique index dedupes across
                // repeated polls, since the same recent events reappear in the feed every time.
                """
                CREATE TABLE IF NOT EXISTS younglings.player_activity (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    rsn TEXT NOT NULL,
                    activity_date TEXT NOT NULL,
                    activity_text TEXT NOT NULL,
                    activity_details TEXT NOT NULL,
                    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,

                """
                CREATE UNIQUE INDEX IF NOT EXISTS player_activity_unique_idx
                ON younglings.player_activity (guild_id, LOWER(rsn), activity_date, activity_text);
                """,

                """
                CREATE INDEX IF NOT EXISTS player_activity_rsn_idx
                ON younglings.player_activity (guild_id, LOWER(rsn), recorded_at DESC);
                """,

                // The clan's roster, tracked independently of player_link — a name shows up here
                // the moment a clan sync sees it in the Clan Hiscores response, whether or not
                // anyone has ever verified that it's their own account. active flips to false (not
                // deleted) when a later sync no longer sees the name, so "left the clan" is a fact
                // you can see, not a silently vanished row.
                """
                CREATE TABLE IF NOT EXISTS younglings.clan_member (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    rsn TEXT NOT NULL,
                    clan_rank TEXT NOT NULL,
                    first_seen TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    last_seen TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    active BOOLEAN NOT NULL DEFAULT TRUE
                );
                """,

                """
                CREATE UNIQUE INDEX IF NOT EXISTS clan_member_unique_rsn_lower
                ON younglings.clan_member (guild_id, LOWER(rsn));
                """,

                // Total XP and kill count as of the last sync, straight from the Clan Hiscores CSV
                // line — kept so a rename-detection pass has a "last known" total XP for a name that
                // just disappeared from the roster, without needing a separate lookup or its own
                // history table. See RsnRenameService.
                """
                ALTER TABLE younglings.clan_member
                    ADD COLUMN IF NOT EXISTS total_xp BIGINT NOT NULL DEFAULT 0;
                """,

                """
                ALTER TABLE younglings.clan_member
                    ADD COLUMN IF NOT EXISTS kills BIGINT NOT NULL DEFAULT 0;
                """,

                // The date this member actually joined the *clan* in-game — distinct from
                // first_seen (when *we* first noticed them) and from player_link.verified_at (when
                // their Discord account got linked, which can happen long after or never). Not
                // populated automatically yet: the plan is for a future sync pass to read it off a
                // newly-appeared member's adventure log the day they're first detected (log entries
                // don't stick around forever, so this has to happen close to join time — with a
                // ~24h sync cadence, "yesterday" is the correct inferred date even when the log
                // itself is private by the time we look). Until that's built, this is set manually
                // per player via /rsadmin's Player Lookup, to backfill everyone already tracked.
                """
                ALTER TABLE younglings.clan_member
                    ADD COLUMN IF NOT EXISTS clan_joined_at DATE NULL;
                """,

                // T-593 (the site admin's Clan health page): when the Clan Hiscores' XP total last changed between two roster syncs
                // (null: no change seen yet), and when the bot started comparing it (the first sync after this column existed, or the
                // member's first sync). "No XP for N days" is read from these, never from RuneMetrics, so private profiles don't matter.
                """
                ALTER TABLE younglings.clan_member
                    ADD COLUMN IF NOT EXISTS xp_changed_at TIMESTAMPTZ NULL;
                """,

                """
                ALTER TABLE younglings.clan_member
                    ADD COLUMN IF NOT EXISTS xp_watched_since TIMESTAMPTZ NULL;
                """,

                // T-594 (the rank-up advisor): when the roster sync first saw the member's current clan rank, and their clan Hiscores XP
                // total at that sync, so "XP gained at this rank" is the total now minus this. Null until the first sync after the column
                // existed (the bot then starts counting from that sync); set again whenever the rank changes.
                """
                ALTER TABLE younglings.clan_member
                    ADD COLUMN IF NOT EXISTS rank_since TIMESTAMPTZ NULL;
                """,

                """
                ALTER TABLE younglings.clan_member
                    ADD COLUMN IF NOT EXISTS rank_xp BIGINT NULL;
                """,

                // A name that vanished from the clan roster the same sync cycle a new name appeared,
                // where XP/skill/activity evidence suggests they're the same account renamed rather
                // than one member leaving and another joining — see RsnRenameService for the
                // detection logic and RsnRenameInteractionListener for the confirm/reject buttons.
                // Kept even after resolution as an audit trail, not deleted.
                """
                CREATE TABLE IF NOT EXISTS younglings.rsn_rename_candidate (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    old_rsn TEXT NOT NULL,
                    new_rsn TEXT NOT NULL,
                    confidence TEXT NOT NULL,
                    basis TEXT NOT NULL,
                    detected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    resolved_at TIMESTAMPTZ NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING'
                );
                """,

                """
                CREATE INDEX IF NOT EXISTS rsn_rename_candidate_pending_idx
                ON younglings.rsn_rename_candidate (guild_id, status);
                """,

                // Every join/leave ClanSyncService's daily roster diff detects, recorded permanently
                // rather than only ever announced once in real time — this is what the weekly digest
                // (WeeklyDigestScheduler/WeeklyDigestRepository) queries by date range. event_type is
                // 'JOIN' or 'LEAVE', not a foreign key to anything, same "fixed small set in code"
                // reasoning used throughout this schema.
                """
                CREATE TABLE IF NOT EXISTS younglings.clan_roster_event (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    rsn TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    event_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,

                """
                CREATE INDEX IF NOT EXISTS clan_roster_event_window_idx
                ON younglings.clan_roster_event (guild_id, event_at);
                """,

                // The ordered rank ladder a guild's points system is evaluated against — a fixed
                // number of tiers per guild (seeded once, lazily, with the standard Jagex rank names
                // at rank_order 0..10; see ClanPointsRepository) but each tier's own name and
                // point_threshold are admin-editable, since Jagex clans can rename any tier and this
                // codebase never assumes clan_member.clan_rank matches one of a hardcoded list.
                """
                CREATE TABLE IF NOT EXISTS younglings.clan_rank_config (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    rank_name TEXT NOT NULL,
                    rank_order INT NOT NULL,
                    point_threshold BIGINT NOT NULL DEFAULT 0
                );
                """,

                """
                CREATE UNIQUE INDEX IF NOT EXISTS clan_rank_config_unique_order
                ON younglings.clan_rank_config (guild_id, rank_order);
                """,

                """
                CREATE UNIQUE INDEX IF NOT EXISTS clan_rank_config_unique_name_lower
                ON younglings.clan_rank_config (guild_id, LOWER(rank_name));
                """,

                // One row per guild — how many points a day of clan membership, a Citadel visit, and
                // a Citadel cap are each worth. All default to 0 (no points awarded) until an admin
                // sets them via /rsadmin's Points & Promotions panel.
                """
                CREATE TABLE IF NOT EXISTS younglings.clan_points_settings (
                    guild_id BIGINT PRIMARY KEY,
                    daily_membership_points BIGINT NOT NULL DEFAULT 0,
                    citadel_visit_points BIGINT NOT NULL DEFAULT 0,
                    citadel_cap_points BIGINT NOT NULL DEFAULT 0
                );
                """,

                // A permanent, idempotent ledger of every point award ever made — award_type is
                // 'DAILY_MEMBERSHIP', 'CITADEL_VISIT', or 'CITADEL_CAP' (a fixed small set in code,
                // same reasoning as clan_roster_event.event_type). awarded_for_date is the calendar
                // day (membership) or that Citadel week's end date (visit/cap) being credited — the
                // unique index is what makes re-running the daily job safe: awarding the same
                // guild/rsn/type/date twice is a silent no-op, not a double credit.
                """
                CREATE TABLE IF NOT EXISTS younglings.clan_points_award (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    rsn TEXT NOT NULL,
                    award_type TEXT NOT NULL,
                    points BIGINT NOT NULL,
                    awarded_for_date DATE NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,

                """
                CREATE UNIQUE INDEX IF NOT EXISTS clan_points_award_unique_idx
                ON younglings.clan_points_award (guild_id, LOWER(rsn), award_type, awarded_for_date);
                """,

                // The running total derived from clan_points_award, kept denormalized here so reading
                // a member's current total (checked every day, for every member) doesn't mean
                // re-summing the whole ledger each time. promotion_needed/promotion_needed_since track
                // whether this member's current in-game clan_rank is below what their total_points
                // have earned against clan_rank_config — see ClanPointsService, and the "Clan Report"
                // tracking group (TrackingGroup#CLAN_REPORT) that posts from this daily.
                """
                CREATE TABLE IF NOT EXISTS younglings.clan_member_points (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    rsn TEXT NOT NULL,
                    total_points BIGINT NOT NULL DEFAULT 0,
                    promotion_needed BOOLEAN NOT NULL DEFAULT FALSE,
                    promotion_needed_since DATE NULL
                );
                """,

                """
                CREATE UNIQUE INDEX IF NOT EXISTS clan_member_points_unique_rsn_lower
                ON younglings.clan_member_points (guild_id, LOWER(rsn));
                """,

                // What TrackingIconCatalog last uploaded each managed Discord application emoji with —
                // emojis are matched by name and reused across restarts (so a restart doesn't spam
                // duplicate emoji creations), but that alone means correcting a wrong icon's source
                // image and re-running IconDownloader silently does nothing: the stale emoji under that
                // same name just keeps getting reused forever. Comparing the local file's hash against
                // what's recorded here is what lets a changed file trigger a real delete-and-recreate
                // instead. Not guild-scoped — Application Emojis belong to the bot's Discord application
                // as a whole, not any one guild.
                """
                CREATE TABLE IF NOT EXISTS younglings.icon_emoji_hash (
                    emoji_name TEXT PRIMARY KEY,
                    content_hash TEXT NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,

                // --- A player belongs to JonnyBot, not to a server ---
                //
                // player_account is who owns an RS account, once, everywhere: /rs links a name to a Discord user with
                // JonnyBot, and every server that user is in sees the same account. player_link (above) stays what it was,
                // one row per server, but now means "this account is registered in this server" — a server only shows a
                // player once they've used JonnyBot there (/rs adopts their accounts into it), so linking in one server
                // doesn't put someone on another server's lists unasked. verified_guild_id is the server whose admin vouched
                // for the account; it is where trust came from, and it is what stops a different server re-linking the name.
                """
                CREATE TABLE IF NOT EXISTS younglings.player_account (
                    account_id BIGSERIAL PRIMARY KEY,
                    rsn TEXT NOT NULL,
                    discord_user_id BIGINT NOT NULL,
                    verification_method TEXT NOT NULL,
                    verified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    verified_guild_id BIGINT NULL
                );
                """,

                """
                CREATE UNIQUE INDEX IF NOT EXISTS player_account_unique_rsn_lower
                ON younglings.player_account (LOWER(rsn));
                """,

                """
                CREATE INDEX IF NOT EXISTS player_account_discord_user_idx
                ON younglings.player_account (discord_user_id);
                """,

                // Every link that existed before accounts did becomes an account (the earliest link of a name wins, should
                // two servers ever have linked the same one). Safe to run every boot: a name already there is left alone.
                """
                INSERT INTO younglings.player_account (rsn, discord_user_id, verification_method, verified_at, verified_guild_id)
                SELECT DISTINCT ON (LOWER(rsn)) rsn, discord_user_id, verification_method, verified_at, guild_id
                FROM younglings.player_link
                ORDER BY LOWER(rsn), verified_at ASC
                ON CONFLICT DO NOTHING;
                """,

                // A server's admin removing a registration from their own server (the owner's own unlink removes the account
                // everywhere instead). Without this, /rs would register the same account right back; linking the name in
                // that server on purpose clears the row.
                """
                CREATE TABLE IF NOT EXISTS younglings.player_link_exclusion (
                    guild_id BIGINT NOT NULL,
                    rsn TEXT NOT NULL,
                    removed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,

                """
                CREATE UNIQUE INDEX IF NOT EXISTS player_link_exclusion_unique
                ON younglings.player_link_exclusion (guild_id, LOWER(rsn));
                """,

                // --- A player's RuneMetrics data belongs to the player, not to a server ---
                //
                // Snapshots and activities are now stored once per RS name, whichever server asked for the poll, and every
                // server reads the same rows (a server's own lists still come from its roster and registrations, not from
                // here). guild_id stays on the rows, nullable now: old rows keep theirs, new ones leave it empty.
                """
                ALTER TABLE younglings.player_stats_snapshot ALTER COLUMN guild_id DROP NOT NULL;
                """,

                """
                ALTER TABLE younglings.player_activity ALTER COLUMN guild_id DROP NOT NULL;
                """,

                """
                CREATE INDEX IF NOT EXISTS player_stats_snapshot_global_rsn_idx
                ON younglings.player_stats_snapshot (LOWER(rsn), snapshot_at DESC);
                """,

                """
                CREATE INDEX IF NOT EXISTS player_activity_global_rsn_idx
                ON younglings.player_activity (LOWER(rsn), recorded_at DESC);
                """,

                // The same event seen by two servers' polls would be two rows; collapse any (there are none until a second
                // server polls a name the first already has) and make "seen once per player" a rule the database enforces.
                // Done once — the index existing is the marker that it has been.
                """
                DO $$
                BEGIN
                    IF to_regclass('younglings.player_activity_global_unique_idx') IS NULL THEN
                        DELETE FROM younglings.player_activity a
                        USING younglings.player_activity b
                        WHERE a.id > b.id
                          AND LOWER(a.rsn) = LOWER(b.rsn)
                          AND a.activity_date = b.activity_date
                          AND a.activity_text = b.activity_text;
                        CREATE UNIQUE INDEX player_activity_global_unique_idx
                            ON younglings.player_activity (LOWER(rsn), activity_date, activity_text);
                    END IF;
                END $$;
                """,

                // The moment an activity really happened. activity_date is UK local wall-clock time as RuneMetrics writes it (see RuneMetricsDates),
                // which can't be compared with anything else until it is turned into an instant; this column is that, set when the activity is saved.
                // recorded_at stays what it always was: when we first saw it.
                """
                ALTER TABLE younglings.player_activity ADD COLUMN IF NOT EXISTS occurred_at TIMESTAMPTZ NULL;
                """,

                // The log's own event code (rs_plog_*), which sorts a line; null for lines the bot's old polls saved without one.
                """
                ALTER TABLE younglings.player_activity ADD COLUMN IF NOT EXISTS activity_type TEXT NULL;
                """,

                """
                CREATE INDEX IF NOT EXISTS player_activity_occurred_idx
                ON younglings.player_activity (occurred_at);
                """,

                // Fill in the rows saved before the column existed (and any left empty): read the written clock time in the RuneMetrics zone.
                // To recompute everything after changing the zone, set occurred_at to NULL and restart.
                """
                UPDATE younglings.player_activity
                SET occurred_at = to_timestamp(activity_date, 'DD-Mon-YYYY HH24:MI')::timestamp AT TIME ZONE '%s'
                WHERE occurred_at IS NULL AND activity_date ~ '^[0-9]{2}-[A-Za-z]{3}-[0-9]{4} [0-9]{2}:[0-9]{2}$';
                """.formatted(RuneMetricsDates.zone().getId().replace("'", ""))
        ));
    }
}
