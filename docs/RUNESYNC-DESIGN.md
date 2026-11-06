# RuneSync: the RS3 clan plugin (design)

Ticket T-002. Status: **draft with defaults** (Drake away, 2026-10-20: "build everything ... except where you absolutely need my input"). Every open choice has a default in section 13; nothing here blocks on Drake except the items in section 14.

Read with: `docs/LINK-SYSTEM.md` (RuneLink, T-112) and `docs/ECOSYSTEM.md`.

## 0. In one paragraph

RuneSync is a Lua plugin in each member's own RS3 client. It reports **only the local player's own** XP, loot, boss kills and clue completions to RuneClan Bot's existing plugin ingest API, and only the categories that member has switched on (everything starts off). It does not pair by itself: it depends on **RuneLink** (T-116), asks it for a short-lived signed assertion, and sends that with every batch, so RuneClan Bot knows which Discord member the data belongs to without RuneSync ever holding a secret of its own. The data feeds what already exists (event objectives through the hub's progress routes, the points ledger, the member profile) and every setting lives on the website first: the member's profile for personal consent and data, the dashboard for clan settings.

## 1. Hard limits (not design choices)

- **Own data only.** Jagex's rule: nothing about other players leaves the client. No roster scans, no "who is nearby", no other players' names in any payload. Clan-wide views are built server-side from many members each reporting themselves.
- **No automation.** No actions for the player, no menu entries that send actions, no unhidden interfaces, no PvP info.
- **HTTP only to the manifest base URLs.** No redirects or shorteners. The base URL is moderated, so it must be final before the first submission (section 11).
- **Code Drake can review line by line.** Readable, commented, no obfuscation; numbers-and-dots versions.
- **The game must be open.** Gaps and resends are normal; the ingest is idempotent per event id (already built).
- **No clan data API today** (VarClan etc. unavailable), so RuneSync cannot read clan rank or clan chat membership; the server decides clan membership from the roster as today.

## 2. What already exists (reuse, don't rebuild)

| Piece | Where | Used for |
|---|---|---|
| Ingest API, schema v1, per-event rejects, 100-event batches, 14-day max age | `plugin/PluginPayload.java`, `internal/PluginApi.java` (`/internal/plugin/ingest`, `whoami`, `me`) | every RuneSync upload |
| Consent per category, enforced on the server | `plugin/PluginCategory.java`, `PluginRepository` (`plugin_consent`) | gate on what is stored |
| Idempotent store, retention 180 days | `PluginRepository`, `PluginRetention` | raw events |
| Event sinks | `PluginEventSink` -> the hub's progress routes + `PluginObjectiveMatcher` | bingo/scavenger/boss-night auto ticks |
| Points ledger | `points/*` | per-clan point rules (T-061) |
| Member profile Plugin tab | website `src/app/profile` (`PluginPanel`, `pluginActions.ts`) | tokens, consent, delete my data |
| Dashboard coverage page | website `src/app/dashboard/[guildId]/plugin` | clan counts only |
| Website proxy for plugin calls | website `src/lib/pluginProxy.ts` on the `api.` host | public face of the ingest |
| GE prices | T-104 (RS3 lookup: GE prices) | valuing drops server-side |

## 3. Identity: RuneLink, not a RuneSync token

- RuneSync declares RuneLink as a **required dependency** (T-117). With no link, RuneSync shows "Link your account with RuneLink first" and a button that calls `RuneLink:OpenLinkPanel()`; it sends nothing.
- Before each upload RuneSync calls `RuneLink:GetAssertion("RuneSync", cb)` (signed, 15 minutes, LINK-SYSTEM section 13.2) and caches it until 2 minutes before expiry.
- Upload header: `Authorization: RuneLink <assertion>` (the older `RSLink` scheme is still accepted). The ingest verifies the signature with the link key set, then asks the link service through its **internal route** (never its classes, LINK-SYSTEM section 2) who the first-party consumer RuneClan Bot should credit: `POST /internal/rsnlink/resolve` -> `{ discordId, rsn, verification }`. This route exists only for first-party consumers on the internal port; other plugins keep seeing an alias only.
- The RSN in the batch must equal the link's RSN (after `normaliseRsn`), otherwise the batch is refused like today's "not your name" case.
- **Manual tokens stay** (`X-Plugin-Token`, profile "Create token") for the simulator (`tools/plugin_simulator.py`) and for players who do not want RuneLink. Default: the profile hides token creation behind an "Advanced" disclosure once RuneLink is live.
- A RuneLink link counts as "this Discord owns this RSN" for ingest only. Whether it also creates or verifies a RuneClan Bot `player_link` is T-121's call (clan adoption), not this design's.

## 4. What RuneSync reports (first data, per the ticket)

All events use schema v1 (`PluginPayload`). New types are additive: an old server rejects an unknown type per event, never the batch.

| Data | Category (consent) | Event type | Client source (own player only) | Payload `data` | Notes |
|---|---|---|---|---|---|
| Skill XP | **XP (new)** "Skill XP" | `XP` (new) | `SkillTransmit` | `{ skill, xp, level }` (totals, not deltas) | Sent at most once per skill per 60 s batch window (latest value wins). Server stores the client's raw value and divides by `PLUGIN_XP_SCALE` (default 10, see 13.D4). |
| Loot | DROPS | `DROP` | `ObjStack` added with `isOwner` (own ground loot); loot-window items via inventory change while the loot interface is open | `{ itemId, itemName, quantity }` | Source = NPC that died within 3 ticks on the stack's tile or adjacent, else `OTHER`. No `value` from the client: the server prices with T-104 GE prices. |
| Boss kill | KILLS | `KILL` | Game message "Your <boss> kill count is: N." (chatAccess, game messages only) | `{ bossName, killCount }` | Message-based so attribution is certain; NPC death events alone are not trusted (attribution to "me" is untested). |
| Clue completion | ACHIEVEMENTS | `CLUE` (new) | Game message on casket opening "You have completed N <tier> Treasure Trails." | `{ tier, count }` | Casket loot then arrives as DROP with source `{kind: ACTIVITY, name: "<tier> clue"}`. |
| Presence | PRESENCE | `PRESENCE` | login / logout | `{ state }` | **Not sent in v1** (default off and no feature uses it yet). |
| Level-up, quest, collection log | ACHIEVEMENTS | existing types | later | | T-061 maps them to objectives/points; RuneSync sends them in a later version. |

**Chat use is narrow.** RuneSync asks for `chatAccess` only to read the player's own **game/engine** messages above. It never sends chat text, never reads clan, friends or public chat types, and only the extracted fields (`bossName`, `killCount`, `tier`, `count`) leave the client. If a member refuses chatAccess, kills and clues simply do not report; XP and loot still work.

**Event ids:** `rs1:<trackingIdHash8 or "anon">:<unixMillis>:<counter>`, unique per client, 8-64 chars as the ingest requires. `trackingAccess` is optional; it only makes ids stable across reinstalls.

## 5. Sending

- Queue in memory; every 60 s (from the `ServerLogic` subscription, never from a callback chain) send up to 100 events.
- On failure: keep the batch, back off 1, 2, 5, 10 minutes; on `401`/`403` stop sending and show "Relink RuneLink" in the panel.
- Persist the unsent queue in `PersistentDB` on `PluginShutdown()` (cap 500 events, oldest dropped first) so a restart does not lose a kill.
- The server's per-event `rejected` reasons are kept for the diagnostics panel (last 20 batches).
- No send when the member has switched every category off (the client asks `GET /v1/runesync/config` on login, then every 10 minutes).

## 6. Consent and settings (website first)

| Setting | Where it is changed | Where it is shown | Default |
|---|---|---|---|
| Categories (XP, Drops, Boss kills, Achievements incl. clues, Presence) | **Website profile -> Plugin tab** (primary) and the RuneSync settings panel in game (mirror; writes the same server record) | both | all **off** |
| Delete everything RuneSync sent | profile Plugin tab (exists) | | |
| Pause sending | game panel only (a local switch; no server data) | game panel | off (not paused) |
| In-game clan panel | game panel | game panel | **off** |
| Clan: which plugin events feed events and points | **Dashboard -> Clan plugin** page (T-061 owns the rules) | dashboard | off per type until staff turn it on |
| Clan: coverage counts | dashboard Clan plugin page (exists, counts only, nobody named) | dashboard | on (it is counts) |

The server is the source of truth for consent (`plugin_consent`); the plugin only reads it to avoid collecting what would be refused anyway. New categories `XP` needs a migration-free enum addition (consent rows are text) and a label on the profile.

## 7. Server additions (bot)

- `POST /internal/plugin/ingest` accepts `Authorization: RuneLink <assertion>` (or the older `RSLink`) beside `X-Plugin-Token` (T-200).
- `PluginCategory.XP`, `PluginEventType.XP` and `CLUE` with validation; XP scale; DROP valuation from GE prices at store time (stored as `valueGp` in the event's data copy, nullable when unknown) (T-201).
- `GET /internal/plugin/runesync/config` -> `{ consent: [...], panelAvailable: bool, minClientVersion: "0.1.0", sendEverySeconds: 60 }` and `POST` (or `PUT`) `.../consent` (same rules as the profile action; the game client can only send GET and POST) (T-202).
- `POST /internal/plugin/runesync/diagnostics` -> stores a redacted snapshot for 14 days, returns a short id like the bot's dev snapshot (T-093) (T-202).
  - Built (T-202): `minClientVersion` comes from `RUNESYNC_MIN_CLIENT_VERSION` (unset = `0.0.0`, nobody refused). The config call may add `?client=<name version>&paused=true|false`, which only feeds the profile's device card. Snapshots: 16 KB max, 10 per member per day, fields named like token, assertion, tracking id, chat text/message, secret, password, authorization, cookie or discord dropped at any depth (permission flags like `chatAccess` stay). Owner view: `GET /internal/admin/runesync/diagnostics/{id}` (RuneClan Admin at runeclans.com/admin -> RuneSync diagnostics). Member view: `GET /internal/plugin/me/runesync`.
- `GET /internal/plugin/runesync/panel` -> the member's own clan's upcoming and running events, their own points and their own event progress (T-206). Data flows **into** the client only; this does not break the own-data rule.
  - Built (T-206): one entry per server the member shares with RuneClan Bot whose **Clan plugin** page has "Allow the in-game clan panel" on (`plugin_guild_settings.clan_panel`, default off; admin route `GET/PUT plugin-settings`). Events: SIGNUPS and RUNNING, 10 per clan, with `joined`. Points: the member's own balance when the clan uses points. Progress: for running events the member is in, their (or their team's) points, objectives done of total, bingo tiles done. Cached 60 s per member; `panelAvailable` in the config is true when at least one clan allows it.
  - Added (T-142, T-143; PLUGIN-ERA-FEATURES.md sections 2 and 3, Drake's Q2/Q3 defaults): a bingo carries its board, `bingo.board = { size, cells: [{ label, done, locked }] }` in a solo event and `bingo.teamBoard` (same shape) in a team event, where the team's board is the one played. A team event carries `teamStatus = { name, members: [{ name, inGame }] }`: the member's own team only, names as the server shows them, and `inGame` (a RuneSync check-in in the last 11 minutes) only for teammates who switched on the **Presence** category; `null` for the rest ("not shared"). No location, combat stats or gear. RuneSync 0.0.8 draws both (tap-to-read tile grid, team lines).
- All behind the website proxy on the `api.` host, which keeps its own per-IP limits.

## 8. Website views (every data type has one)

| Data / control | View |
|---|---|
| XP reported | profile Plugin tab: "Last XP sync" per skill (time + value); clan site profile already shows XP from RuneMetrics, plugin XP is used for event/competition tracking only in v1 |
| Drops | profile Plugin tab: last 50 own drops (item, source, value, time); clan drops page keeps its RuneMetrics feed, plugin drops are added there only when T-061 says so |
| Kills, clues | profile Plugin tab: last 50 |
| Device / client | profile Plugin tab: client version, last seen, last batch result, "Paused" flag |
| Diagnostics id | profile Plugin tab shows ids the member created; the owner can open any id (redacted) in RuneClan Admin |
| Clan settings | dashboard Clan plugin page (T-061 adds the rules section) |

## 9. In-game UI

- **RuneSync panel** (settings icon, `hasSettingsIcon`): link status (from RuneLink), category toggles (mirror), pause, "Copy diagnostics id", "Open website settings" (shows the URL; the client cannot open a browser).
- **Clan panel** (opt-in, off): upcoming events with start times in local time, own points, own progress in a running bingo. Read-only. Built with plain components first; PrettyUI only if Drake approves the dependency (13.D9).
- No overhead titles, party window or "has plugin" markers in v1 (T-062, later).

## 10. Dev snapshot (bug reports)

"Copy diagnostics id" posts `{ clientVersion, rsLinkApiVersion, settings, queueLength, last20Batches: [{at, accepted, duplicates, rejected:[reason...]}], permissions: {http, chat, tracking} }` to the diagnostics route and shows the id (and prints it to the console). Never included: assertion, RuneLink token, tracking id, chat text. The board's bug form already has a "dev snapshot id" field; this id goes there.

## 11. Install, update, manifest

- `plugin.json`: name `RuneSync`, version `0.1.0` for the first submission, `requiredDependencies: { "RuneLink": "RuneLink" }`, `optionalPermissions: ["httpAccess", "chatAccess", "trackingAccess"]` (http is required in practice; the panel explains each), `urls: { "runeclans": "<API_BASE>" }`, `hasSettingsIcon: true`, contact email from 13.D6.
- Install from mod.io. Updates through mod.io; each release passes Jagex moderation. The server sends `minClientVersion`; an older client shows "Update RuneSync" and stops sending (never breaks the game).
- `<API_BASE>` is `https://runeclans.com`: the website serves both plugins' API (`/api/ingest/v1/...` for RuneSync, `/api/link/v1/...` for RuneLink) and passes it on to the RuneClans hub when its `HUB_BASE_URL` is set, otherwise to the bot. rsyounglings.com no longer serves it.

## 12. Privacy notice (draft for the submission, to publish at `/runesync/privacy` on the RuneClan Bot site)

> **RuneSync privacy notice (draft)**
> RuneSync is a free plugin that sends some of **your own** RuneScape activity to RuneClan Bot, a Discord bot your clan uses, so your clan's events and points can count it automatically.
> **What is sent, only if you switch it on:** your skill XP totals; items you receive and the monster or activity they came from; your boss kill counts; your clue scroll completions. With each upload: your RuneScape name and a signed proof from the RuneLink plugin that this name is linked to your Discord account. Nothing about other players is ever sent. Chat text is never sent.
> **What is not sent:** passwords, chat, other players, your location, your bank.
> **Where it goes:** RuneClan Bot's servers, hosted by Railway in the United States (US West). The United States is not a UK-adequate country; your data is protected by encrypted connections (HTTPS), access limited to the bot's operator, and automatic deletion.
> **How long:** raw reports are deleted after 180 days. Points and event results that came from them stay with the clan's event history.
> **Your rights:** see and delete everything RuneSync sent on your RuneClan Bot profile (Plugin tab), switch any category off at any time, or remove the plugin. Contact: <contact email, 13.D6>.

The Jagex submission form answers (permissions, Case 3 details) are filled from this notice in T-205; Drake submits.

## 13. Decisions made for Drake

| # | Question | Default chosen | Why |
|---|---|---|---|
| D1 | How does RuneSync prove who the player is? | RuneLink signed assertion on each batch; manual tokens kept for the simulator and opt-outs | No secret in public plugin code; one link for every plugin |
| D2 | First data | XP totals, own loot, boss kill counts, clue completions (ticket default) | Feeds bingo, boss nights and XP competitions straight away |
| D3 | Kill attribution | Only the game's "kill count is" message, not NPC death events | Certain attribution; NPC deaths near you might be someone else's |
| D4 | XP scale | Server divides the client's value by `PLUGIN_XP_SCALE`, default 10; Drake confirms in the beta | Observed Fletching 105,735,700 at level 110 |
| D5 | Drop values | Priced on the server from T-104 GE prices | No GPL dependency in the plugin, one price source |
| D6 | Contact email | A new address Drake creates (not the mod.io login); placeholder until then | Jagex requires a public one |
| D7 | Hosting / data protection | Stay on Railway US West and use Jagex Case 3 (extra form) | No move needed now; moving ingest to EU later makes it Case 2 |
| D8 | chatAccess | Asked for, optional; game messages only, fields only | Needed for kill counts and clues; refusing it only loses those |
| D9 | UI library | Plain components; PrettyUI only with Drake's ok | Fewer dependencies to review |
| D10 | In-game clan panel | Built, **off** by default | Optional-off rule |
| D11 | Presence | Category stays, not sent in v1 | No feature uses it yet |
| D12 | Batch timing | Every 60 s, 100 events max, queue 500 persisted on shutdown | Cheap on the server; little loss on crash |
| D13 | Retention | Keep the existing 180 days | Already built and documented |
| D14 | Base URL | `https://runeclans.com` for both RuneSync and RuneLink (changed from `api.rsyounglings.com`) | One site serves both plugins; rsyounglings.com is a normal clan site |
| D15 | Version for first submission | 0.1.0 | Numbers and dots only |

## 14. Drake's actions (cannot be done for him)

1. Test every RuneSync build in the beta client (`prf`, `pon`, `prl runesync`) and review every Lua line before submission.
2. Confirm the XP scale (D4) by comparing a skill's XP in game with the value logged.
3. Create the public contact email (D6).
4. Submit on mod.io, accept the EULA, fill the Case 3 form (D7).
5. Approve PrettyUI if wanted (D9).

## 15. Build tickets

| Ticket | Repo | What | Depends on |
|---|---|---|---|
| T-200 | bot | Ingest accepts RuneLink assertions | T-113 |
| T-201 | bot + website | XP and CLUE types, XP category, server-side drop pricing, profile labels | none |
| T-202 | bot + website | RuneSync config/consent/diagnostics routes + profile Plugin tab additions | T-201 |
| T-203 | RuneSync | Collectors, queue and sending | T-117, T-201, T-200 |
| T-204 | RuneSync | Settings panel + diagnostics button | T-203, T-202 |
| T-205 | website + RuneSync docs | Privacy notice page + submission pack | T-201 |
| T-206 | bot + website | In-game panel feed route (off by default) | T-202 |
| T-207 | RuneSync | In-game clan panel (off by default) | T-204, T-206 |

T-061 (more types + per-clan points rules) and T-062 (plugin-era features) were blocked on this design; they can now follow T-201.
