# One-process state: inventory and decisions

RuneClan Bot runs today as **one process**: one JDA connection, the internal HTTP API, every scheduler lane and the Discord job outbox drain,
all in the same JVM. Several things in the code quietly rely on that. This document lists every piece of static or in-memory state (and the
habits around it) that assumes one process, and records a decision for each, so the bot can later run as more than one copy.

Nothing here changes behaviour. It is the checklist the later steps work from.

## 1. The deployment shapes this is judged against

| Shape | What runs | Copies |
|---|---|---|
| **B1** one process, `ShardManager` with `SHARD_COUNT` shards (default 1) | everything | 1 |
| **B2** roles: `BOT_ROLE=gateway` and `BOT_ROLE=api` (default `all` = today) | *gateway*: Discord login, events, interactions, outbox drain. *api*: internal HTTP API and gateway-free schedules | gateway 1, api 2-3 behind a load balancer |
| **B3** shard sets: several gateway processes, each `SHARD_RANGE` of `SHARD_COUNT` | as B2, with one gateway process per shard set | gateway N, api 2-3 |

`BOT_ROLE` exists since T-670 (`config/BotRole`, `discord/RestOnlyJda`): `api` logs in REST-only and only queues outbox jobs. Every lane names its owner (T-702), and since T-746 the api role passes routes that need servers or members on to the gateway, so a split can run (section 1.1).

### 1.1 Running B2 (T-746)

- **How the api process answers the website:** it has no servers or members, so `internal/GatewayForward` checks the caller's secret (or the module's own check), then passes the request on to the gateway process's internal API (`GATEWAY_INTERNAL_URL`, private network, plain http: keep that port off the public network) and hands back its status, headers and body (lengths are recomputed; hop-by-hop headers dropped): the website gets what one process says. Bodies over 16 MB are refused (413). A passed-on request carries `X-RC-Hop`; an api process that receives one answers 508, so a `GATEWAY_INTERNAL_URL` pointing at an api process can't loop. The api role's internal API has 32 worker threads (forwards mostly wait). Only routes that need no Discord state are answered in the api process itself: stored pictures (`/internal/image/`, `/internal/wheel-gif/`, read from Postgres by token) and `/metrics` (each process's own). A route joins that list only once its handler is known to read nothing from the gateway's cache.
- **Gateway outage:** no connection, or a read (GET/HEAD) with no answer within 7 s (the website gives up at 8 s), is 503 "try again", never "not in that server". A change (POST, PUT, PATCH, DELETE) that was sent but got no answer in time is 503 with code `GATEWAY_NO_ANSWER`: the gateway may still be carrying it out, so the website must not repeat it blindly. The gateway's own 503s (a shard still connecting, Discord slow) pass through as they are.
- **What a split buys today:** the api-owned lanes (database work) and the stored pictures move off the gateway, and api replicas can restart without touching the gateway. Website routes still cost the gateway the same work. Moving more routes to the api side is per route, later.
- **M6 for B2:** dashboard jobs with in-memory progress (event space, wrap-up export, role backfill) run where their route runs, so in the one gateway process; F4 is only needed once there is more than one gateway (B3).
- **B3 later:** one `GATEWAY_INTERNAL_URL` assumes one gateway process. Several shard sets need the route sent to the process running that server's shard.
- **Start-up checks** (`BotRole.checkedForStart`): both split roles need `BOT_CACHE_NOTIFY=true`, `INTERNAL_API_SECRET` and `GUILD_ID`; `api` needs `GATEWAY_INTERNAL_URL` as an http(s) address with a host; a `BOT_ROLE` that isn't a role stops the process. `BotRole.splitReady()` is a kill switch: false refuses every split role again.
- **`onJdaReady` listeners in the api role** (checked for T-746): all of them either keep the JDA for later, or start repeating work on a lane (so `LaneOwners` decides where it runs), except these one-off steps, all harmless without a gateway: the skill and tracking icon lists are read once (`EmojiUploads`: no uploads), Linked Roles re-sends its metadata schema (the same idempotent `PUT` as from the gateway), `PluginXpTracker` sets two in-memory switches, `DevHelpSetup` loops over the (empty) server list on a dev bot, the internal API starts, and the outbox only queues.
- **Railway, for Drake when wanted:** one bot service stays as the gateway (`BOT_ROLE=gateway`, `BOT_CACHE_NOTIFY=true`, its internal API port reachable on the private network only); a second service from the same image runs `BOT_ROLE=api`, `BOT_CACHE_NOTIFY=true`, `GATEWAY_INTERNAL_URL=http://<gateway service>.railway.internal:<port>`, and the website's bot address points at the api service. The api process checks callers itself before passing requests on, so every secret a caller uses must be set on the api service as well as the gateway: `INTERNAL_API_SECRET`, `RUNELINK_DM_SECRET` (the hub's DM relay); the api's own `/metrics` needs `METRICS_ENABLED` and `METRICS_TOKEN` there too. Set the database's TCP keepalives before running two api replicas (`database/ApiLeaderElection`).

Under B1 nothing in this list breaks: a `ShardManager` in one JVM still sees every server. The decisions below matter for B2 and B3.

## 2. Decision key

| Decision | Meaning |
|---|---|
| **Move to Postgres** | Must be one value for the whole bot. Two copies would double a budget, double-post, or lose work. Moves to a table (or to the hub) before B2/B3 go live. |
| **One owner** | Correct only while exactly one process runs it. Pin it to one role (normally `api`) or guard it with a Postgres advisory lock; no data move needed. |
| **Invalidate across processes** | A per-process cache of database rows whose writes can arrive on another process (the website writes through `api`, the gateway reads). Needs a cross-process "forget X" signal (Postgres `LISTEN/NOTIFY`) or a short TTL. |
| **Per shard set is fine** | State about servers, members or interactions that only the process owning that server's shard ever sees. |
| **Per process is fine** | A local cache with a TTL, a lookup dedupe, an abuse limiter for button mashing, a log buffer, or a constant table. Worst case with N copies: N times the work or N times the limit, which is acceptable. |

## 3. Must fix before B2/B3 (the short list)

These are the items that break or misbehave with a second process. Each maps to one follow-up in section 3.1.

| # | Item | Where | What goes wrong with two processes | Decision |
|---|---|---|---|---|
| M1 | RuneMetrics poll queue and budget | `runescape/polling/PollEngine` (`requestLog`, `inFlight`, `recentPolls`, `enqueuedAt`), `RateBucket`, `PollQueue.byKey`, `PollCoordinator.jobs` | Each copy has its own token bucket, so the shared per-IP budget is spent twice; the same player can be polled by both copies; recurring jobs register twice. | **One owner** now (polling runs only in the `api` role; other roles forward `submit` to it). **Move** to the hub's `poll_queue` and per-provider budget rows when polling moves there. |
| M2 | RuneMetrics daily tally | `RuneMetricsBudget` (saves `GREATEST(old, new)` per day and tier) | Two copies each save their own count; `GREATEST` keeps the larger, so the history under-counts. | Follows M1 (one owner). If polling ever runs in two copies, change the upsert to add deltas. |
| M3 | Discord outbox restart recovery | `DiscordJobRepository.recover()` | A starting process marks **every** `running` job as retrying or failed, including jobs another live process is running right now. | **Move to Postgres**: add `claimed_by` and `claimed_at`; recover only rows whose claim has expired or that this process claimed. |
| M4 | Discord outbox claim by shard | `DiscordJobRepository.claimDue` | Already `FOR UPDATE SKIP LOCKED` (no double run), but any gateway process takes any server's job; under B3 a process cannot reach servers on other shards and fails the job. | **Move to Postgres**: claim only rows whose `guild_id` maps to this process's shards (`(guild_id >> 22) % SHARD_COUNT`). |
| M5 | Boot-loaded live registries | `PollService.activePollsById` / `optionsByPollId`, `SignupService.activeSignupsById` | Loaded once at start-up and then trusted. A poll created through the website (`CommunityAdminApi`, `MemberApi`) on the `api` process is unknown to the gateway process until it restarts, so its buttons fail. | **Move to Postgres** as the source of truth: look up on a miss (load-through), keep the map only as a cache. **Done** (F3): `getSessionById` reads the one row on a miss (a miss that finds nothing is remembered for 10 s), the `/poll` panel and the website's signup list read their server's rows from Postgres and drop cached ones ended or deleted elsewhere. A poll ended or signup deleted on another process stays cached here until that server's list is read again or the process restarts; a push signal for that is M10's job. |
| M6 | Long-running website jobs and their progress | `EventSpaceOps.jobs` / `locks` / `pending`, `EventWrapupOps.exports`, `RoleSyncBackfill.progress` | The dashboard starts a job on one `api` replica and polls progress every two seconds; the next poll can land on another replica that knows nothing ("no job"). The per-event `ReentrantLock` does not stop a second replica. | **Move to Postgres**: a small job table (state, done, total, message, finished_at) and `pg_advisory_xact_lock` per event instead of the in-memory lock. |
| M7 | Security rate limits for RuneLink | `LinkService` `WindowLimiter`s: `wrongCodesPerDiscord`, `codePerIpMinute`, `codePerIpHour`, `startPerIp`, `startPerRsn`, `startPerTracking`; `LinkDm` `dmPerIp`, `dmPerTracking` | These cap guessing of link codes and DM spam. With N `api` replicas behind a load balancer the real limit is N times the intended one. | **Move to Postgres**, done: `SharedWindowLimiter` keeps them in the link schema's `rate_window` table (one row per limiter and hashed key holding the hit times inside the window; one `INSERT ... ON CONFLICT DO UPDATE` per check, whose row lock makes racing replicas queue; old rows go in the hourly retention sweep on the `rsnlink-retention` lane). A database error refuses the route (500), as the route's own work needs the same database. The other `WindowLimiter` uses (token and alias routes, web account) stay per process. |
| M8 | Hiscores read budget for XP verification | `XpVerifier.globalReads` (and its `cache`), `RsnLinkModule` lane `rsnlink-verify` | Two workers would each spend the full public-read budget and check the same claims. | **One owner** (`api` role). The claims themselves are already in Postgres (`PgVerifyStore`). |
| M9 | "Server not visible" treated as "server gone" | `PollCloser` (clears the poll's schedule), `TwitchService.announce` (claims, then finds no channel, alert lost), `GuildRetentionService.sweep` (uses `getGuildById` as proof of absence), other lanes that skip a `null` guild | Under B3 a process sees only its own shards' servers, and under B2 the `api` role may have no gateway at all. A `null` from `getGuildById` then means "not mine", not "the bot left". | **One owner** per lane plus a shard check: `ShardRouting.owns(guildId)` and `ShardRouting.gone(guildId)` (T-700); `null` counts as "gone" only for a server this process owns, on a connected shard with no outage. Retention purges a departed server (departure row) only from the process that runs its shard. |
| M10 | Settings caches with no TTL | see section 4.3 | The website saves a setting on the `api` process; the gateway process keeps the old value until it restarts (for example an automod rule removed on the dashboard keeps deleting messages). | **Invalidate across processes**: one `LISTEN/NOTIFY` channel (`bot_cache`, payload `area:guildId`) that every cache subscribes to, or a short TTL as a stop-gap. **Done** (F7, behind `BOT_CACHE_NOTIFY`, off by default): see section 4.5. |
| M11 | Scheduler lanes that read rows, not servers | most lanes in section 5 | A lane that loops over database rows (reminders, giveaways, scheduled posts, ticket closes, premium expiry) runs in every process that starts it, so the same reminder fires twice. | **One owner**: each lane declares its role (section 5). Lanes whose work needs Discord run in the gateway and filter rows to their own shards (`guild_id` → shard). |

### 3.1 Follow-up work

Each must-fix item maps to one follow-up. A follow-up is not done until its row here says so (replace "open" with the PR that closes it).

| Follow-up | Covers | Work | Status |
|---|---|---|---|
| F1 | M1, M2, M8 | Pin RuneMetrics polling and the Hiscores verify lane to one role (`api`); other roles forward `submit`. Later moves to the hub `poll_queue`. | done (T-702), changed: polling, its budget and the event trackers are owned by the **gateway** role (their targets are the servers the bot is in, which only the gateway can see), so with one gateway process there is one budget; the Hiscores verify lane is api-owned. RuneMetrics shuts down 2026-11-10, so no forwarding was built |
| F2 | M3, M4 | Outbox `claimed_by` / `claimed_at`; `recover()` touches only expired or own claims; claims filtered to this process's shards. | done (T-696): each process claims under its own `ProcessId`, refreshes its claims every 20 s on the `discord-job-claims` lane, and any process recovers claims not refreshed for 2 min (never its own); claims are filtered to the shards the process runs |
| F3 | M5 | Load-through lookups for polls and signups; the boot maps become caches. | done (#473) |
| F4 | M6 | Job table and per-event `pg_advisory_xact_lock` for event space, wrap-up export and role backfill jobs. | done (T-698): `jobs/JobRepository` keeps each slot's latest job in `younglings.dashboard_job` (an event's roles/channels and wrap-up steps, a server's role fix); a new job claims its slot under `pg_advisory_xact_lock`, a running job beats every 30 s and one silent for 2 minutes reads as failed and frees the slot. Finished exports live in `younglings.event_export_file` for an hour. The in-process `ReentrantLock` stays only to keep a job and that process's own role sync apart |
| F5 | M7 | RuneLink code, start and DM limits in a Postgres sliding-window table (or the hub). | done (T-699) |
| F6 | M9 | `ShardRouting.owns(guildId)` helper; fix the "null guild = gone" branches; retention uses the departure row only. | done (T-700): `ShardRouting.owns` / `ShardRouting.gone` (gone = this process runs the shard, it is connected, no outage, server missing). Poll end times, delayed autoroles, Twitch and Kick alerts and retention only drop work when `gone`; retention purges a departed server only from the process that runs its shard |
| F7 | M10 | One `LISTEN/NOTIFY` channel (`bot_cache`) that the settings caches in section 4.3 subscribe to. | done (T-701); switch on with `BOT_CACHE_NOTIFY=true` in every process once there is more than one |
| F8 | M11 | Each scheduler lane declares its `BOT_ROLE` (section 5); gateway lanes filter rows to their own shards. | done for B2 (T-702): `util/LaneOwners` names every lane's owner (a test fails on a lane missing from it); `BotScheduler` runs a repeating task only where its lane is owned; api-owned lanes run on the one api replica holding the leader lock (`database/ApiLeaderElection`). Staff digest and anniversaries skip servers on other shards. Filtering every gateway lane's rows to its own shards is left for B3 (several gateway processes, not built yet) |

## 4. Full inventory

Paths are under `src/main/java/com/runeclans/bot/`. "TTL" means the entry expires on its own.

### 4.1 Budgets, rate limits and dedupe

| State | Where | Kind | Decision |
|---|---|---|---|
| RuneMetrics queue, bucket, in-flight, recent polls, counters | `runescape/polling/PollEngine`, `RateBucket`, `PollQueue`, `PollCoordinator.jobs` | global budget | M1: One owner, then move to the hub |
| RuneMetrics daily tally | `runescape/polling/RuneMetricsBudget` | global counter | M2: follows M1 |
| Hiscores read log and view cache | `rsnlink/verify/XpVerifier.globalReads`, `cache` | global budget | M8: One owner |
| RuneLink abuse limits | `rsnlink/LinkService` (6 `SharedWindowLimiter`s, 3 `WindowLimiter`s), `rsnlink/LinkDm` (2 `SharedWindowLimiter`s) | security limit | M7: the code, start and DM limits are in Postgres (`SharedWindowLimiter`, `rate_window`), done; token, alias and web-account route limits per process |
| Website button and API limits | `internal/RateLimiter` used by `FormsPublicApi`, `TicketAdminApi`, `DirectorySite*Routes`, `WebUserStateRoutes`, `SiteMembersAdmin`, `AppealsPublicApi`, `MemberApi`, `SiteViewerRoutes`, `DiscordJobRoutes`, `ClanSiteAdminApi`, `OverallAdminApi`, `PluginApi`, `LinkedRolesApi`, `BotlessAdminApi` | anti-mashing | Per process is fine (N replicas allow N times the burst; the website and Discord have their own limits) |
| Plugin ingest limits | `internal/PluginApi` (`ingestLimit`, `accountLimit`, `runeSyncLimit`) | anti-flood | Per process is fine; revisit if plugin traffic grows (hub owns ingest long term) |
| Message cooldowns | `chatlevel/ChatLevelListener.lastEarned`, `highlight/HighlightListener.lastSent`, `autoreply/AutoReplyListener.lastAnswered`, `commands/customtag/TagCommand.lastUsed`, `commands/recap/WrappedCommand.lastUse`, `suggestion/SuggestionService.lastSubmitted`, `tempvoice/TempVoiceService.lastCreate`, `commandchannel/CommandChannelListener.lastNoticeAt`, `servertag/ServerTagListener.recent`, `honeypot/HoneypotListener.recent` | per-server event dedupe | Per shard set is fine (events for a server arrive on its shard only) |
| Automod histories, role-rule chain guard | `automod/AutomodListener.histories`, `rolerules/RoleRuleService.hops`, `recent` | per-server event state | Per shard set is fine |
| Loose proof reminder dedupe | `events/LooseProofReminderListener.reminded` | per-channel dedupe | Per shard set is fine |
| Presence member batch | `presence/PresenceBatch` (`pending`, `askedAt`) | gateway request batching | Per shard set is fine |
| Ephemeral message timers | `discord/EphemeralLifecycle.tracked` | interaction hooks | Per shard set is fine (the hook lives in the process that answered) |
| Discord lookup dedupe | `discord/DiscordLookups` static `usersAsked`, `membersAsked` | in-flight dedupe | Per process is fine |
| Roster refresh throttle | `runescape/ClanSyncService.lastRosterRefresh` | throttle | Per process is fine while clan sync is one owner (section 5); moves with polling to the hub |
| Panel redraw debounce | `internal/Debouncer`, `events/EventBoardPanels.pending`, `events/CohostPanels.redraws` | edit debounce | Per shard set is fine once the redraw runs where the server lives; until then two copies cost one extra edit at most |
| Twitch "live now" snapshot | `twitch/TwitchService.lastLive`, `lastPoll` | display cache | Per process is fine; the post itself is claimed in Postgres (`repository.claim`). The lane is One owner (M11) |
| Send-now guard | `tracking/TrackingConfigInteractionListener.sendNowInFlight` | double-click guard | Per shard set is fine |
| Hub write retry queue | `runeclans/HubWriteQueue.held` | best-effort retry, documented as memory-only | Per process is fine (lost on restart by design) |
| Hub member privacy sends (T-750) | `member/HubMemberPrivacy.held` | newest change per hub row, memory only, capped at 10,000 rows | Per process is fine (lost on restart by design: the member's next save or the hub's `MoveTool copy member-privacy` catches up). Each process sends only what it saved, so newest-wins holds within one process |
| Hub site role reports | `hubsites/RoleReports.pending` | outgoing batch | Per shard set is fine (role changes come from the owning shard) |
| Command usage counts | `commandusage/CommandUsageCounter` | counter flushed with `uses = uses + EXCLUDED.uses` | Per process is fine (flushes add up) |
| Site invalidation batch | `internal/SiteInvalidator` (`pending`, `pendingGuilds`, static `instance`) | outgoing batch to the website | Per process is fine (each process announces its own writes) |

### 4.2 Work in progress and live registries

| State | Where | Decision |
|---|---|---|
| Active polls and options | `commands/poll/PollService` | M5: Move to Postgres (load-through), done |
| Open signups | `commands/signup/SignupService.activeSignupsById` | M5: Move to Postgres (load-through), done |
| Signup builder drafts | `commands/signup/SignupService.submissionDraftsByUserId` | Per shard set is fine (one user's slash-command session; lost on restart by design) |
| Team forming staged changes | `commands/teamforming/TeamformingService.pendingByUserId` | Per shard set is fine (same trade-off) |
| Prune confirmations | `commands/runescape/PruneInteractionListener.pendingSpecs` | Per shard set is fine (button on the same shard) |
| `/rs` share toggle | `commands/runescape/RsInteractionListener.shareEnabled` | Per shard set is fine |
| Event space setup jobs and locks | `events/EventSpaceOps` (`jobs`, `locks`, `pending`) | M6: Move to Postgres |
| Event wrap-up exports | `events/EventWrapupOps.exports` | M6: Move to Postgres |
| Role sync backfill progress | `runescape/RoleSyncBackfill.progress` | M6: Move to Postgres |
| Plugin XP tracker hand-off | `events/PluginXpTracker` (`runeSyncPlayers`, `disagreements`, `notCounted`, `aliases`) | One owner (must run in the same process as `EventTracker`, which reads `runeSyncPlayers`) |
| Discord outbox handlers | `discord/jobs/DiscordOutbox.handlers` | Per process is fine (a constant table); claims are M3/M4 |
| Event change subscribers | `events/EventChanges.subscribers` | Per process is fine for in-process listeners; a change made on `api` that a gateway listener must see goes through M10's signal |
| Snapshot log buffer | `devtools/SnapshotLogBuffer` | Per process is fine |

### 4.3 Caches of database rows (writes may come from the website)

No TTL: the cache is only refreshed when the same process writes. All of these are **M10: Invalidate across processes**, done: each class implements `CacheInvalidator` under the area in the last column (section 4.5).

| Cache | Where | Area |
|---|---|---|
| Automod config | `automod/AutomodRepository.cache` | `automod` |
| Auto replies | `autoreply/AutoReplyRepository.cache` | `auto-reply` |
| Custom tags and their switch | `customtag/CustomTagRepository.cache`, `enabled` | `custom-tag` |
| Highlights (configs, keywords, ignores) | `highlight/HighlightRepository` | `highlight` |
| Chat level config and roles | `chatlevel/ChatLevelRepository.configs`, `roles` | `chat-level` |
| Server tag config | `servertag/ServerTagRepository.cache` | `server-tag` |
| Honeypot config | `honeypot/HoneypotRepository.cache` | `honeypot` |
| Auto-delete rules | `autodelete/AutoDeleteRepository.cache` | `auto-delete` |
| Message log config | `messagelog/MessageLogRepository.configs` | `message-log` |
| Points extras triggers | `points/PointsExtrasRepository.triggerCache` | `points-extras` |
| Role rules | `rolerules/RoleRuleService.cache` | `role-rules` |
| Command channels | `commandchannel/CommandChannelService.channelIndexByGuild` | `command-channels` |
| Permission groups | `permission/PermissionGroupService.cache` | `permission-groups` |
| Hub command settings | `hub/HubService.cache` | `hub-commands` |
| Server language | `i18n/Messages.chosen` | `language` |
| AFK list | `afk/AfkService.byGuild` (the dashboard can clear AFK through `HubAdminApi`) | `afk` |
| Bot-less site list | `clansite/BotlessSiteRepository.known` | `botless-sites` |

With a TTL (stale for at most the TTL on another replica): **Per process is fine**. The admin reads are subscribed to M10's signal; the rest stay on their TTL.

| Cache | Where | TTL |
|---|---|---|
| Admin reads (Discord-derived answers) | `internal/AdminReads` static `StaleCache` | fresh 30 s, served stale up to 10 min while refreshing; `AdminReads.forget` after a write also sends `admin-reads:guildId`, and `internal/AdminReadsCache` drops that server's pages in the other processes (M10) |
| Site and directory caches | `internal/SiteCache`, `DirectoryApi`, `DirectoryClanVsClanSection`, `HubEventsApi`, `TicketAdminApi.archivedThreadCache` (`internal/TtlCache`) | short TTL |
| Site viewer roles | `clansite/SiteRoleGuard.viewers` | 60 s |
| Beta and access answers | `beta/BetaAccessService.remembered`, `access/AccessGrantService.remembered` | about a minute |
| Overall admins, bot owners | `access/OverallAdmins`, `notice/BotOwners` | timed reload |
| Plugin feed, RuneSync panel | `clansite/ClanSitePluginFeed.cache`, `plugin/RuneSyncPanelService.cache` | timed |
| Hub read cache | `runeclans/HubCache` | LRU with TTL |
| Hub feed id cache | `runeclans/RuneClansFeedPoller.Relay.ids` | timed |
| Lookups, prices | `lookup/LookupHttp.cache`, `plugin/PluginEnrichment.prices` (`util/TtlCache`) | 15 min / timed |
| Citadel wheel admin cache | `tracking/CitadelWheelEntries.adminCache` | timed |
| Event space "seen" | `events/EventSpaces.seen` | 60 s |
| Site channel mappings recorded this run | `hubsites/SiteChannelMappings.recorded` | per run; a second process repeats one idempotent write |
| Phishing domain list | `automod/PhishingList` | timed reload |
| Twitch and Kick app tokens | `twitch/TwitchClient`, `kick/KickClient` | until expiry; each process holds its own token (allowed by both APIs) |
| RuneLink signing keys | `plugin/RsLinkAuth.keys` | timed refetch |

### 4.5 How the cache signal works (M10)

Paths are under `src/main/java/com/runeclans/bot/cachebus/`.

- **Switch:** `BOT_CACHE_NOTIFY=true` (docs/DEV-SETUP.md 3c). Off by default; then nothing below runs and every cache behaves exactly as with one process. Turn it on in every process at the same time.
- **Sending:** a cache that wrote calls `CacheBus.changed(area, guildId)` after its write committed (it has already updated or dropped its own entry, as before). The signal is queued on the `cache-bus-send` lane and sent with `SELECT pg_notify('bot_cache', 'area:guildId')` on a pool connection, so the web request that saved never waits for it. The same signal queued twice before it is sent goes once.
- **Listening:** `CacheListener` holds one connection of its own, opened from `DATABASE_URL` outside the Hikari pool, and runs `LISTEN bot_cache` on the `cache-bus-listen` lane. Each turn waits up to a second for signals, then queues the next turn. The connection is checked every 30 s; when it fails it is reopened after 1 s, doubling to at most a minute. **Every (re)connect clears every subscribed cache**, because signals sent while nobody listened are lost.
- **Dispatching:** `CacheDispatcher` parses `area:guildId` (anything else is ignored) and calls `evictCached(guildId)` on each `CacheInvalidator` of that area. A cache that throws is logged and the others still run.
- **Own signals:** a process hears its own signals too and drops the entry it just wrote; the next read loads the same row. That costs one read per change but needs no bookkeeping, and whatever order two processes write in, both end with what Postgres holds.
- **Adding a cache:** implement `CacheInvalidator` on its `@BService` (an `@InterfacedService`, so `CacheBus` finds it without an edit), give it a `CACHE_AREA` of its own (`CacheAreasTest` lists them), and call `CacheBus.changed(CACHE_AREA, guildId)` after each write. `evictCached` and `evictAllCached` only drop entries; they never write or send a signal.
- **Not covered yet:** `events/EventChanges.subscribers` (section 4.2) and the boot-loaded poll and signup maps (M5) still learn of another process's change only on their next read from Postgres; they can subscribe the same way when needed.

### 4.4 Application-wide Discord state

| State | Where | Decision |
|---|---|---|
| Application emoji mentions and upload guard | `runescape/SkillEmojiCatalog`, `tracking/TrackingIconCatalog`, `runescape/GuildIconEmojiCatalog` (`uploadInFlight`) | Per process is fine for the cache. Emojis belong to the application, so two processes starting together can both upload the same missing emoji. One owner for uploads (gateway process holding shard 0); others only read the list. Low risk: only on a first boot after adding an icon. Done (T-702): only the gateway's shard 0 creates or replaces emojis (`discord/EmojiUploads`); the api role reads the list once at start. `GuildIconEmojiCatalog` is unchanged: it uploads lazily, one emoji per server named after that server, only from the process that has the server in its cache, so two processes never upload the same one. |
| Privileged intent flags | `config/PrivilegedIntents` (static) | Per process is fine (same application, same answer) |
| Dev-bot posting switch | `tracking/TrackingPostingToggle`, `alerts/AutoPostRouter` (static `instance`) | Per process is fine on the dev bot (it is one process by design); production starts ON from `LIVE_ENV` everywhere |
| Premium and billing switches | `premium/PremiumSwitch` (static `forced`), `premium/Billing` (static `instance`) | Per process is fine (`forced` is a test hook; the switch comes from the environment, the same in every process) |
| Feature flags shown on the status page | `internal/FeatureStatusRepository.FLAGS` | Per process is fine (constant table) |
| Text catalogue | `i18n/Text` (static `installed`, `english`) | Per process is fine (loaded from bundled files) |

### 4.5 Constant tables and thread naming (no decision needed)

Per process is fine: `runescape/SkillEmojiCatalog.ICON_BYTES`, `tracking/TrackingIconCatalog` byte tables, `events/BingoBackdrops.KEPT`,
`events/BingoLayout.IMAGES` / `all`, `events/BingoTiles.ICONS`, `internal/ColorRoleCatalog.COLORS`, `internal/WikiCatalog`,
`events/ClanVsClanTemplate.games`, `events/CompetitionTemplate.pluginSourceOffered`, `commands/poll/PollBarRenderer` font measurements,
`welcome/WelcomeCardRenderer.cache`, `fun/FunApis` prefetch queues, `util/BotScheduler.WORKER_NUMBER`, `internal/AdminReads.THREAD_NUMBER`,
`tools/IconDownloader` (a developer tool, not run by the bot), `rsnlink/verify/MemoryVerifyStore` (tests and database-less runs only).

## 5. Scheduler lanes

Every `BotScheduler.lane(...)` and which process should own it once roles exist. Since T-702 the code's table is `util/LaneOwners` (the source of truth; it differs from the first plan below where a lane turned out to read the server list: polling, clan sync, clan points, the hub feed, skill of the week, plugin XP points, seasons, draws, board updates and retention are gateway-owned). "gateway (own shards)" means the lane needs Discord and must
filter its rows to the servers on this process's shards; "api" means exactly one owner (the `api` role, guarded by an advisory lock while it
has more than one replica).

| Lane | Class | Owner |
|---|---|---|
| `poll-jobs` | `runescape/polling/PollCoordinator` | api |
| `runemetrics-budget` | `runescape/polling/RuneMetricsBudget` | api (with polling) |
| `runemetrics-clock-check` | `runescape/RuneMetricsClockCheck` | api |
| `clan-sync-scheduler` | `runescape/ClanSyncScheduler` | api (roster fetch); role sync results go through the outbox |
| `clan-points-scheduler` | `runescape/ClanPointsScheduler` | api |
| `weekly-digest-scheduler` | `runescape/WeeklyDigestScheduler` | gateway (own shards) |
| `event-tracker`, `plugin-xp-tracker` | `events/EventTracker`, `events/PluginXpTracker` | api, together (shared `runeSyncPlayers`) |
| `rsnlink-verify`, `rsnlink-retention` | `rsnlink/bridge/RsnLinkModule` | api |
| `rsnlink-hub-dm` | `rsnlink/bridge/HubDmButtons` | gateway (own shards) |
| `community-command` | `hubsites/CommunityCommand` | gateway (own shards) |
| `runeclans-feed` | `runeclans/RuneClansFeedPoller` | api (cursor in Postgres); posts through the outbox |
| `hub-sites` | `hubsites/HubSiteReporter` | gateway (own shards) |
| `request-cards` | `hubsites/RequestCards` | gateway (answers card presses); cards and roles go through the outbox (`hubsites/RequestCardJobs`) |
| `discord-jobs`, `discord-job-claims` | `discord/jobs/DiscordOutbox` | gateway (own shards), see M3 and M4 (claims per process, done) |
| `ephemeral-lifecycle` | `discord/EphemeralLifecycle` | gateway (the process that answered) |
| `event-board-panels`, `cohost-panels`, `event-signups` | `events/...` | gateway (own shards) |
| `event-announcer-sweep` | `eventannounce/EventAnnounceService` | gateway (own shards) |
| `reminders` | `reminder/ReminderScheduler` | gateway (own shards) |
| `server-reminders` | `serverreminder/ServerReminderService` | gateway (own shards) |
| `scheduled-posts` | `internal/ScheduledPostRunner` | gateway (own shards) |
| `poll-closer` | `internal/PollCloser` | gateway (own shards), see M9 |
| `giveaways` | `giveaway/GiveawayService` | gateway (own shards) |
| `ticket-scheduler` | `commands/ticket/TicketScheduler` | gateway (own shards) |
| `signup-maintenance` | `commands/signup/SignupMaintenanceScheduler` | gateway (own shards) |
| `lookup-reminders` | `lookup/LookupReminderScheduler` | gateway (own shards) |
| `member-notifier` | `member/MemberNotifier` | gateway (own shards) |
| `autorole`, `role-keep`, `temp-voice`, `autopurge` | `autorole/...`, `rolekeep/...`, `tempvoice/...`, `autopurge/...` | gateway (own shards) |
| `clan-anniversaries` | `anniversary/AnniversaryService` | gateway (own shards) |
| `staff-digest` | `internal/StaffDigestService` | gateway (own shards) |
| `twitch`, `kick`, `feeds` | `twitch/TwitchService`, `kick/KickService`, `feed/FeedService` | api fetches once; posts filtered to own shards (or sent through the outbox) |
| `presence-members` | `presence/PresenceMemberLoader` | gateway (own shards) |
| `linked-roles` | `linkedroles/LinkedRoles` | api |
| `rank-weekly` | `internal/RankWeeklyScheduler` | gateway (Discord names and channels for the weekly congrats) |
| `premium-expiry`, `billing-reconcile` | `premium/Premium`, `premium/Billing` | api |
| `guild-retention` | `retention/GuildRetentionService` | api, see M9 |
| `plugin-retention`, `message-log-purge`, `clan-site-cleanup` | `plugin/...`, `messagelog/...`, `clansite/...` | api (database only) |
| `address-change-scheduler` | `clanprofile/SlugChangeScheduler` | api |
| `clan-site-race` | `clansite/ClanSitePluginFeed` (the leaderboard race's hub reads) | api (website reads only) |
| `command-usage` | `commandusage/CommandUsageListener` | every process (flushes add up) |
| `phishing-list` | `automod/PhishingList` | every process (each needs the list) |
| `debouncer`, `site-invalidator` | `internal/Debouncer`, `internal/SiteInvalidator` | every process (local batching) |
| `hub-member-privacy` | `member/HubMemberPrivacy` | every process (sends the member privacy changes it saved to the hub, T-750) |
| `panel-switches` | `runeclans/PanelSwitches` | every process (reads the Switches panel from the hub every 30 seconds for its own switches, T-817) |
| `job-heartbeat` | `jobs/JobRepository` | every process (keeps that process's own running dashboard jobs alive, T-698) |
| `event-export-expiry` | `events/EventExportRepository` | api (database only: deletes expired wrap-up exports, T-698) |
| `leagues` | `leagues/LeagueReporter`, `leagues/LeagueFeedPosts`, `leagues/LeagueCommand` | every process (reports a finished clan-vs-clan event's result to the hub from the process that finished it, T-759; turns the hub's league feed into outbox jobs and answers `/league` off the request thread, T-760: the posts themselves run through the Discord outbox in the shard's process) |

Two thread pools sit outside `BotScheduler` and are covered by M6: `EventSpaceOps` (`event-space`) and `EventWrapupOps` (`event-wrapup`),
plus the `RoleSyncBackfill` worker.

## 6. How this list was made, and keeping it current

Searched `src/main` for fields initialised with a mutable collection, atomic or cache (`ConcurrentHashMap`, `HashMap`, `ArrayDeque`,
`Atomic*`, `TtlCache`, `StaleCache`, `RateLimiter`, `WindowLimiter`, ...), every non-final or `volatile` static, every `BotScheduler.lane`,
every `FOR UPDATE` / `SKIP LOCKED` / advisory lock, and every `getGuildById(...) == null` branch in a scheduler. Then read each hit.

When adding state: if it must be one value for the whole bot (a budget, a dedupe that prevents a double post, a job other requests poll),
put it in Postgres from the start. A per-server cache of settings that the website can change needs the invalidation signal from M10 once it
exists. Add the row here either way.
