# The RuneClan Bot ecosystem

How the bot fits with the websites and the RuneClans hub, and the rules a change follows. Setting up a machine is in [DEV-SETUP.md](DEV-SETUP.md).

## 1. The pieces

| Piece | Where | Stack | What it is |
|---|---|---|---|
| **RuneClan Bot** (Discord bot) | this repository (`metorrite/JonnyBot`) | Java 21, JDA 6.4.x, BotCommands 3.1, PostgreSQL (schema `younglings`), Maven, shaded jar | The bot: commands, listeners, RuneScape tracking, tickets, events, points, moderation. Serves an internal HTTP API on port 8081 for the websites. |
| **Websites** | a separate, private repository (Next.js 16, React 19, Tailwind 4, NextAuth with Discord) | | runeclans.com (the clan directory, clan websites and the RuneClan Admin console at `/admin`), the bot's own site and per-server **dashboard** (every setting of the bot), and the Younglings clan site. The websites have no database of their own: they read and write through the bot's internal API and the hub. |
| **RuneClans hub** | a separate service with its own database | Java, PostgreSQL | The central data hub with a public `/v1` API: RuneLink (RSN to Discord linking), RuneSync plugin data, API keys and the change feed. The bot is one of its clients (`RUNECLANS_HUB_*`, DEV-SETUP.md section 3c). |
| **RS3 plugins** | separate | Jagex's plugin API | RuneSync and RuneLink: a player's own data, reported with their consent (docs/RUNESYNC-DESIGN.md, docs/LINK-SYSTEM.md). |
| Hosting | Railway (bot, websites, hub), Cloudflare (DNS) | | `master` of each repository deploys. |

## 2. How the pieces talk

- **Website to bot:** server side only, over the website's `RUNECLAN_BOT_INTERNAL_API_URL` (`http://localhost:8081` locally) with the shared secret in header `X-Internal-Secret` (the bot's `INTERNAL_API_SECRET`). Browsers never see it. Dashboard calls name the server (`X-Guild-Id`) and who is asking, and the bot checks that person itself.
- **Clan websites without the bot:** the website signs who is asking and which servers they manage (`X-RC-Actor`, `X-RC-Servers`, HMAC with the internal secret), so `clan-site/...` routes also work for a server the bot isn't in (docs/BOTLESS-SITES.md).
- **Bot to website push:** after a write that changes public data the bot calls the site's `POST /api/revalidate` (shared secret, cache tags) so pages refresh at once; the cache timers stay as the fallback. See docs/SITE-INVALIDATION.md (needs `SITE_URL` / `CLAN_SITES_URL` on the bot).
- **Admin routes on the bot:** `/internal/admin/<feature>`. `TicketAdminApi` checks the asker may use the dashboard in that server, rate-limits writes, audit-logs, then offers the path to every `AdminRouteExtension` bean. A new feature is a new `@BService` implementing `AdminRouteExtension`; no edit to `TicketAdminApi`. New `/internal/me/<x>` routes are an `InternalRouteModule`.
- **Access:** dashboard access is per server (owner, Administrator/Manage Server, or configured admin roles). Bot-owner-only things use `BotOwners` (the Discord application's owner or `OWNER_IDS`).
- **Premium:** a hand-picked server list managed in the RuneClan Admin console (owner only). It gates faster polling and the bot's server avatar, banner and bio. Premium and billing stay off unless switched on (`PREMIUM_ENABLED`).
- **Data stores:** the bot's data lives in its PostgreSQL schema `younglings`. Tables are created by each feature through `SchemaBootstrapper` (idempotent `CREATE ... IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`).
- **Dev snapshots (bug reports):** an owner-only zip of the bot's state with a stable id (`snap-yyyyMMdd-HHmmss-xxxx`), taken from the dashboard or `GET/POST /internal/admin/dev/snapshot`. Format, focus and redaction rules: [DEV-SNAPSHOT.md](DEV-SNAPSHOT.md). Read one with `tools/snapshot_inspect.py`, and the local database with the read-only `tools/db_query.py`: [BUG-REPORT.md](BUG-REPORT.md).
- **Clan and community websites:** every hub site is a clan website (one registered clan) or a community website (any Discord server, members join through the site). The hub owns the community, its members and the requests board; the bot is optional for both. With the hub connected (`RUNECLANS_HUB_*`) the bot posts request cards with Accept and Decline buttons when a site turns on "Handle requests in Discord", runs `/community join` and `/community leave`, reports the community role's holders, and polls community members as their own tier (`RUNESCAPE_COMMUNITY_POLL_WINDOW_MINUTES`). The website and hub switches are on RuneClan Admin > Switches ("Community websites", "Requests board", "Track every linked player"); the bot has no switch of its own: a hub route answering 404 means the feature is off.
- **Multi-server:** the bot runs in many servers. Anything that names a clan or assumes the home server is wrong. Use `BrandingService.displayName(guild)` for names and `GuildSettingsService.getEffective(guildId)` for settings (env defaults apply to the home server only, `GUILD_ID`).

## 3. Rules for a change

**Git**
- Feature branches off `origin/dev`, one PR into `dev`. Don't stack a branch on unmerged work unless the code really depends on it, and say so in the PR.
- `master` is a real deploy to Railway: never merge, push or open a PR to it without the owner's go-ahead. Never force-push.
- LF line endings (`.gitattributes`).
- Before review, `git diff --diff-filter=D --name-only origin/dev...HEAD` must list nothing you did not mean to delete.

**Where a control goes**
- **The bot's dashboard** holds everything that changes how the bot behaves: roles, commands on/off, channels the bot posts to, tracking, tickets, welcome, events, member and points data.
- **A clan website's own admin** holds only website behaviour (what the site shows, its news-feed channels, site options). It may read bot data, but never changes bot behaviour. Bot-wide, owner-only controls (premium servers, beta servers, access switches) are in the RuneClan Admin console.
- Skipping a surface is fine only when it has no meaning there (a purely website option needs no Discord control; a member's personal command like `/afk` needs no admin page unless it has server settings). Say why in the PR.

**Bot (Java)**
- **Website first.** The dashboard is the main way to change how the bot behaves; Discord-only use keeps working. Every new setting, setup step or slash command gets its dashboard side (a control, the Commands page, a page and nav entry for a new feature) in the same change as the bot side.
- New `ListenerAdapter`s must be registered in `Bot.java`, or their buttons silently time out.
- **Privileged intents:** `Bot.getIntents()` passes the wanted intents through `PrivilegedIntents.filter`, which leaves out the ones Discord has off for the application (logging which features lose out), so the bot still starts. Features that read message text check `PrivilegedIntents.messageContent()`.
- Tests: the full suite (about 2,800 tests) runs with a database, or the database tests skip silently (DEV-SETUP.md section 5). A throwaway JUnit against the local dev database is the way to check new SQL.
- The dev bot sits in a real community server (`GUILD_ID`): live tests are real. Keep them small and clean up after.
- Don't invent facts about Discord or JDA limits: look them up or test them. List what you could not verify (signed-in pages, anything against live Discord) in the PR.

**Secrets**
- Never commit, print or paste a real secret (Discord tokens, DB URLs and passwords, `INTERNAL_API_SECRET`, OAuth and Twitch keys, plugin tokens, live database credentials): not in code, tests, docs, PR text or logs.
- Never print `.env*` files or `.idea/workspace.xml` (the run configurations) wholesale; read a single key if you must, and never echo it.
- Test secrets are built at runtime (`TestSecrets.fake("token")` in the test sources) or are obviously fake (`TEST-SECRET-1`). Discord ids in tests are made up at runtime (for example `"1".repeat(18)`), never a real user, server, channel, role or message id.
- A gitleaks pre-commit hook (`sh tools/install-hooks` once per clone, DEV-SETUP.md section 3b) and the CI "Secret Scan" job check every change. Scan output is always redacted (rule id, file, commit). Don't bypass the hook with `--no-verify`; an allow-list entry in `.gitleaks.toml` needs a reason and must be specific.
- If a real secret was ever committed: stop and tell the repository owner (the kind, file and commit, never the value). Rotation is the owner's call, and history is never rewritten by a PR.

## 4. Running it locally

DEV-SETUP.md section 4 sets up the database, the bot and the website; section 4b starts everything with one command (`tools/dev-start.ps1`). Only one dev bot may run at a time.
