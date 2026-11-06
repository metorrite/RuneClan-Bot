# Events hub: open clan and community events (design)

Ticket T-006. Status: **draft with defaults** (Drake away, 2026-10-20). Builds behind a flag, **off by default**. Related designs: `docs/EVENTS-CROSS-SERVER.md` (T-059, alliance co-hosting and the fill queue) and `docs/EVENTS-CLAN-VS-CLAN.md` (T-058). Background: `docs/EVENT-LIFECYCLE.md`.

## 0. In one paragraph

Any server that uses RuneClan Bot (a clan server or a community server with no clan) can mark an event made with the event manager as **Open**. Open events appear on the events hub, a section of the existing clans hub site, where anyone signed in with Discord can find them, read the rules and press Join, whether or not they are in a clan. Joining still means joining the host's Discord first (Drake's rule D10), so roles, channels and `/submit` keep working exactly as today. Nothing is listed unless the host chooses Open, and the whole hub stays behind its beta gate and a new `EVENTS_HUB` flag.

## 1. What exists

- Event engine with `EventRules.Scope { PRIVATE, ALLIANCE, PUBLIC }` stored on every event (the old dashboard route `EventsAdminApi`, removed in T-942, defaulted to PRIVATE) but **not used for any listing yet**.
- `EventKinds` / `TeamRules.Kind { HOME_CLAN, HOME_OTHER, PARTNER }` and `QueueRules.Policy { FIRST_COME, CLAN_FIRST }`: who may join and in what order.
- Website member routes `events/join|leave` (`MemberEventsApi`): refuse non-members of the host server and show the clan profile's apply link (D10).
- Clans hub (`src/app/hub`: directory, `c/[slug]`, `events`, `leaderboards`), beta gate `HUB_PUBLIC`, own navbar, host `clans.<apex>`. Its `events` page today lists **Discord scheduled events** shared through `shared_event` (PUBLIC/ALLIANCE), not engine events.
- Clan directory reports (`clan_report`, owner review) for takedowns.

## 2. Who may host

| Host | Allowed | Notes |
|---|---|---|
| A clan server using RuneClan Bot | yes | the usual case |
| A community server using RuneClan Bot with no clan set up | yes | the clan-only options (clan-first queue, HOME_CLAN mixing) are hidden |
| A single person with no server | **no** | roles, channels, staff review and moderation all need a server; an individual can make a small server |

Only people who can use the event manager in that server (today's dashboard access, plus the events coordinator tier T-060) can make an event Open.

## 3. Visibility and joining

The existing `scope` becomes the visibility, renamed in the UI:

| Scope (stored) | UI name | Listed where | Who may join |
|---|---|---|---|
| PRIVATE (default) | Only this server | nowhere outside the server | members of the host server (today) |
| ALLIANCE | This server and its alliances | hub, only for signed-in members of an allied server; the allied servers' dashboards (T-059) | host server members; allied servers' players per T-059 |
| PUBLIC | Open to everyone | events hub for everyone who passes the beta gate | anyone with a Discord account who joins the host server |

- **Joining an Open event from the hub:** signed in with Discord -> Join. If the person is in the host server they join at once (same `EventService.join`, so team policy, queue, deadline and bans apply). If not, the hub shows the host's **event invite link** and a "I've joined, try again" button. No automatic joining of servers (no `guilds.join` scope).
- **Event invite link:** a new per-server setting `events.inviteUrl` (dashboard Events defaults card, T-208 area). Falls back to the clan profile's apply link, then to "ask the organiser". Validated as a `discord.gg/...` or `discord.com/invite/...` link.
- **No RSN needed** to join an Open event unless its template needs one (competition, boss night and auto-ticked bingos need a linked RSN to track; the join page says so and links `/profile`). Template flag `needsRsn`.
- **Mixing rule for Open events:** default team policy for PUBLIC events is "everyone mixes" (`TeamRules` all kinds, one mixing group); the host can change it as today.

## 4. What the hub shows (privacy)

Listing card: event name, template, host server name and icon (from the clan profile when there is one, else the server's own name/icon), start/end in the viewer's time, signup state, player count and limit, a short description (first 300 characters), "needs RSN" badge.

Event page `/e/<id>` on the hub host: the above plus rules/description, prizes (as text), the board picture for bingos, and Join. **Never** participant names or Discord ids. Standings appear only if the event's standings visibility (T-211) is "everyone in the server" **and** a new switch "show standings on the events hub" (`hub.publicStandings`, off) is on; then names are RSNs only (players without an RSN are shown as "Player 12").

Once joined, the player uses the existing player page (`/clan-events/<id>`, slice 7) for their team, channels and submissions; the hub links to it.

## 5. Moderation

- Every Open event page has **Report** (reason required, rate-limited like `clan_report`, no sign-in needed but signed-in reporter recorded).
- Reports go to the bot owner's **Directory moderation** page on the bot dashboard (`/dashboard/<server>/listings`, T-089) beside clan reports: open the event, **Unlist** (sets a takedown flag; the host sees "Removed from the events hub by RuneClan Bot staff: <reason>" on the event and cannot re-list until the owner clears it) or dismiss.
- A server can be barred from listing events (owner action) after repeated takedowns.
- Hosts agree to a one-line rule when first choosing Open: "Open events are public. Don't post anything that breaks Discord's or Jagex's rules; RuneClan Bot staff may unlist an event."

## 6. Where it lives

- **No new subdomain now.** The events hub is the `/events` section of the clans hub (`clans.<apex>/events`), which gains an "Events" tab with filters: Open, Alliance (when signed in and allied), My servers. Discord scheduled events from `shared_event` stay listed under a "Discord events" filter.
- Routes are host-aware so `events.<apex>` can become an alias later (T-063 domain config); that is configuration, not code.
- Flag `EVENTS_HUB` (website env, default **off**): off = the hub's events page stays as it is today and the dashboard hides the Open choice. The hub's own beta gate `HUB_PUBLIC` still applies on top.

## 7. Dashboard and Discord (website first)

| Setting / action | Dashboard | Discord |
|---|---|---|
| Visibility (Only this server / Alliances / Open) | Event manager Overview, "Who can see and join" card; also in Guided setup (EventOptions `scope`) | `/events create` stays private; no Discord command to open an event in v1 (listing a public page is a website job) |
| Event invite link | Events defaults card (server-wide) | none |
| Show standings on the hub | "Who can see and join" card | none |
| Takedown notice | shown on the event Overview | none |
| Reports / unlist | Directory moderation (bot dashboard, owner) | none |

## 8. Decisions made for Drake

| # | Question | Default |
|---|---|---|
| H1 | Who may host | Any server using RuneClan Bot (clan or community); no individuals |
| H2 | Reuse `scope` or a new field | Reuse PRIVATE / ALLIANCE / PUBLIC, renamed in the UI |
| H3 | Must players join the host Discord | Yes (D10); hub shows the invite; no automatic server join |
| H4 | Separate events subdomain | Not now: a section of the clans hub, host-aware for a later `events.` alias |
| H5 | RSN needed to join | Only when the template tracks game data |
| H6 | What the public sees | No participant names; standings only with an extra switch, RSNs only |
| H7 | Moderation | Report -> owner's Directory moderation page -> unlist; repeat offenders barred from listing |
| H8 | Flag | `EVENTS_HUB` off by default, under the existing `HUB_PUBLIC` beta gate |
| H9 | Default mixing for Open events | Everyone mixes |
| H10 | Discord command to open an event | None in v1 |

## 9. Build tickets

| Ticket | What | Depends on |
|---|---|---|
| T-216 | Bot: listing feed of PUBLIC/ALLIANCE engine events, `events.inviteUrl`, `hub.publicStandings`, `needsRsn`; dashboard "Who can see and join" card | none |
| T-217 | Website: hub events tab, `/e/<id>` page, Join with invite fallback, behind `EVENTS_HUB` | T-216 |
| T-218 | Bot + website: event reports, unlist/takedown, barred servers on the bot dashboard's Directory moderation page | T-216 |
