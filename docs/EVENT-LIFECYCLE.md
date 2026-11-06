# The event lifecycle project

> **T-942 (2026-11-06):** the bot dashboard's event manager is gone. Events are set up and run on runeclans.com (`/events/host`) against the RuneClans hub; the bot's dashboard routes below (`EventsAdminApi`, `EventSetupApi`, `EventWinApi`, `EventUpdatesApi`, `EventWrapupApi`, `EventSpaceApi`, `EventCohostApi`, `BingoPoolAdminApi`) were deleted. The Discord-side classes this page describes (spaces, wrap-up steps, boards, panels) stay. Read the route and test names below as history.

What was asked for, what has been decided, what is built, and what is next for the event lifecycle (first written 2026-10-17).

## The goal

Clan staff run RuneScape clan events (bingo, scavenger hunts, XP competitions, boss nights) through RuneClan Bot and the clan dashboard. The event engine (create, objectives, teams, players, proof, standings, buy-ins, prizes) already existed. This project turns it into a full lifecycle:

**build -> take signups -> close signups and confirm the roster -> (verify players) -> build teams -> make Discord roles and channels -> run, with players submitting proof -> finish -> wrap up (export, archive, delete).**

## Requirements (Drake's words, condensed) and decisions

- R1-R4 Bingo looks: keep both the template pictures ("classic") and the RuneScape-themed ones; shapes (path, pyramid, convergence = "The Boss Convergence", world tree) plus the normal grid, now with backdrops; options to customise each.
- R5-R9 Every event gets channel, player/team and board management that coincide, and a player-facing area. The bot makes **event roles** for signed-up players (website and Discord signups stay in sync); marking signups closed pauses the signup and syncs the roster **one last time**; roles then drive a category and channels; a **"Set up event channels"** button only works once signups are over, the roster is confirmed and (team events) the teams are final.
- R10-R11 Team events: an admin builds teams by hand by skill (some bigger and weaker, some smaller and stronger), in a friendly web interface **and** Discord commands, then finalises them.
- R12-R17 Channels: every channel name from a pattern setting (`{event} General Channel` -> `christmas-bingo-general-channel`); per channel the bot **creates it or the admin picks an existing one**, independently; picked channels are validated; the next phase is blocked until everything the engine needs exists. Per event: its own category, a staff+organiser channel, an announcement channel (participants read, organisers post), a general channel, optional board/standings channels, private team channels, optional per-player channels for solo events. Discord allows 50 channels per category, so extra categories are made automatically (decided, not optional).
- R18-R19 `/submit`: the event is worked out from the channel it is used in; an option makes `/submit` the only way to submit; each submission goes to the player's/team's channel and (optionally) a staff-only channel. Decided shape: `/submit event` and `/submit event-verify` (subcommands).
- R20/R22 Verification before the event (so nobody hoards): pending players sit on the roster but are excluded from teams unless an organiser lets them through; the gate (at signup / before teams / before start) is movable.
- R23 Setup streams in the web interface: Quick, Standard, Guided; saved templates; drafts.
- R24 Wrap-up options (undecided in detail): export everything to a zip of JSON files, delete all, archive into archive channels, compress/transcribe (e.g. every solo channel into one), de-duplicate bot messages, and a role-retention schedule independent of the channel schedule (e.g. keep general and announcements viewable a week).
- D10 Participants must join the host Discord to take part (so roles and channels only concern members).
- Parked: an events subdomain hub for open community events (`events.rsyounglings.com`).

## Slices and status

| # | Slice | Bot | Website | State |
|---|---|---|---|---|
| 1 | Bingo looks, grid backdrops, options | #186 | #75 | merged into dev |
| 2 | Lifecycle foundation: close signups, roster, verification, readiness checklist | #187 | #76 | merged into dev |
| 3 | Setup streams (standard/quick/guided), drafts, saved templates, options registry | #188 | #77 | merged into dev |
| 4 | Team builder: ratings, strength, suggestion, finalise, `/events team` | #189 | #78 | merged into dev |
| 5 | Roles and channels (see below) | #191 | #79 | built and tested, PRs open (not merged); never run against Discord for real |
| 6 | `/submit event` and `/submit event-verify`, channel inference, receipts, require flag | | | not started |
| 7 | Player-facing event area on the website (my events, team, board, standings, submissions, links to channels) | | | not started |
| 8 | Wrap-up: export zip, delete, archive, compress, de-duplicate, role retention; docs/guide text | | | not started |

Nothing from these slices is on `master`: it needs Drake's explicit go-ahead (it deploys). After slice 8 comes refinement.

## How the engine fits together (bot, `com.runeclans.bot.events`)

- `EventService` applies the rules; `EventRepository` owns the tables (`SchemaBootstrapper` creates them at startup, `ALTER ... IF NOT EXISTS` for new columns). Standings are never stored: `EventScoring` works them out from the append-only progress log.
- Stages: DRAFT, SIGNUPS, RUNNING, SCORING, DONE, CANCELLED (`EventRules`).
- `EventPrep` (pure): the "before it starts" checklist: build, signups, roster, verification, teams. Mode OFF/STANDARD/FULL decides whether steps are required. `EventRoster` (pure): verification states and the gate. `TeamBuilder` (pure): ratings, suggestion, "what stops the teams being final". `TeamRules`: who may join and share a team.
- Templates (`EventTemplate`: custom, bingo, scavenger-hunt, competition, boss-night). `EventOptions` is the **single registry of every creation option** (path, label, step, type, standard value, required, show-when, quick); `EventSetup` creates events from answers, keeps drafts (`event_draft`) and saved templates (`event_blueprint`); the website renders its three setup streams from the registry's schema, so a new option appears everywhere.
- `EventChanges` is a tiny bus (PROGRESS, STAGE, SETUP, CLAIM_DECIDED, ROSTER, TEAMS) that Discord-facing parts subscribe to (`EventBoardPanels` redraws the live bingo board).
- Website routes live in `EventsAdminApi` (+ `EventSetupApi`), reached through the dashboard's allow-listed server action `eventApiAction` (`src/app/dashboard/[guildId]/event-manager/actions.ts`). Discord commands: `/events ...` (`EventsCommand`), `/events team ...` (`EventsTeamCommand`).
- Website: `EventManager.tsx` (the event's tabs), `setup/` (the three streams, wizard, live summary), `teams/` (the team builder), `src/lib/eventOptions.ts` and `eventTeams.ts` (pure helpers mirroring the bot).

## Slice 5: roles and channels (built, PRs open)

What it does, per event that switches it on (`config.channels.enabled`, off by default), once signups are closed, the roster is confirmed and (team events) the teams are final:

- **Roles** the bot makes (recorded so it only ever touches its own): participant, organiser, one per team. Membership follows the effective roster (players who count at the current verification moment, so unverified players never get roles), teams and organisers, kept in step when they change.
- **Channels** (slots): category (plus overflow categories at 50 channels each), staff, announcements, general, board, submissions (staff queue), a private channel per team, optionally one per player for solo events. Names from patterns; each slot is created by the bot or an existing channel the admin picked (validated).
- **Access:** @everyone cannot see any of it; staff roles and organisers see and post everywhere; participants read announcements and the board, post in general; a team's role sees only its channel; a player sees only theirs.
- **Preview, then apply:** a pre-flight (bot permissions, role hierarchy, headroom under Discord's limits of 250 roles, 500 channels, 50 channels per category, 100 permission overwrites per channel) and a dry-run list of everything that will be created or changed, then apply; applying is idempotent and resumable (each created id is saved as it goes; pressing it again only creates what is missing and repairs drift). A way to remove everything the bot made for an event.
- Opt-in per event (`config.channels.enabled`), so existing events are unaffected; when on, "Roles and channels" becomes a required step of the checklist in an event that follows the setup steps.

### Where it lives

Bot (`com.runeclans.bot.events`): `EventNaming` (names from patterns, Discord's style), `EventSpace` (pure: the settings `config.channels`, who sees what per kind of channel, the readiness rule, the `Plan` of what to create/keep/remove with Discord's limits, who should hold each role), `EventSpaceRepository` (tables `event_role`, `event_channel`, `event_role_member`; the applied/complete/note state is on `event_setup`), `EventSpaces` (reads the roster, teams, staff roles, server room and permissions, and previews), `EventSpaceOps` (does it on Discord: background setup and removal jobs, role holders kept in step on roster/team/stage changes, member joins and a 15-minute sweep). Dashboard routes: `internal/EventSpaceApi` (`GET events/{id}/space`, `PUT .../space/settings`, `POST .../space/preview|apply|remove|sync`). Tests: `EventNamingTest`, `EventSpaceTest`, `EventSpaceRepositoryTest`, `EventSpaceApiTest`, `EventSpaceOpsTest` (a pretend Discord server), and the checklist step in `EventPrepTest`.

Website: `components/dashboard/space/ChannelsTab.tsx` (the Channels tab), `lib/eventSpace.ts` (pure helpers), types in `lib/eventTypes.ts`, allow-list entries in `event-manager/actions.ts`, a button on the checklist step in `EventManager.tsx`.

### Choices I made that Drake may want to change (unconfirmed)

- Default names are short (`{event} Staff`, `{event} Announcements`, `{event} General`, `{event} Board`, `{event} Review`, `{event} {team}`, `{event} {player}`; roles `{event} Participant`, `{event} Organiser`, `{event} {team}`); his example `{event} General Channel` is just a pattern he can type. The patterns are per event; a server-wide default pattern setting was not built.
- Staff = the Admin group in the server's settings (plus anyone with the Administrator permission, who sees everything anyway). Organisers of an event get their own role. Players of a team event who aren't on a team yet still get the participant role.
- Team roles are only made when team channels are on (roles are scarce: 250 per server). Per-player channels (solo events) are off by default.
- A channel an organiser picks is used as it is: RuneClan Bot never changes who can see it. The board and proof channels RuneClan Bot makes become the event's `boardChannelId` / `reviewChannelId`.
- Setting up again only makes what is missing; it does not repair permissions or names of channels that already exist (a possible refinement), and removing a team leaves its role and channel until "Bring them up to date" with the remove box ticked.
- Unverified players (past the gate) and players on the waitlist get no roles. A player who isn't in the Discord server yet is skipped and gets the role when they join (D10).
- No welcome message is posted in the new channels yet.

### What is not proven (never run against Discord for real)

Creating the category, roles and channels on a real server, the exact permission behaviour of the overwrites in Discord's client, role grants to real members, and how long Discord's channel-creation rate limit makes a big event take. A preview was checked read-only against the real server (staff roles, room, picked channels). **How to try it** (carefully, the dev bot lives in the real clan server, so everything made is real but hidden from members): make a small test event with yourself as the only player, switch on roles and channels on its Channels tab, close signups and confirm the roster, press Set up, look at the server's channel list as a member without the roles (nothing should be visible) and as yourself, then Remove them.

## Decisions I made that Drake may want to change

Setup defaults (one `standard` value per option in `EventOptions`/the templates' `options()`): starts a day after creation, runs a week, solo, clan only, no limit, mix freely, first come first served, no buy-in, no prizes, setup steps ON from the dashboard (OFF for Discord quick-create), verification off (gate before teams), bingo 5x5 RuneScape look boss-drop pool 50 per line 250 blackout, hunt 8 items 25 pts unordered 100 bonus, competition overall 1 point per 10,000 XP, boss night 10 kills 10 pts.
Verification: a pending player counts until the gate's moment; the deadline closes signups for every event that has one; confirming the roster survives late adds, reopening signups resets it.
Teams: ratings 1-5 (3 = unknown); the suggestion levels totals (strongest first onto the weakest team); strength = total XP of the linked RuneScape name; teams can still be changed after they are final and reopened until the event starts.

## Slices 6 to 8 (built 2026-10-17)

Slice 6, `/submit`: `/submit event` takes the event from the channel it is used in (or an `event` number), the tile from an autocomplete of the tiles the player can still send proof for (a bingo tile number also works), and a screenshot. `/submit event-verify` sends the proof an event asks for before it starts (a claim of kind VERIFICATION: approving verifies the player, denying turns them down with the reason and they may send again). Proof goes to the review channel with the Approve/Deny buttons; a receipt is posted in the player's own (or team's) channel and edited when staff decide. Settings `submissions.require` (players may only send from their event channels; staff and organisers from anywhere) and `submissions.receipts` (default on). `/events submit` is unchanged. Not built: a gentle reminder for a screenshot posted loose in a channel (needs the message-content intent).

Slice 7, the player's event page: website `/clan-events` (your events, open events, Join) and `/clan-events/[id]` (timeline, verification, team, channel links, your board or objectives, standings, what you have sent, prizes), from the bot's `GET /internal/me/events/detail`. (`/events` was already the Discord scheduled events page.)

Slice 8, wrapping an event up (Wrap-up tab on the event manager, bot routes `events/{id}/wrapup`): settings `wrapup` {lockOnFinish (default on), rolesDays (default 7; -1 never), channelsDays (default -1: the organiser decides), channelsAction (ARCHIVE | DELETE | NONE), archiveCategoryId}. Defaults: when the event finishes its channels go read-only, its roles are taken back after 7 days, its channels stay until an organiser acts. The schedule runs every 15 minutes (`EventWrapupOps.sweep`), once per step, only on roles and channels RuneClan Bot made. Actions an organiser can start: lock/unlock, take the roles back, archive (channels move into an "Event archive" category only staff can see, the event's own categories are deleted), compress (each private channel's messages become a transcript file in one staff-only channel, the originals optionally deleted), export (a zip: event.json, messages.json, each channel as .json and .txt in a folder per category, and the images up to 12 MB, the rest as links; the bot's duplicate receipts folded into one; kept for an hour for download), delete (the existing take-down). Everything that deletes asks for confirmation. Not built: unlocking happens by hand only, a reopened event starts its wrap-up over but its channels stay read-only until Unlock.

## Phases, forward and back (safeguards, 2026-10-17)

Signups open -> signups closed -> roster confirmed -> teams final (team events) -> roles and channels set up -> start. Each step is only open once the one before it is done, and going back takes what depends on it back:

- **Reopening signups** takes back the roster confirmation and the final teams (and the Discord signup is switched on again, its panels redrawn).
- **Changing the roster after it was confirmed** (a player joins, leaves, is removed or barred, or a verification is decided) does not silently keep the old confirmation: the Roster step goes back to "confirm it again" and the start is held until it is (`event_setup.roster_changed_at` against `roster_confirmed_at`).
- **Changing the teams after they were called final** (a team added, renamed or removed, a player moved) does the same for the Teams step ("finalise again"; `teams_changed_at`).
- **The roster and the Discord signup follow each other** until the event starts (`event_participant.source`: SIGNUP follows the signup, SELF joined on their own, STAFF added by staff). A player taken off the signup in Discord is taken off the roster and their team; one removed on the website loses their entry.
- **Barring** (`event_ban`) removes a player and keeps them out of this event by every road until the ban is lifted.
- A checklist-free event (setup mode "start whenever I like") is never held up by any of this.

## What is not proven yet

Discord-side code has needed fixes the first time it ran for real every time. Not yet run in Discord: closing signups and the pause, the join messages with verification, `/events create` from the standard answers, `/events team ...`, board posting with the new looks. Test on the dev bot with your own account and an alt. The dev bot runs in a real community server (the one set as `GUILD_ID`), so tests there are live: keep them small and clean up after. Lessons that bit before: a new enum slash option needs a resolver in `EventsResolvers` or the bot will not start; a new `ListenerAdapter` must be registered in `Bot.java`; starting the dev bot twice leaves two bots on one token.

## Refinement pass (T-051, 2026-10-20): decisions made for Drake

Drake asked to "revisit everything" once all slices were done, then (2026-10-20) to decide with sensible defaults while he is away. Every open question and "choice he may change" above is answered here. **Keep** means today's behaviour stays and nothing is built; **Build** names the ticket that changes it. He can overturn any line; each is one setting or one small ticket.

| # | Question | Decision (default) | Why | Ticket |
|---|---|---|---|---|
| E1 | Default look for new bingos | **Keep RuneScape** (`BingoLooks.DEFAULT_FOR_NEW`); classic stays a choice; a server can change its own default | It is the look he built for, and both stay | T-208 (server default) |
| E2 | Setup steps on by default for dashboard events, off for Discord quick-create | **Keep** | Quick events stay quick; dashboard events get the safety steps | none |
| E3 | Verification gate default | **Keep "before teams"**, verification itself off; a server can set its own default gate (his instinct was the signup phase) | Least surprising for solo events; servers that verify at signup set it once | T-208 |
| E4 | Gate semantics (pending counts until the gate; only verified/waived score once running) | **Keep** | Matches D1 | none |
| E5 | Deadline closes signups for every event with a deadline | **Keep** | One rule, fewer surprises | none |
| E6 | Roster confirmation vs late adds | **Keep** the safeguard version (any change asks to confirm again; reopening signups resets it) | Built in the phase safeguards | none |
| E7 | Team ratings, suggestion, strength | **Keep** (1-5, 3 unknown; strongest onto weakest; total XP of the linked RSN) | Works; tune after real use | none |
| E8 | Channel/role name patterns | **Keep the short per-event defaults**, and **build a server-wide default pattern** per slot that new events copy | He wanted "Event General Channel" style available without typing it each time | T-208 |
| E9 | Staff = Admin group | **Keep**; the events-only coordinator tier (T-060) adds a narrower role | One source of staff | none |
| E10 | Team roles only with team channels; per-player channels off | **Keep** | 250-role limit | none |
| E11 | Picked (existing) channels are never changed | **Keep**; "Fix permissions for me" stays an explicit button | Never surprise an admin | none |
| E12 | Repair names/permissions of channels RuneClan Bot made | **Build**: "Bring them up to date" also repairs drift on bot-made roles/channels, shown in the preview first | Admins rename or edit by accident | T-210 |
| E13 | Welcome message in new event channels | **Build, off by default**: one message per slot (announcements, general, team, player) with tokens, posted once when the bot makes the channel | Optional-off rule | T-209 |
| E14 | Standings visibility | **Keep "all participants"** as the default and **build the option**: participants / everyone in the server / staff only / hidden until the event ends | Some clans hide standings for suspense | T-211 |
| E15 | `/clan-events` path name | **Keep** `/clan-events` (and `/events` stays Discord scheduled events); the events hub (T-006) brings a host-aware `/e/<id>` later | No churn now | none |
| E16 | Shared live board channel made for an OWN-only bingo | **Already fixed** on dev: `EventSpace.choice` skips the shared board channel for an own-only bingo when team or player channels are on (it stays only as the fallback) | Checked in code 2026-10-20 | none |
| E17 | Wrap-up defaults (read-only at once, roles back after 7 days, channels kept until an organiser acts) | **Keep** | Safe: nothing deleted without a person | none |
| E18 | Reopened event stays read-only until Unlock | **Build**: reopening an event unlocks the channels RuneClan Bot locked (setting `wrapup.unlockOnReopen`, default on) | Reopening is already an explicit staff act | T-212 |
| E19 | What else the export holds | **Build**: add `claims.csv` (every submission with decision, decider, time; the claims are already in `event.json`), `standings.csv` and `participants.csv` for spreadsheets | What staff ask for after an event | T-212 |
| E20 | Bingo customisation beyond the look | **Already ticketed**: options T-052, variable shape sizes T-108; no new decision | | none |
| E21 | Empty glyph boxes in `path.png` | **Build**: paint them out in both looks | Cosmetic, safe | T-213 |
| E22 | Gentle reminder for loose screenshots | **Already ticketed** (T-057) | | none |
| E23 | One pending verification at a time; deny = REJECTED; receipts on | **Keep** | Simple to review | none |

Live Discord testing of slices 2-8 stays the script in `docs/LIVE-TEST-SCRIPT.md` (Drake runs it; the dev bot is in the real server).

## Board update rules (T-650)

When and how the bot posts a bingo's board, per event, in `config.updates` (`BoardUpdates`). Every part is "as today" or off until an organiser changes it on the event manager's **Updates** section, so an existing bingo behaves exactly as before.

| Setting | Default | What it does |
|---|---|---|
| `mode` | `LIVE` | `LIVE`: the board is redrawn a few seconds after each approval (today). `BATCHED`: at most once every `everyMinutes` (5 to 720, default 30). `DAILY`: once a day at the daily reset (00:00 UTC). Only approvals and tracked progress are held back: a start, a finish, a settings change or the dashboard's refresh button always redraw at once. |
| `recap` | off | After each reset, a recap of the day that ended: the tiles finished that day and the top 3. When the event hides its standings (`STAFF`, or `HIDDEN_UNTIL_END` while it runs) the recap lists the tiles without who finished them and leaves the top 3 out. A day with no finished tile posts nothing. Posted at most 2 hours late if the bot was down at reset. |
| `firstLine` | off | Announces the first team or player to finish a line (any row, column, diagonal or shape group that has a bonus). |
| `firstBlackout` | off | Announces the first team or player to finish every tile. |
| `firstTile` | off | Announces the first time anyone finishes each tile (one post per tile). |
| `channelId` | none | Where recaps and milestones go. Without one: the event's announcements channel if the bot made it, else the live board channel. |

How it works:

- `EventBoardPanels` holds a board back (`afterPause`) and a one-minute check on its own `BotScheduler` lane redraws it when due (`flushHeld`). What is held is kept in memory; after a restart the next approval holds it again.
- `EventBoardUpdates` checks milestones a few seconds after progress and posts recaps after the reset, on its own lane. Each post is noted in `younglings.event_update_post` (by key: `recap:<day>`, `line`, `blackout`, `tile:<key>`) before it is handed to the Discord outbox (`EventUpdatePostJob`, kind `event.update.post`, never repeated after an unclear failure), so nothing is posted twice. Posts carry names, never mentions, and allow none.
- While the event hides its standings no milestone is announced (or noted as posted), since each one names who is ahead.
- `updates` is saved only through its own route: a settings save (`PUT events/{id}`) or a re-rolled board keeps what was there (`EventService.keepUpdates`).
- A milestone reached more than 6 hours ago is never announced, so switching a milestone on mid-event doesn't post old news.
- A bot that is not live (`/dev toggleposting` off) holds back nothing and posts no recap or milestone.
- Routes: `GET/PUT events/{id}/updates` (`EventUpdatesApi`: the rules in words, a preview of each post, when a held-back board is next drawn, the latest posts). The player's event page (`/internal/me/events/detail`) gets `updates` (the rules in words and the latest posts) when an event uses anything but the defaults or has posted something.
- Tests: `BoardUpdatesTest` (pure rules), `EventBoardUpdateRulesTest` (every rule on a pretend Discord server), `EventUpdatesApiTest` (the routes and the post table).

## Ways to win (T-649)

How an event is won, per event, in `config.win` (`WinModes`). An event that never saved it is won on points exactly as before; every other way is an organiser's choice on the event manager's **How it's won** tab.

| Setting | Default | What it does |
|---|---|---|
| `mode` | `POINTS` | `POINTS`: the standings decide the places (today). `DRAW`: the bot draws winners among everyone who qualified. `FIRST_LINE` / `FIRST_BLACKOUT` (bingo only): places go in the order teams or players first finished a line (any bonus group but the whole board) or every tile. |
| `entries` | `PER_TILE` | Draw only. `PER_TILE`: one entry per finished tile. `PER_PLAYER`: one entry each (one per team in a team event). |
| `minTiles` | 1 | Draw only. Finished tiles needed to qualify (1 to 1000). |
| `winners` | 1 | Draw only. How many the draw picks: places 1 to N (up to 25). |
| `autoDraw` | on | Draw only. The bot draws by itself at the event's end time. Staff can always press "Draw now". |

How it works:

- The draw reuses the giveaway's fair pick (`GiveawayDraw.pick`) over numbered entries, with a `java.util.Random` seeded from a number the bot chooses. Entries are listed in a fixed order (subject keys sorted, each one's entries together); the seed and the list are saved with the draw (`younglings.event_draw`, one row per round) and shown on the event page and in the post, so anyone can re-run `WinModes.draw` and get the same winners.
- Only tiles finished by the end time count, and only players still in the event (not on the waitlist) and teams that still exist.
- **Automatic draw**: the hub draws a draw event at its end time and announces it on its feed (`clan_event.draw`); the bot only posts what the feed says (`HubEventPosts`, T-941). A bot that is not live (`/dev toggleposting` off) posts nothing by itself.
- **Draw now**: staff, from the website (`POST events/{id}/win/draw`). Allowed once the event is running, and again only if the last draw found nobody who qualified.
- **Reroll**: for a winner who doesn't claim (`POST events/{id}/win/reroll` with `{place}`): a new winner from the same entries, never anyone drawn before; the old winner's pending awards at that place are revoked. Refused once that prize is paid.
- **Prizes**: the draw records the winners' prizes from the event's prize list as **pending** awards (`PrizePlan`, fixed amounts or pot shares, split for teams as set); nothing is paid until staff mark an award paid. For first to a line or blackout, "Work out awards" uses the finishing order instead of the standings.
- **Posts**: the draw and each reroll are announced where the event's update posts go (T-650: the chosen channel, the announcements channel, else the board) through the Discord outbox (`EventUpdatePostJob`, key `draw:<round>`, noted once in `event_update_post`), with names, never mentions.
- The win rules can change until the winners are drawn or the awards worked out, and not once the event is over.
- Routes: `GET/PUT events/{id}/win`, `POST events/{id}/win/draw`, `POST events/{id}/win/reroll` (`EventWinApi`, staff only through the dashboard). The player's event page (`/internal/me/events/detail`) gets `win` (the rules in words and the draw with its seed and entries) when the event isn't won on points.
- Tests: `WinModesTest` (pure: every mode, the draw, rerolls, finishing order), `EventDrawsTest` (the automatic draw, Draw now, Reroll, awards and posts over Postgres), `EventWinApiTest` (the routes).
