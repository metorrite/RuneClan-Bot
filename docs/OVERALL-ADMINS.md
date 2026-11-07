# Overall RuneClans admins and site admin access (N5)

Drake's decisions of 2026-10-24: an overall admin panel at runeclans.com/admin, overall admins who may open every clan site, and site owners who
choose who else may open their own site's admin panel. This is the bot's side; the website's is in the runeclans repository
(`packages/dashboard/src/lib/overallAdminLogic.ts`, `packages/api-client/src/serverFactsLogic.ts`).

## 1. Who is an overall admin

The list lives on the RuneClans hub since T-981 (RuneClan-API `admin/RuneClanAdminsModule`); the website shows and changes it there
(RuneClan Admin > Admins), and the bot only reads it (`GET /v1/admins` with its first-party key). The old `younglings.overall_admin` table is
no longer read or written; it stays until the move's purge step.

- **Built in:** the hub's `HUB_OWNER_DISCORD_IDS`, plus the bot's owners as `BotOwners` decides (the Discord application's owner or team, plus
  `OWNER_IDS`). No Discord id is written in the code. A built-in admin can't be added or removed through the panel.
- **Added:** people a built-in admin added by Discord user id on the website (the hub's `runeclan_admin`). Only a built-in admin may add or
  remove them.
- `OverallAdmins.isOverallAdmin(jda, userId)` is the one check. The hub's list is cached 30 seconds, then the last known list answers while a
  fresh one is read in the background; a failed read keeps the last known list (it never adds anyone). With the hub client off, only the bot's
  owners are admins.

## 2. What an overall admin may do

- Use the whole panel: every clan site (`SiteOwnerAdminApi`'s routes), the beta testers, the premium access list. Only a built-in admin changes
  the overall admins list.
- Be the **owner of every clan site** (`SiteRoles.resolve`: `Facts.overallAdmin` gives `OWNER`), in the site's server or not, bot or no bot: open
  its editor and its site admin, change its settings and its permissions, including who may open its site admin.

The bot checks the hub's list (cached) on every request; the website sends nothing new for this. The website's `X-Actor-Id` (from its verified session)
names the person, as for every admin route.

## 3. The panel's routes (`/internal/admin/overall/...`)

They name no server: `InternalApiServer` sends them to `OverallAdminApi` before any server is looked up, so an overall admin needs no place in
the bot's home server. Every route but `whoami` needs an overall admin; writes are rate limited and audit-logged in the home server's log.

| Route | Who | Answer |
|---|---|---|
| `GET overall/whoami` | anyone | `{overallAdmin, builtIn}` (always 200) |
| `GET overall/premium` | overall admin | `{grants: [{id, kind, targetId, startsAt, endsAt, note, grantedBy, grantedAt, active}], enforced: false}` |
| `POST overall/premium` `{kind: "site"\|"user", targetId, startsAt?, endsAt?, note?}` | overall admin | the list (dates `YYYY-MM-DD`, UTC) |
| `DELETE overall/premium/{id}` | overall admin | the list |
| `overall/owner-sites...` | overall admin | `SiteOwnerAdminApi` (the T-423 console's routes) |
| `overall/beta-testers...` | overall admin | `SiteBuilderBetaAdminApi`'s `beta-testers` routes |

The old `owner-sites` and `beta-testers` routes in the home server stay as they were (the bot's owner only).

**Premium access is structure only.** `younglings.premium_access_grant` is a separate table that nothing reads; `Premium` still reads only
`premium_guild`, and premium stays off (`PREMIUM_ENABLED`). Reading these grants when premium is switched on is a deliberate later change.

## 4. A site's own admin access

Stored with the site's roles (`clan_site_roles`: `admin_user_ids`, `admin_role_ids`, `staff_server_id`, `staff_server_permission`), as
`SiteRoles.AdminAccess`. Each one makes a person an `ADMIN` of that site (the OWNER/ADMIN/EDITOR model is unchanged, only extended):

- **By Discord user id**, in the server or not (on a bot-less site's public pages too).
- **By Discord role**: holders of one of the roles, where the bot is in the server (a bot-less site can't see roles and refuses them).
- **The staff-server rule:** members of `staff_server_id` whose permissions there include `staff_server_permission` (Administrator always
  counts), from the website's signed staff-server facts (section 5). Permissions offered: `administrator`, `manage_server`, `manage_roles`,
  `manage_channels`, `ban_members`, `kick_members`, `manage_messages`, `moderate_members` (`ClanSiteAdminApi.STAFF_PERMISSIONS`).

Routes: `GET clan-site/roles` now carries `adminAccess: {userIds, roleIds, staffServerId, staffPermission}` and `you.overallAdmin`;
`PUT clan-site/roles/admin-access` `{userIds?, roleIds?, staffServerId?, staffPermission?}` (a key left out keeps what is stored) needs the site's
**owner** (the server owner, whoever claimed the site, or an overall admin). Answers the roles.

**People who aren't in the server, or who have no dashboard access, where the bot IS in the server:** `TicketAdminApi` asks
`SiteAccessRoutes` before its 403. When the site lets the person in, only the site's own paths open: `clan-site...` (each route checks its own
role), `site-pictures...` (editors), `GET structure` and `GET audit` (site admins; the audit is always filtered to `clan-site`). Every write
is rate limited and audit-logged ("A RuneClan Admin" or "A Site Admin" when the person isn't a member). Editors without dashboard access now come
in this way too, with the same rights as before.

**Bot-less sites:** overall admins and people let in by the site's admin access come in without being on the beta list themselves (the site's
owner was).

**The public viewer** (`/internal/directory/sites/{slug}/viewer`) answers `owner` for an overall admin and `admin` for the site's admin access,
so the website opens the site admin for them; it reads the staff-server facts from the same call. The cached answer follows a change within a
minute (an admin list change reaches the bot within about 30 seconds more).

## 5. Signed staff-server facts (the contract change)

A new pair of headers, next to `X-RC-Actor` (which is unchanged):

- `X-RC-Servers`: base64url (no padding) of compact JSON
  `{"v":1,"userId":"<id>","servers":{"<server id>":"<permission bits, decimal>",...},"iat":<unix s>,"exp":<unix s>}`. Only servers where the
  person owns the server or holds one of the staff permissions above (an owner gets them all), at most 100 from the website (the bot reads 200).
- `X-RC-Servers-Sig`: lowercase hex HMAC-SHA256 over the exact `X-RC-Servers` string, keyed with the internal secret.
- Checked (`ServerFacts`): the signature (constant time), `v == 1`, `iat <= now + 60`, `exp > now`, `exp - iat <= 300`, and `userId` is the
  person the request is for. Anything off is simply no staff servers (they only add access).

Unlike `X-RC-Actor` these aren't about the server a call names, so the website sends them on every call that names a server and on the site
viewer call. Both sides pin the same fixed vector (`ServerFactsTest`, `packages/api-client/tests/serverFacts.test.mjs`).

## 6. What the clan sees, and the bot owner's override (T-445)

- **The clan sees RuneClan Admin edits.** A change to a site by someone who owns it only as an overall admin (`SiteRoles.onlyAsOverallAdmin`)
  is written to the server's audit log as `<name> (RuneClan Admin)` (or `A RuneClan Admin` when they aren't a member, and on bot-less sites),
  so the site's own history shows it. `you.asRuneClanAdmin` on `GET clan-site`, `clan-site/layout` and `clan-site/roles` tells the editor and
  the Site Admin to say so.
- **Restrictions stay on by default.** Like every overall admin, the bot owner reaches only the site, layout and site-role routes of a server
  (section 3) unless they lift the restrictions.
- **Lifting the restrictions** (bot owner only, `BotOwners`): `owner-override` on the guild-scoped admin API.
  - `GET` the state, `POST {"acknowledged": true, "minutes": n}` lifts it, `DELETE` ends it now. All three answer
    `{active, until (ISO or null), minutes}`; anyone else gets 403, and a `POST` without the acknowledgement 400.
  - `minutes` is the Switches panel's number `owner-override-minutes` (RuneClan Admin > Switches, default 30), which the website reads and
    sends; the bot keeps it within 5 to 240 and uses 30 when it is left out.
  - While it runs, `DashboardAccess.tierOf` answers ADMIN for that person in that one server, so
    `whoami` says `allowed` and the whole dashboard opens. A bot owner who isn't a member gets a stand-in member (`OwnerStandIn`): their id
    and name, no roles and no Discord permissions. Their own Discord rights play no part; routes that act in Discord use the bot's rights,
    as they do for any Admin.
  - The lift, the end and every change are written to the server's audit log, changes as `<name> (bot owner, restrictions lifted)`.
  - `whoami` gives the bot owner `ownerOverride: {active, until, minutes}` so the dashboard shows a banner while it runs.
  - Kept in memory: a restart ends every override. It holds only in the bot process that took the lift (one today); several API replicas would need it in the database.
  - Someone removed as a bot owner keeps a running override until the owner list is next looked up (at most about ten minutes).
