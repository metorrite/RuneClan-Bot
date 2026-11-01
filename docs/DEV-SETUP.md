# Setting up the RuneClan Bot dev environment

What a machine needs to build, run and test the bot, and where each piece comes from (how the bot, the websites and the hub fit together: [ECOSYSTEM.md](ECOSYSTEM.md)). **No secrets are in this file**: where a secret is needed it says which one, never its value.

Some names in code and tooling still say JonnyBot and stay that way for now: the repository, the jar, the database schema `younglings`, the IntelliJ run configuration "JonnyBot Dev", the local `jonnybot_*` databases and the deprecated `JONNYBOT_*` setting names.

## 1. What to install

| Tool | Why | Notes |
|---|---|---|
| Git (on Windows, Git Bash) | everything | |
| GitHub CLI (`gh`), logged in | PRs, CI checks, merges | `gh auth login` |
| JDK 21 | the bot (Java 21, JDA 6.4.x, BotCommands 3.1.0) | any JDK 21 works |
| Maven 3.9.x | builds and tests | IntelliJ's bundled one or a download. First run online (`mvn test`), then `-o` works from the local cache |
| PostgreSQL 16 (port 5432) | the bot's local dev database `jonnybot_dev` (schema `younglings`) | the bot creates its own tables at startup (`CREATE TABLE IF NOT EXISTS ...`) |
| Node.js 22 LTS and npm | the website (Next.js 16, React 19, Tailwind 4) | `npm ci` in the website checkout |
| IntelliJ IDEA (optional) | run configurations | see section 4 |

## 2. Repositories and folder layout

The bot and the website live in separate repositories. Clone them side by side (the scripts in `tools/` look for the website next to this repository):

```
Repositories/
  JonnyBot/              this repository: the Discord bot (Java)
  younglings-website/    the website + dashboard (Next.js; a separate, private repository)
  jb-*-wt/  yw-*-wt/     git worktrees, one per feature branch (see below)
```

Branch model (both repos): feature branches are made off `dev`, a PR goes into `dev`, and **nothing goes to `master` without the owner's go-ahead** (master auto-deploys to Railway). No stacked PRs. Merge bot PRs before website PRs.

- Keep the main checkout for running the dev bot, and do feature work in worktrees: `git worktree add ../jb-<name>-wt -b feat/<name> origin/dev` (and `yw-<name>-wt` for the site).
- Website worktrees get their own `npm ci` (see "Working in worktrees" in section 5).

## 3. Secrets and configuration (names only)

Keep these in a password manager. Never commit them.

**Bot (IntelliJ run configuration "JonnyBot Dev", environment variables):**
`DATABASE_URL` (local Postgres, `postgresql://USER:PASSWORD@127.0.0.1:5432/jonnybot_dev`), `DISCORD_TOKEN` (the *dev* bot's token), `GUILD_ID` (`<your-server-id>`, the server the dev bot sits in: **this is the real Younglings clan server**, a live community server; the dev bot is a separate Discord application that sits in it, so anything the dev bot does on Discord happens in the real server, and a bot cannot create a throwaway test server, Discord refuses that for bots now), `INTERNAL_API_SECRET` (shared with the website), `LIVE_ENV=FALSE`, `REMOVE_COMMANDS_ON_SHUTDOWN=TRUE`. The internal API listens on port 8081. The run configurations live in `.idea` (git-ignored), so on a new machine they are made by hand: in IntelliJ, Run > Edit Configurations > Application, JDK 21:

| Name | Main class | Environment variables |
|---|---|---|
| JonnyBot Dev | `com.runeclans.bot.Main` | `DATABASE_URL`, `DISCORD_TOKEN`, `GUILD_ID`, `INTERNAL_API_SECRET`, `LIVE_ENV`, `REMOVE_COMMANDS_ON_SHUTDOWN` |
| Initialize Database (optional; the bot initialises its tables itself) | `com.runeclans.bot.util.InitializeDatabase` | `DATABASE_URL` |
| Icon Image Update and Download (a tool) | `com.runeclans.bot.tools.IconDownloader` | none |

The Java package was `com.younglings.bot` until 2026-10-28 (T-663). On a machine set up before that: change each run configuration's main class to the `com.runeclans.bot` one above, and delete any leftover `src/test/java/com/younglings` folder after pulling (git-excluded scratch tests stay behind there and no longer compile).

The scripts in `tools/` read the bot's environment from the "JonnyBot Dev" run configuration (`dev-start.ps1`, `db_query.py` and `rsnlink-dev-env.ps1` from `.idea/runConfigurations/JonnyBot_Dev.xml`, `dev_api.py` from `.idea/workspace.xml`), so keep that name. An `.idea` folder copied from another machine carries the secrets: move it the way you would move a password.

**Website (`.env.local` in the website checkout, not in git):**
`DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET`, `NEXTAUTH_SECRET`, `NEXTAUTH_URL` (`http://localhost:3000`), `RUNECLAN_BOT_INTERNAL_API_URL` (`http://localhost:8081`), `RUNECLAN_BOT_INTERNAL_API_SECRET` (must equal the bot's `INTERNAL_API_SECRET`; the older names `JONNYBOT_INTERNAL_API_URL` / `JONNYBOT_INTERNAL_API_SECRET` still work when the new ones are unset). Signing in to the dashboard uses Discord OAuth, so the Discord application's redirect URI must include `http://localhost:3000/api/auth/callback/discord`.

**Live database and hosting:** production runs on Railway, and the production Postgres credentials live only there (never in this repository, a doc or a log). Only touch the live database when asked, read-only first. DNS is on Cloudflare.

**RSN link service (`rsnlink`, optional, off unless set up):** its own database `jonnybot_link` on the same local Postgres and its own keys, all `LINK_*` variables (LINK-SYSTEM.md section 6.1). Set it up locally with one command, from this repository:

```
powershell -ExecutionPolicy Bypass -File tools/rsnlink-dev-env.ps1
```

It creates `jonnybot_link` if missing and appends fresh random **local-only** keys to the git-ignored `.env` (`LINK_DATABASE_URL`, `LINK_ENC_KEY`, `LINK_LOOKUP_KEY`, `LINK_ALIAS_KEY`, `LINK_SIGNING_KEY`, `LINK_PUBLIC_BASE_URL`), printing names only. Keys already in `.env` are kept (changing `LINK_ALIAS_KEY` changes every alias; changing `LINK_ENC_KEY` without moving the old one to `LINK_ENC_KEY_OLD` makes stored Discord ids unreadable). To make a key by hand instead: 32 random bytes, base64, e.g. in PowerShell `$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)`. On start the bot logs `RSN link service is on (schema rsnlink).`, or `... is off` when `LINK_DATABASE_URL` is unset, or `... refused to start:` with the names of the variables that are wrong. Never put these local keys on Railway; production keys are made at go-live (T-120). Tests make their own random keys and use the schema `rsnlink_test`; point them at the link database with `RUNECLAN_BOT_TEST_LINK_DATABASE_URL` (old name `JONNYBOT_TEST_LINK_DATABASE_URL`, still read; same form as `DATABASE_URL`, database `jonnybot_link`).

**RSN link verification ("plugin knew it first", T-170, off by default):** `LINK_VERIFY_ENABLED=true` in `.env` switches it on (it needs the link service above). Optional tuning: `LINK_VERIFY_MIN_MATCHES` (2), `LINK_VERIFY_MIN_GAIN_XP` (100), `LINK_VERIFY_CHECKS_PER_LINK_DAY` (24), `LINK_VERIFY_CHECKS_PER_MINUTE` (20). When on, the bot logs `RSN link verification is on`, makes two tables (`verify_claim`, `verify_watch`) in the link schema, and reads the public Hiscores and RuneMetrics in the background for linked players whose plugin sends XP (LINK-SYSTEM.md section 12.1). Off, nothing is read or stored.

## 3c. Every bot setting

Every environment variable the bot reads. Each one can also go in the git-ignored `.env`, except where noted; the environment wins over `.env`. Only `DISCORD_TOKEN`, `DATABASE_URL` and `LIVE_ENV` are required. `SettingsDocumentedTest` fails the build when the bot reads a setting that isn't in this table.

**Core**

| Setting | What it does | When unset |
|---|---|---|
| `DISCORD_TOKEN` | The bot's Discord token (the *dev* bot's, locally). | Required. |
| `DATABASE_URL` | The bot's Postgres (`postgresql://USER:PASSWORD@host:port/db`). | Required. |
| `LIVE_ENV` | `TRUE` on the live bot only. With `FALSE` the bot is "not live": automatic posts are diverted (see `DEV_ALERT_CHANNEL_ID`), background sweeps that change the server by themselves (auto-clean, delayed autoroles, timed roles, event roles, event wrap-up, and the Member/Guest roles of the daily clan sync) are skipped (temp voice channels still work; `/dev toggleposting` turns them all on for testing), and dev-only setup can run. | Required. |
| `GUILD_ID` | The home server. Outside production, commands register there as guild commands, and `@Test` commands only ever go there. Locally it is the **real** clan server (see above). | Commands register globally; `@Test` commands nowhere. The internal API stays off. |
| `OWNER_IDS` | Comma-separated Discord ids of the bot's owners (RuneClan Admin's built-in admins, owner-only commands). | Nobody is an owner. |
| `BETA_SEED_GUILDS` | The servers put on the beta list (whose admins may use the website while it is closed, and whose clan sites may show) when a database is first set up: comma-separated server ids, each optionally `id:label` (a label is cut to 60 characters, the most the website's Beta page accepts). Applied once per database (the marker table `beta_guild_seeded` records it), so a server removed on the website stays removed; a database that was seeded before, including by an older build that kept the first testers' ids in code, is left as it is. Entries that are not ids are skipped and named by position in the log. With `RUNECLANS_HUB_ACCESS=on` the hub's list is used instead. | Nothing is seeded: a new database starts with an empty beta list (the home server, `GUILD_ID`, always counts); add servers on the website. |
| `ADMIN_ROLE_ID` | The env-default Admin role for the home server: it and every role above it may use admin commands. Servers set their own on the dashboard. | Fails closed: nobody passes the env-default admin check. |
| `BOT_STATUS` | The bot's Discord status text. | `bot.activity` from `config.properties`. |
| `PREMIUM_ENABLED` | `1` (or `true`) brings Premium back for clan websites (T-422): free servers get the free page, widget, version and picture limits again. Keep it equal to the website's `PREMIUM_ENABLED`. | Off: everything is free for now; every server gets the website's Premium features and limits (nothing is granted or stored). Faster polling and the server profile still follow the plans. |
| `LANGUAGE_PACKS_ENABLED` | `true` offers the machine-drafted language packs (T-099, docs/TRANSLATION.md) on the dashboard's Language page. | Off: only English can be picked (a server that already picked a pack keeps it). |
| `SHARD_COUNT` | How many gateway shards the bot logs in with (T-668). Discord requires sharding from 2,500 servers and recommends about 1,000 servers per shard; raise it only when that comes close. The bot always uses exactly this total (never Discord's recommendation), and every bot-wide lookup (internal API, schedulers, server lookups) asks all shards. A value that isn't a whole number of 1 or more is treated as 1. | `1`: one shard, the single gateway connection the bot always had. |
| `BOT_ROLE` | Which part of the bot this process runs (T-670, docs/SCALING-STATE.md shape B2 and section 1.1): `gateway` logs in to Discord, handles events and buttons and runs the Discord job outbox; its internal API answers the api process on the private network. `api` serves the website's internal API with a REST-only Discord connection (no gateway login), passes every route that needs servers or members on to the gateway (`GATEWAY_INTERNAL_URL`, T-746) and only queues outbox jobs. Each scheduler lane's repeating work runs only in the role that owns it (T-702, `util/LaneOwners`; api-owned lanes run on the one api replica holding the leader lock). Both split roles need `BOT_CACHE_NOTIFY=true`, `INTERNAL_API_SECRET` and `GUILD_ID`, and `api` needs a valid `GATEWAY_INTERNAL_URL`; without them the process stops at start-up and says which is missing, as it does for a value that isn't a role. | `all`: one process runs everything, the bot as it always was. |

**Internal API and website**

| Setting | What it does | When unset |
|---|---|---|
| `INTERNAL_API_SECRET` | The shared secret the website sends (must equal the site's `RUNECLAN_BOT_INTERNAL_API_SECRET`, or its older `JONNYBOT_INTERNAL_API_SECRET`). It also keys the signed sign-in facts (`X-RC-Actor-Sig`) the website sends for a clan website whose server the bot isn't in (docs/BOTLESS-SITES.md); there is no separate setting for those. | The internal API doesn't start (no dashboard, no site data). |
| `INTERNAL_API_PORT` | Port of the internal API. | `8081`. |
| `SITE_URL` | The website's public address, no trailing slash: where `/wrapped` gets recap images and links to. | `/wrapped` says it isn't set up. |
| `CLAN_SITES_URL` | The website that serves the clan sites, e.g. `https://runeclans.com`, no trailing slash (T-393). After a change the bot tells its `/api/revalidate` the shared cache tags plus that server's own clan site tags (`site:<slug>:<key>`), so the clan site shows it on the next visit (docs/SITE-INVALIDATION.md). Needs the website to accept per-site tags (runeclans T-500). | Clan sites refresh on their cache timers (5 s to 5 min). |
| `CLAN_SEASONS` | `on` (or `1` / `true`) turns on clan seasons (T-596): sites whose owner switches seasons on in the Site Admin get quarterly seasons (UTC) scored from XP gained, clan points and event wins, a half-hourly job (lane `clan-seasons`) keeps each finished season's final standings once, and, if the owner picked a channel, queues one season-end post through the Discord job outbox (no pings; never from a dev bot with posting off). | Off: the Seasons routes answer that seasons are off, the public season answer is `{"enabled": false}` and no season is closed. |
| `BILLING_RECONCILE_URL` | The runeclans website's billing reconcile route, e.g. `https://runeclans.com/api/billing/reconcile` (T-366). Once a day the bot sends it the paid subscriptions of servers it left 14+ days ago (with `INTERNAL_API_SECRET`), and the website sets them to cancel at the period end. | Nothing is sent; those subscriptions keep renewing until cancelled by hand. |
| `RUNECLAN_BOT_SITE_URL` | The address put in the User-Agent of every request the bot makes to a third party. | `https://jonnybot.rsyounglings.com`. |
| `JONNYBOT_SITE_URL` | **Deprecated** old name of `RUNECLAN_BOT_SITE_URL` (T-662). Still read, exactly as before, when the new name is unset; the bot then logs a one-time note naming both variables. | |
| `GATEWAY_INTERNAL_URL` | Only with `BOT_ROLE=api` (T-746): the gateway process's internal API on the private network, e.g. `http://bot-gateway.railway.internal:8081` (its `INTERNAL_API_PORT`). Must be `http://` or `https://` with a host. Routes that need servers or members are passed on there with the same secret and headers (plain http, so keep the gateway's port on the private network only); when the gateway can't be reached the website gets 503 "try again", and when a change gets no answer within 7 s it gets 503 with code `GATEWAY_NO_ANSWER` (it may have happened). | Unset or not an address: an api process won't start. |
| `BOT_CACHE_NOTIFY` | `true` (or `1`) turns on cross-process cache invalidation (T-701, docs/SCALING-STATE.md M10): after a settings change, the process sends `NOTIFY bot_cache, 'area:guildId'`, and one extra connection of its own (outside the pool) listens and drops that server's cached settings, so a change saved through the website reaches the gateway process at once. Turn it on in every process once the bot runs as more than one. | Off: no listener, no signals; each process keeps its caches until it writes them itself, as with one process. |

**Metrics, request log and ops alerts** (T-674; all off by default)

| Setting | What it does | When unset |
|---|---|---|
| `METRICS_ENABLED` | `on` serves `GET /metrics` on the internal API port (`INTERNAL_API_PORT`): Prometheus text with internal API requests and latency per route, the Discord job outbox (due jobs, oldest age), the RuneMetrics poll queue and budget (used per minute, tokens, backoff, exhausted), Discord REST 429s, gateway events and ping, the database pool (in use, waiting, wait time) and JVM heap. Needs `METRICS_TOKEN`. A request that came through a public edge (`X-Forwarded-For`, `X-Real-Ip`, `Forwarded` or `CF-Connecting-IP` present) is refused, so it only answers over Railway's private network; every refusal is a plain 404. Never needs the internal secret. Needs the internal API (`INTERNAL_API_SECRET`, `GUILD_ID`). | Off: `/metrics` is a 404 and Discord's HTTP client is JDA's own. |
| `METRICS_TOKEN` | The bearer token for `/metrics` (`Authorization: Bearer ...`), at least 24 characters. Never logged. | `/metrics` stays off (a warning is logged when `METRICS_ENABLED` is on). |
| `REQUEST_LOG` | `on` logs one line per internal API request: `request id=<X-Request-Id> <method> <route> <status> <ms>` (the route is the path prefix plus one plain word, never ids). The request id is always taken from the website's `X-Request-Id` (or made), echoed, and sent on to the hub with the bot's hub calls in that request; this switch only adds the log line. | No request lines. |
| `OPS_ALERTS` | `on` checks the ops alert rules every minute and posts to `OPS_ALERT_CHANNEL_ID`: a health address failing 3 checks in a row, the oldest due Discord job older than 5 minutes, the RuneMetrics budget used up for 15 minutes with work waiting, and a Discord 429 spike. One alert when a rule starts, a reminder each hour while it lasts, one line when it recovers. Posts follow the dev-bot switch (`DEV_ALERT_CHANNEL_ID`, `/dev toggleposting`) and ping nobody. | Off. |
| `OPS_WEEKLY` | `on` posts the week's numbers (servers, website calls and errors, RuneMetrics use against the budget, the job queue, 429s, the database pool, memory, alerts firing) to `OPS_ALERT_CHANNEL_ID` on Mondays at 09:00 UTC. | Off. |
| `OPS_ALERT_CHANNEL_ID` | The ops channel (a private channel in the owner's server) for `OPS_ALERTS` and `OPS_WEEKLY`. | Neither posts (a warning is logged if one is on). |
| `OPS_HEALTH_URLS` | Comma-separated health addresses for the health rule, e.g. the website's `/api/health` and the hub's `/health/ready` (Railway private addresses work). A 2xx within 5 seconds is healthy. | No health rule. |
| `OPS_429_THRESHOLD` | Discord 429 answers within 5 minutes that count as a spike. | `20`. |

**Dev bot safety**

| Setting | What it does | When unset |
|---|---|---|
| `DEV_ALERT_CHANNEL_ID` | While the bot isn't live (T-126): the channel its automatic alerts go to instead, with no pings. | They are only logged. |
| `DEV_HELP_SEED_CHANNEL` | Not-live bot only: the name (or part of it) of a channel where PvM/CA Help test panels are set up at start. Environment only. | Nothing is set up. |

**RuneScape tracking**

| Setting | What it does | When unset |
|---|---|---|
| `RUNESCAPE_CLAN_NAME` | Env-default clan name for the home server (servers set their own on Server setup). | No clan until one is set. |
| `RUNESCAPE_AUTO_POLL_ENABLED` | `false` stops all automatic roster and clan polling (manual Update buttons still work). | `true`. |
| `RUNESCAPE_POLL_REQUESTS_PER_MINUTE` | The shared RuneMetrics request budget per minute. | `10`. |
| `RUNESCAPE_POLL_BURST` | Requests the budget may save up for a burst. | `6`. |
| `RUNESCAPE_CLAN_POLL_WINDOW_MINUTES` | One pass over every player in a registered clan. | `180`. |
| `RUNESCAPE_LINKED_POLL_WINDOW_MINUTES` | One pass over linked players (`/rsn`, and RuneLink links from the hub's polling list) in no registered clan or community. | `480`. |
| `RUNESCAPE_COMMUNITY_POLL_WINDOW_MINUTES` | One pass over RuneLink-linked players the hub places in a community site and in no registered clan (T-736). The hub's Switches panel "Track every linked player" (`polling-list`) turns that list on; off, this tier is empty. | The linked window. |
| `RUNESCAPE_PREMIUM_POLL_WINDOW_MINUTES` | One pass over players in premium clans. | `60`. |
| `RUNEMETRICS_TIME_ZONE` | The zone RuneMetrics' activity times are read in. The clock check warns if it looks wrong (for example `Etc/GMT-1`). Environment only. | `Europe/London`. |
| `RUNEMETRICS` | Whether RuneMetrics is still asked. Jagex retires it on 2026-11-10: `auto` asks until then and stops after, `on` keeps asking (if Jagex extends it), `off` stops now. Also stops the RuneLink XP check reading it (the Hiscores alone decide). When off, Clan health's missed Citadel caps list and the rank-up advisor's caps parts show as unavailable (not counted). Environment only. | `auto`. |
| `RSN_RENAME_ALERT_CHANNEL_ID` | Env-default channel for "this player may have renamed" alerts. | Renames are only logged. |
| `RSN_VERIFICATION_CHANNEL_ID` | Env-default channel where RSN verification requests go for review. | Requests wait unnoticed. |
| `VERIFIED_CLAN_ROLE_ID`, `VERIFIED_NON_CLAN_ROLE_ID`, `UNVERIFIED_ROLE_ID` | Env-default roles given or removed on verification (clan member, verified non-member, removed on verify). | Nothing is given or removed for that slot. |

**Data retention**

| Setting | What it does | When unset |
|---|---|---|
| `GUILD_DATA_RETENTION_DAYS` | Days after the bot leaves a server before that server's data is deleted; `0` keeps it forever. | `30`. |
| `GUILD_DATA_RETENTION_DRY_RUN` | `false` lets the retention sweep actually delete. | `true`: it only reports. |
| `PLUGIN_EVENT_RETENTION_DAYS` | Days plugin events are kept; `0` keeps them. Environment only. | `180`. |

**Features with outside services**

| Setting | What it does | When unset |
|---|---|---|
| `PHISHING_LIST_URL` | The phishing-domain list automod downloads daily; empty switches the download off. Environment only. | `https://phish.sinking.yachts/v2/all`. |
| `TWITCH_CLIENT_ID`, `TWITCH_CLIENT_SECRET` | The Twitch app for go-live alerts. Environment only. | Twitch alerts say they aren't set up; nothing is sent to Twitch. |
| `KICK_CLIENT_ID`, `KICK_CLIENT_SECRET` | The Kick app for go-live alerts (T-042). Environment only. | The same for Kick. |

**Plugins**

| Setting | What it does | When unset |
|---|---|---|
| `PLUGIN_XP_SCALE` | Raw plugin XP units per point of experience. | `1` (RuneSync sends whole XP; checked against the Hiscores on 2026-10-26). |
| `PLUGIN_XP_POINTS_ENABLED` | `true` offers clans "Points for XP gained" on the dashboard's Clan plugin page (T-542): *N points for every M XP* a member gains in one UTC day, with a daily cap, read from the RuneClans hub's XP ledger (`POST /v1/guilds/{id}/xp/gains`, timed RuneSync XP only, catch-up excluded) once each UTC day has ended (see `PLUGIN_XP_POINTS_GRACE_MINUTES`); members are found from the bot's own RuneSync notes and from the hub (`POST /v1/guilds/{id}/xp/gainers`, so XP that reaches only the hub counts: members still in the server whose grant shares their Discord id, each server and day asked once and remembered across restarts, and only days since the clan last switched its rule on; a hub without that route leaves the notes only); one award per member per day (`RuneSync: 1.2M XP gained`), never twice. Each clan's rule stays off until its staff switch it on, and points are paid only while the server uses points. Needs the hub client (`RUNECLANS_HUB_ENABLED`) and the hub's `HUB_XP=on`. | Off: the rule isn't offered and nothing is paid. |
| `PLUGIN_XP_POINTS_GRACE_MINUTES` | How long after midnight UTC a day's XP points are settled, so XP still on its way to the hub counts (T-712). XP that reaches the hub after a member's day settled isn't paid; a member the hub lists only after an outage (within 7 days) is paid for that day then. Held to 1 minute to 12 hours. | `15` |
| `RUNESYNC_MIN_CLIENT_VERSION` | RuneSync versions below this are told to update. | `0.0.0`: none are refused. |
| `LINK_DATABASE_URL`, `LINK_ENC_KEY`, `LINK_ENC_KEY_OLD`, `LINK_LOOKUP_KEY`, `LINK_ALIAS_KEY`, `LINK_SIGNING_KEY`, `LINK_PUBLIC_BASE_URL` | The RSN link service (section 3 above, and LINK-SYSTEM.md section 6.1). | The link service is off. |
| `RUNELINK_DM_SECRET` | Lets runeclans-hub have the bot DM RuneLink codes for it (T-166): the hub's `LINK_DM_BOT_SECRET`, sent as `X-RuneLink-Dm-Secret` (or the older `X-RsLink-Dm-Secret`, still accepted) to `/internal/rsnlink-dm/resolve` and `/send` on the internal API port. It opens those two routes only, never the rest of the internal API. At least 32 characters. The DM's buttons go back to the hub with the hub client (`RUNECLANS_HUB_*`). | The hub can't send DM codes (its DM routes answer 503). |
| `RSNLINK_DM_SECRET` | **Deprecated** old name of `RUNELINK_DM_SECRET` (from before RS Link was renamed RuneLink). Still read when the new name is unset; the bot logs a one-time warning. Rename it to `RUNELINK_DM_SECRET` (same value). | |
| `LINK_INTERNAL_URL` | Where the plugin ingest reaches the link service to check RuneLink assertions. | This bot's own internal API on `127.0.0.1:<INTERNAL_API_PORT>`. |
| `LINK_VERIFY_ENABLED`, `LINK_VERIFY_MIN_MATCHES`, `LINK_VERIFY_MIN_GAIN_XP`, `LINK_VERIFY_CHECKS_PER_LINK_DAY`, `LINK_VERIFY_CHECKS_PER_MINUTE` | The "plugin knew it first" RSN link check (T-170): `true` switches it on (needs the link service); the others tune it (see the paragraph in section 3). | Off; tuning defaults 2, 100, 24, 20. |
| `RUNECLANS_HUB_ENABLED` | `true` makes the bot a client of the RuneClans hub (T-158): RuneLink assertions are checked there, plugin events and request card changes come from its change feed every 3 seconds, and the bot's servers are registered with it. It also offers competitions the "RuneSync plugin" XP source (T-537): those are counted every minute from the hub's XP ledger (`POST /v1/guilds/{id}/xp/gains`, needs the hub's `HUB_XP=on` and the key's `events:read` and `links:read` scopes). Needs the two below; with either missing or malformed the client stays off and says why in the log. | Off: the bot's own RuneLink and plugin ingest do everything. |
| `RUNECLANS_HUB_URL` | The hub's address (`https://...`; plain http only for localhost). | The hub client is off. |
| `RUNECLANS_HUB_API_KEY` | The bot's first-party hub API key (`rsh_...`; scopes `link:resolve`, `players:read`, `feed:read`, `guilds:write`, and `leagues:report` for alliance leagues, T-759: the server's leagues, attaching a clan-vs-clan event to a round and reporting its result). Never logged. | The hub client is off. |
| `RUNECLANS_HUB_ACCESS` | Overrides the website's Switches panel switch "Beta access from the API" (`hub-access`, read by `PanelSwitches` every 30 seconds from the hub's `GET /v1/platform/switches`; the panel's last answer stands while the hub can't be reached); any value but `on`/`true`/`1`/`yes` holds it off whatever the panel says (a typo too), unset lets the panel decide. On, the bot reads the access gates (beta servers, access grants, site switches) from the hub's `/v1/access/...` instead of the bot's own tables, kept 10 minutes and emptied by the hub's `access.changed`; while the hub can't answer, its last answer for up to 15 minutes, then the bot's own tables. Also sends clans verified on the dashboard to the hub (`POST /v1/clans/bot-verified`). Needs the hub client (`RUNECLANS_HUB_ENABLED`) and the hub's `HUB_ACCESS=on`. Turn it on together with the website's hub Beta page (T-484): the bot's own Beta and switch editors still write the bot's tables. | Unset: the panel decides (off by default, so the bot's own tables decide, exactly as before). |
| `RUNECLANS_HUB_MEMBER_PRIVACY` | Overrides the Switches panel switch "Member privacy in the API" (`member-privacy`); any value but an on word holds it off, unset lets the panel decide; the panel's last answer stands while the hub can't be reached, so saves are held, not dropped. On, the bot also sends every member privacy change the bot saves to the hub (T-750): a member's own toggles (`PUT /v1/guilds/{id}/member-privacy/members/{userId}`, all three toggles once they named one) and Site Admin > Member privacy (`.../defaults`). Sent on its own lane, never in the website's request; each hub row keeps only its newest change until the hub takes it (so a held change never lands after a newer one), retried every 30 seconds, dropped when the hub refuses it. At most 10,000 rows are held; past that a new one is dropped and logged. Held changes are lost on a restart: the member's next save or the hub's move tool catches up. With several bot processes each sends what it saved, so two near-simultaneous saves of one row from different processes can land in either order. The bot's own tables stay the ones it reads. Needs the hub client (`RUNECLANS_HUB_ENABLED`) and the hub's `HUB_MEMBER_PRIVACY=on`. Go-live order: hub switch, this switch, then the hub's `MoveTool copy member-privacy --yes` (it only adds hides), all before the hub's `HUB_XP=on`. | Unset: the panel decides (off by default: nothing is sent; the hub keeps what the move tool last copied). |
| `EVENT_XP_TILES_ENABLED` | `true` counts bingo and scavenger XP tiles ("Gain 1M Fletching XP", "500k XP in Mining", T-543) from the RuneClans hub's XP ledger every minute (one hub call per event, or per server in clan-vs-clan): timed RuneSync XP from the later of the event's start and when the player joined (in an ordered scavenger hunt, when the step unlocked) until its planned end, catch-up XP excluded; a tile completes when the gain reaches the goal, and in a team event the team's gains add up. Only bingo and scavenger hunts count them. Finish such an event a few minutes after its planned end, so the last minute's XP reaches the hub first. Players without RuneSync (RuneMetrics only) make no progress on these tiles and the event pages say so. Needs the hub client (`RUNECLANS_HUB_ENABLED`) and the hub's `HUB_XP=on`. | Off: XP tiles take proof or an organiser like any other tile. |
| `SITES_BACKEND` | Overrides the Switches panel choice "Clan sites backend" (`sites-backend`, `bot` or `hub`); unset lets the panel decide, any other value holds it at `bot`; the panel's last answer stands while the hub can't be reached (the move is one way). It lives only as long as the process: a bot restarted while the hub is down uses `bot` until the hub answers (site writes are accepted, member privacy saves are dropped, until then), so once the move (D-18) is done set `SITES_BACKEND=hub` on Railway, a backend selector Railway may hold. `hub` once clan sites live on the RuneClans hub (flipped only after the move run, D-18). The bot then finds a server's site with the hub's `GET /v1/guilds/{guildId}/site` (kept 10 minutes, emptied by `site.*` changes), keeps the website's news, feed and poll channel picks as mappings keyed by the hub's site id (copied in from its own tables the first time), declares RuneClan Bot's capabilities on start, for all servers and per server (`PUT /v1/guilds/{guildId}/capabilities`, again on joining a server, an empty list on leaving one; this is what makes the hub's switchboard count the bot as connected) and reports who holds the roles the hub's `role-mappings` name (on start, and on role changes, one report per role). Its own site write routes (site builder, profile, recruiting, alliances, site pictures, the owner's site tools) answer `409 {"moved":"hub"}` and change nothing. Reads from the hub need the hub client (`RUNECLANS_HUB_ENABLED`); the moved answers don't. | Unset: the panel decides (`bot` by default: sites stay on the bot exactly as before). |
| `LINKED_ROLES_ENABLED` | `true` switches on Discord Linked Roles (`linkedroles` package; how it works and the admin how-to: `docs/LINKED-ROLES.md` in the runeclans repo): the website's `/linked-roles` page hands over a member's OAuth tokens, the bot keeps them sealed and sends their RuneScape metadata to Discord, hourly when it changes and daily at least. Needs the three below; with any missing it stays off and says which in the log. | Off. |
| `LINKED_ROLES_TOKEN_KEY` | 32+ random bytes, base64, that seal the stored OAuth tokens (AES-256-GCM). Changing it makes every stored token unreadable: those members are forgotten and must verify again. Never logged. | Linked Roles stays off. |
| `LINKED_ROLES_CLIENT_ID`, `LINKED_ROLES_CLIENT_SECRET` | The bot's Discord application id and OAuth client secret (the same values as the website's `DISCORD_CLIENT_ID` / `DISCORD_CLIENT_SECRET`), to refresh a member's access token and to name the app in Discord calls. | Linked Roles stays off. |
| `LINKED_ROLES_DISCORD_API` | Discord's API base, for tests against a local mock only (`http://localhost:...`; anything but https or localhost is refused). | `https://discord.com/api/v10`. |
| `LINKED_ROLES_REGISTER_ON_START` | `false` stops the bot registering the five metadata records with Discord when it starts (a PUT, so repeating it changes nothing). | Registers on every start while Linked Roles is on. |
| `LINKED_ROLES_REQUIRE_CONFIRMED` | `true` counts a RuneLink name for Linked Roles only once the RuneClans hub has confirmed the link from public data (`public_data_corroborated` or stronger). Names the bot verified itself still count. One switch for the whole bot: Discord keeps role-connection values per person, not per server. | Off: every active RuneLink name counts. |
| `RSCLANS_HUB_ENABLED`, `RSCLANS_HUB_URL`, `RSCLANS_HUB_API_KEY` | **Deprecated** old names of the three settings above (the hub is now runeclans-hub). Still read for one release when the new name is unset; the bot logs a warning. | |

Tests read `RUNECLAN_BOT_TEST_DATABASE_URL` (the older `RUNECLAN_BOT_TEST_DB_URL`, a JDBC URL, still works as a fallback), `RUNECLAN_BOT_TEST_LINK_DATABASE_URL`, `STARTUP_CHECK_DATABASE_URL` and `STARTUP_CHECK_REQUIRED` (section 5); the bot itself never does. The old names `JONNYBOT_TEST_DATABASE_URL`, `JONNYBOT_TEST_DB_URL` and `JONNYBOT_TEST_LINK_DATABASE_URL` are **deprecated** but still work when the new name is unset (T-662; CI still sets the old one).

**Renamed settings (T-662).** Every `JONNYBOT_*` setting the bot or its tests read is now `RUNECLAN_BOT_*` (and, from T-659, `RSNLINK_DM_SECRET` is `RUNELINK_DM_SECRET`). The new name wins; the old name is read only when the new one is unset, with the same value and behaviour as before, and the bot logs one deprecation note per process naming both variables (never the value). Nothing needs changing on Railway; rename at leisure. The website's `RUNECLAN_BOT_INTERNAL_API_URL` / `RUNECLAN_BOT_INTERNAL_API_SECRET` (older `JONNYBOT_INTERNAL_API_URL` / `JONNYBOT_INTERNAL_API_SECRET`) are settings of the website, not of the bot.

## 3b. Secrets and the secret scan (pre-commit hook)

**Rules.** Never commit, print or paste a real secret (Discord tokens, DB URLs and passwords, `INTERNAL_API_SECRET`, OAuth and Twitch keys, plugin tokens, live database credentials): not in code, tests, docs, PR text or logs. Never print `.env*` files or the run configurations wholesale; read a single key if you must, and never echo it. Test secrets are built at runtime (`TestSecrets.fake("token")` in the test sources) or are obviously fake (`TEST-SECRET-1`). The same goes for Discord ids: tests build made-up ids at runtime (for example `"1".repeat(18)`), never a real user, server, channel, role or message id. If a real secret was ever committed, stop and tell the repository owner (kind, file and commit, never the value): it must be rotated, and history is never rewritten by a PR.

Every commit is scanned for secrets by [gitleaks](https://github.com/gitleaks/gitleaks), locally; nothing is uploaded. CI runs the same scan ("Secret Scan" job) next to GitGuardian.

1. Get the pinned release (8.30.1) from the official releases page (`gitleaks_8.30.1_windows_x64.zip` on Windows), verify it against `gitleaks_8.30.1_checksums.txt`, and unzip the binary into a tools folder **outside the repositories** (for example `%LOCALAPPDATA%\tools`). Never add the binary to a repository.
2. Put it on `PATH`, or set `GITLEAKS_BIN` to its full path.
3. In each clone (and each worktree's repository, once): `sh tools/install-hooks` from Git Bash. It sets `core.hooksPath` to `tools/hooks`.
4. A commit with a secret in the staged changes is blocked and prints only the rule id, file and line, never the value. Remove the secret (a real one: tell the repository owner so it is rotated), build fake test values at runtime, or add a specific entry to `.gitleaks.toml`. Do not use `--no-verify`.

Check the whole tree by hand: `gitleaks dir . --redact=100 --no-banner`.

## 4. First run

1. Start Postgres and create the database: `createdb jonnybot_dev` (user `postgres`).
2. Get data into it. Either let the bot create empty tables on first start (it also creates BotCommands' own tables, schema `bc`; if the log ever complains they are missing, load `docs/bc-schema.sql` once as that file says), or restore a dump you are allowed to use. A dump of live data holds real members' data: keep it private, and **the dev bot must not run against that data in the real server**.
3. IntelliJ: open this repository, set the JDK to 21, import the Maven project, create the "JonnyBot Dev" run configuration with the variables above, build, run. Success looks like `Internal API listening on port 8081` and `Finished Loading!` in the log.
4. Website: in the website checkout, `npm ci; npm run dev` (port 3000). Sign in with Discord, open `http://localhost:3000/dashboard/<your-server-id>/event-manager`.
5. Only one bot instance may run per token: starting "JonnyBot Dev" again does **not** stop the old one (the second logs "Address already in use" for port 8081). Stop the old `com.runeclans.bot.Main` java process first.

Checking the running bot without the browser: `python tools/dev_api.py GET events/12/space --actor <your discord user id>` calls the same internal admin routes the dashboard uses (it reads the secret from the run configuration and never prints it). A GET only reads; remember the dev bot sits in the real server, so POST/PUT/DELETE change real things.

### Connect the dev bot to the local hub (T-811)

Players link on the website, and those links live in the RuneClans hub. A dev bot without the hub client checks RuneSync's RuneLink
assertions against its own old link keys, so every upload and the RuneSync clan page fail (the log now says so at start, and at most
hourly when an assertion with an unknown key arrives). For local tests set, in the bot's `.env` (or its run configuration):

- `RUNECLANS_HUB_ENABLED=true`
- `RUNECLANS_HUB_URL=http://localhost:8090` (the local hub)
- `RUNECLANS_HUB_API_KEY`: a first-party key made on the LOCAL hub (`POST /admin/apps` with the local hub's admin secret,
  `firstParty: true`, the scopes listed in 3c). Never a live key.

The hub's `tools/local_link_setup.py --bot-env <this repository>/.env` adds all three to `.env` when they are missing (it makes the key on the local hub and never prints it); run it once the local hub is up, then start the bot.

## 4b. Start everything with one command

From this repository, in a terminal:

```
powershell -ExecutionPolicy Bypass -File tools/dev-start.ps1
```

It stops every running bot and the website, rebuilds `local/wip-preview` in the website worktree (origin/dev plus every open website PR merged in; a PR that conflicts is skipped and named), builds and starts exactly one bot from whatever branch is checked out here, starts the website on port 3000, and prints whether both answer and how many bot processes are running (it must be 1). `-NoRefresh` leaves the website branch as it is. The bot's environment comes from the git-ignored `.idea/runConfigurations/JonnyBot_Dev.xml`, so no secret is in the script. Logs: `%TEMP%\jonnybot-dev.log` and `%TEMP%\website-dev.log`.

Do not also start "JonnyBot Dev" from IntelliJ: each launch adds another process, and the older one keeps port 8081 with old code.

## 5. Running the tests

```
mvn -o test                                      # whole bot suite (about 2,800 tests; database tests skip without a local database)
mvn -o test -Dtest='EventTeamsTest,EventsAdminApiTest'   # the database-backed event tests (EventsAdminApiTest alone takes ~2.5 minutes)
```

Database-backed tests (`EventSetupTest`, `EventTeamsTest`, `EventsAdminApiTest`, ...) use `LocalDatabase`, which refuses anything but 127.0.0.1/localhost and cleans up the rows it makes (made-up server ids). It finds the database in `.idea/workspace.xml` (the run configuration's `DATABASE_URL`) **or** in the environment variable `RUNECLAN_BOT_TEST_DATABASE_URL` (old name `JONNYBOT_TEST_DATABASE_URL`, still read); a git worktree has no `.idea`, so export the variable (same value as `DATABASE_URL`) when running tests there. It must be in the `postgresql://USER@127.0.0.1:PORT/db` form (`postgresql://USER:PASSWORD@...` with a password): a `jdbc:postgresql://...?user=` address has no host `LocalDatabase` can read, so every database test is skipped. `StartupWiringTest` also needs a password in it, and `docs/bc-schema.sql` loaded into that database first. Without it those tests are silently skipped (surefire shows "Tests run: 0" for them).

**Headless startup check (`StartupWiringTest`).** Boots the bot's real BotCommands wiring in a child JVM *without logging in to Discord* (the system property `jonnybot.headless=true` makes `Bot.createJDA` return early, so no gateway, no internal API, no schedulers), then runs every command provider's declaration step. It fails on "No service or factories found", an interface service missing `@InterfacedService`, a missing `SlashParameterResolver`, a bad constructor, a SQL error in a repository's table setup. It uses the local dev database (`RUNECLAN_BOT_TEST_DATABASE_URL`, old name `JONNYBOT_TEST_DATABASE_URL`) and is skipped when there is none; CI runs it against a throwaway Postgres (`STARTUP_CHECK_DATABASE_URL`, with `docs/bc-schema.sql` loaded first) and fails if the database is missing. Run it alone with `mvn -o test -Dtest=StartupWiringTest`. `ListenerRegistrationTest` is its static companion: every `@BService` `ListenerAdapter` must be a field of `Bot` or implement `BotListener`. **Limit:** it proves the wiring and the command definitions build, not that Discord accepts them or that a listener behaves; the "Finished Loading!" launch of the dev bot stays the check for those.

Website: `npx tsc --noEmit` (ignore the one `LayoutProps` error in a worktree: Next generates that type), `npx eslint <paths>`, `npx next build --webpack`.

### Working in worktrees

- **Website worktrees get a real `npm ci`.** Never a `node_modules` junction: `git worktree remove` through one deletes the real packages.
- **Stop dev servers and node processes before `git worktree remove`** (it fails with "Permission denied" while one holds the folder). Check `git worktree list` afterwards.
- **Delete `.next` when switching branches** in a website worktree: stale `.next/types` make `tsc` fail.
- **DB-backed bot tests:** set `RUNECLAN_BOT_TEST_DATABASE_URL` (or the old `JONNYBOT_TEST_DATABASE_URL`) to the local dev database (read that single key from the run configuration; never grep the whole file, it prints the password). Without it those tests skip silently ("Tests run: 0").
- **One dev bot only.** Port 8081 and the real server are shared: check that nobody else is running the dev bot, launch from your worktree, wait for "Finished Loading!", and stop exactly your own process afterwards.
- **No dev bot for website work:** run a small stub of the internal API (a fake server answering the routes the page calls) to render owner/non-owner views.
- **Tool notes (Windows):** use `python`, not `python3` (the Store stub); `psql` options must come before the URL; edit files with an editor, not heredocs or PowerShell `Set-Content` (they mangle backslashes and non-ASCII text); never print the contents of `.env`, run configurations or `.idea/workspace.xml`.

## 6. What is not in git

- The IntelliJ run configurations (`.idea`, with the secrets above; section 3 says how to recreate them) and the website's `.env.local`.
- The local databases: `jonnybot_dev` (and any copy of live data, which holds real clan data: treat a dump of it as private). Moving data is optional: `pg_dump -U postgres -Fc jonnybot_dev -f jonnybot_dev.dump` on one machine and `pg_restore -U postgres -d jonnybot_dev --clean --if-exists jonnybot_dev.dump` on the other. A fresh empty database works too.
- Git-excluded scratch files: bot `src/test/java/com/runeclans/bot/DevDb*SmokeTest.java` and `tools/DevDbSmokeTest.java.txt` (throwaway tests that run repository SQL against the local dev database), `src/test/.../BingoSkinScratchTest.java` (generates the bingo look preview pictures; a copy of the generator is in `tools/BingoSkinGenerator.java.txt`), `tools/DevDbPluginE2eTest.java.txt`.
