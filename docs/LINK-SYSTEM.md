# RSN link system: design and protocol spec

Ticket T-112. Status: **draft; Drake's decisions of 2026-10-19 applied (section 18)**.

> **Superseded in part (T-276, 2026-10-28).** Links live in the RuneClans hub, not in this bot (T-162). The RuneLink pages are only on runeclans.com (`/plugins/link`); the separate link host face (`LINK_HOST`) is gone, and the website forwards the plugin API (`/api/link/v1/*`) and the pages' calls to the hub's `/v1/link/*` and `/web/link/*`, not to the bot's `/internal/rsnlink/...`. Where this document describes the link host face or the website forwarding to the bot, read it as history. The hub's copy of this document (RuneClan-API `docs/LINK-SYSTEM.md`) and its code are current. Build tickets T-113..T-118 follow this document; whoever finds a gap here asks rather than inventing.

## 0. In one paragraph

A standalone RS3 plugin named **RuneLink** (Drake's pick, section 15) lets a player link the character they are logged into to their Discord account. The plugin shows a short code and a short web address; the player opens the address on any device, signs in with Discord, types the code and confirms "Link RuneScape name X to this Discord". The plugin then holds a private random token and never shows it to anyone. Other plugins depend on it and can ask only two things: "is this player linked?" and "what is this player's alias for my plugin?" (a stable random-looking id that is different for every plugin). For players who prefer it, a fallback has RuneClan Bot DM them a code instead, which only works when RuneClan Bot can reach them on Discord. Trust is implicit for now, with a `verification` field and a hook so a real check can be added later. The service has its own database, its own keys and a neutral, unbranded web face whose address is configuration, so the final domain can be chosen before the Jagex submission.

## 1. Fixed decisions (from Drake's Q&A, 2026-10-19; not re-opened here)

- Generic: any plugin can depend on it. RuneClan Bot and RuneSync are its first users, nothing more.
- Primary pairing: Discord sign-in, device-code style. Fallback: a DM code, only when the bot can DM the player.
- Codes expire (about 10 minutes), wrong attempts lock out, cooldowns per account and per IP. No server membership or slash command needed.
- Sign-in page: neutral and unbranded (same visual theme is fine; no clan name, no ads, no "personal gain" look). Base URL is configuration; final domain decided before submission.
- Dependents get "is linked" plus a per-plugin alias only. Raw lookups (RSN to Discord and back) are not in v1.
- Trust: implicit for now; no staff verification ever; verification field and hook for later (T-122).
- Plugin: MIT, public repo (starts private until Drake says). The server stays private.
- Database: dedicated, local dev database for now; hosting region decided at release (T-120).

## 2. The pieces

```
  RS3 client (player's PC)                     Neutral link host (website app, own host face)          RuneClan Bot process
 +---------------------------+   HTTPS         +--------------------------------------+   internal   +------------------------------+
 | RuneLink plugin (Lua)      | --------------> | /v1/*   plugin API (proxy + limits)  | -----------> | rsnlink service (Java)       |
 |  - pairing panel          |                 | /       code entry + Discord sign-in |  X-Internal- |  - own DataSource            |
 |  - token in PersistentDB  |                 | /links  my links, unlink, delete     |   Secret     |  - own schema `rsnlink`      |
 |  - library for dependents |                 +--------------------------------------+              |  - DM fallback via JDA       |
 +---------------------------+                         ^ browser (any device)                         +--------------+---------------+
        ^ functions only (no token)                    |                                                             |
 +---------------------------+                  Discord OAuth2 (identify)                              Link database (separate DB)
 | Dependent plugin          |                                                                       local: `jonnybot_link` on dev Postgres
 +---------------------------+
```

- **RuneLink plugin** (new repo, T-116): Lua 5.4. Shows the pairing panel, talks to the link host, stores the token, exports a small library.
- **Link host face** (website, T-114): a separate host face of the existing Next.js app, chosen by hostname like the `api.`/`clans.`/`jonnybot.` faces in `src/proxy.ts`. It serves the plugin API (thin proxy with its own per-IP limits, the same pattern as `src/lib/pluginProxy.ts`) and the sign-in pages. It shares no cookies, sessions or nav with the clan site or the dashboard.
- **rsnlink service** (bot, T-113): a new package `com.runeclans.bot.rsnlink` inside the RuneClan Bot process for now (Drake, 2026-10-19), exposed only on the internal port behind `X-Internal-Secret`. It becomes its own service when Drake is ready to transition, so the module boundary is kept clean enough that the split is a move, not a rewrite:
  - **its own DataSource** (`LINK_DATABASE_URL`), schema, keys and config; it never touches the clan schema `younglings`;
  - it imports nothing from other bot packages except a tiny shared HTTP/bootstrap layer, and other packages never import its internals; a unit test checks both directions;
  - what it needs from RuneClan Bot sits behind two interfaces: `LinkDmSender` (send a DM, resolve a username; JDA today, a small bot-side endpoint after the split) and `LinkHttpRoutes` (mounting its routes); the `/internal/rsnlink/*` contract, tables and keys move unchanged;
  - consumers inside RuneClan Bot (T-121) use only the same internal routes a separate service would expose, never its classes.
- **DM fallback** (bot, T-115): uses RuneClan Bot's gateway connection to send the code. This is the one place the RuneClan Bot name appears, because it is the bot that sends the DM.
- **Abuse view** (the website's RuneClan Admin console at runeclans.com/admin, plus bot `/internal/admin/rsnlink/*`, T-118).

Why the website proxies instead of the plugin calling the bot: the bot's port is not public, the website already owns TLS, hosts and per-IP limits, and one manifest base URL then covers both the API and the page the player is told to open.

## 3. Research answers

### 3.1 Jagex plugin guide (developer.runescape.com, cleared for AI reading)

Paraphrased from the guide pages named in brackets; no Jagex text is copied.

| Question | Answer | Design consequence |
|---|---|---|
| How does a dependency expose functions? [Plugin Structure] | `plugin.json` lists it in `requiredDependencies` / `optionalDependencies` (array = imported under its plugin name; object = `"VarName": "PluginName"` to rename). The dependency loads first; whatever its `main.lua` returns (a table of functions in the examples) is cloned and handed to the dependent as a variable named after it. Dependents call `RuneLink:IsLinked()`. Optional dependencies can be nil, so dependents check first. A global `PluginDependencyUpdate(pluginIdentifier, enabled)` is called when an optional dependency is turned on or off. Cycles are an error. Names that are not valid Lua identifiers get `_` substitutions. | The name "RuneLink" is a valid Lua identifier, so either form imports it as `RuneLink`; dependents call `RuneLink:...` and the README says so. (The earlier name "RS Link" had a space and needed the object form `{ "RSLink": "RS Link" }`.) T-116 checks in the beta client which string the import actually uses (the manifest `name` or the plugin folder id). Export **functions only**: data fields in a cloned table would be snapshots, while functions keep reading the plugin's live internal state. The guide also says exported tables may be shared across imports, so the library must not hold per-dependent state in the exported table. |
| Does a dependency's HTTP use its own manifest URLs? [Plugin Structure, Plugin, HTTP] | **Not documented.** `HTTPRequest.new(base, path)` takes a base from `Plugin.urls`, and only manifest base URLs are allowed, but nothing says whose manifest and permissions apply when a dependent calls a dependency function that makes a request. | Exported functions **never make HTTP calls themselves**. They read cached state or put a request on a queue; RuneLink's own event subscription (registered by RuneLink, so running as RuneLink) sends the queue and calls the dependent's callback afterwards. Dependents need no `httpAccess` and no URL for linking. Question goes to Jagex via T-119. |
| Can dependents read the dependency's PersistentDB? [Persistent DB] | Records are isolated per plugin; one plugin cannot read or change another's. Not said directly for dependents, but isolation implies no. Scope per character or per account is not documented, and so is when data is written to disk (examples set values on shutdown). | The token lives in RuneLink's PersistentDB, keyed per character inside (section 9.3) so it works whether storage is per account or per PC. Save straight after pairing, not only in `PluginShutdown` (shutdown does not run on a crash). |
| How to show a code and a text box? [Simple UI tutorial, Input Field, Text, Button] | Attach a GroupBox to `TOPLEVEL_V2__PLUGIN_BUILD_LAYER_TOP` with a stylesheet (invisible without one); `ui.Text` needs a font; `ui.Button` fires `ONBUTTONPRESSED`. `ui.InputField` fires `ONCONTENTCHANGED` with a cause (`SUBMIT` on Enter, Escape, focus loss), `Setup(visibility, filter, maxLength)` sets a filter (`ALPHANUMERIC`, `NUMERIC`, ...) and length. Masked fields (`HIDDEN`) cannot be read back. | Code box: `ALPHANUMERIC`, max 9, visible. Discord username-or-id box: filter `NONE` (usernames contain `.` and `_`), max 32, checked by the rules in 4.2. No documented clipboard or "open URL" function, so the player **types** the address and code: both must be short and unambiguous. |
| Character identity [Player] | `Player.GetTrackingID()` returns a 64-bit per-character value or nil (needs `trackingAccess`; arrives a few seconds after login). Stability across name changes is not documented. Display name comes from the player entity (`Player.GetEntity()`). | Tracking id is optional but strongly recommended; the service keys characters on it when present (RSN recycling, section 11). Plugin waits up to 10 s for it before pairing. Ask Jagex whether it survives name changes (T-119). |

Still unknown, for T-119: HTTP rate limits and timeouts; dependency HTTP context; tracking id stability; whether any Jagex-signed proof of the logged-in player is coming.

### 3.1a Settled by the full read of the guide (2026-10-21, T-129)

The whole guide has now been read (a local copy, kept outside this repository). Four results changed the plugins:

| Finding | Library page | What the plugins do |
|---|---|---|
| PersistentDB is shared by **every character played on the PC** (per plugin, and beta vs live), not per account | `docs/core-persistentdb.md` | RuneLink keeps one token per character with prefixed keys and moves older keys (9.3). RuneSync tags every queued event with its character and saves one queue per character. |
| Any plugin that depends on RuneLink can call **everything it exports**; exports are cloned per importer | `docs/guides.md` ("Depending on other plugins") | Exports are read-only and only touch a status copy and a request inbox (10). |
| Any plugin can read text off components on screen, except **restricted** ones | `docs/ui-core.md` ("Reading game interfaces", "Restricted components") | The pairing code label is restricted, so another plugin can't read the code and confirm it with its own Discord (4.1). |
| mod.io needs **Windows, Linux, Android and iOS**; phones have no keyboard, mouse or console | `docs/submission.md` ("Step 4"), `docs/core-input.md` | Linking by sign-in needs no typing in the game; the DM fallback (typing) sits behind a button on devices without a keyboard; RuneSync uses no keyboard, mouse or console (4.2, section 10). |

### 3.2 Discord

- **No device grant.** Discord OAuth2 supports the authorization code grant (with refresh), implicit and client credentials; there is no RFC 8628 device grant. So "device-style" is built on top: our own short user code typed on our page, plus a normal authorization code sign-in in the browser.
- **`identify` scope** returns the user's id, username, global name and avatar, without email. That is all we ask for. We use the access token once to read `/users/@me`, then **throw it away** (no refresh token stored).
- **State and PKCE:** Discord returns `state` and an `iss` value (`https://discord.com`) on the redirect; we require both to match. PKCE is only mentioned in passing in Discord's docs, so the server also uses the client secret (confidential client) and sends PKCE S256 as an extra.
- **Consent screen shows the OAuth application's name.** Decision (Q2): a **separate, neutral Discord application** eventually, **RuneClan Bot's application for now**. Client id, secret and redirect URL are configuration (`LINK_DISCORD_CLIENT_ID` / `LINK_DISCORD_CLIENT_SECRET` / `LINK_PUBLIC_BASE_URL`), so the switch is a config change plus registering the redirect URL on the new app; no code change. Discord does not allow "discord" inside usernames, so the separate app is simply named "RuneLink" (section 15).
- **DMs.** Discord's Create DM docs say DMs should generally follow a user action, must not be used to message a whole server, and that opening many DMs quickly gets a bot rate limited or blocked from opening new ones. The pages read do **not** state the mutual-server rule or what a user-installed app may do.
- **Usernames are unique.** Since Discord's username change, a username is unique and lowercase: letters, digits, `.` and `_` (Discord blog post on usernames); old `name#1234` tags keep working as aliases. The developer docs' user page still calls usernames "not unique" (wording from the discriminator days) and gives 2 to 32 characters, so the id stays the stored key and a username is only an input to resolve.
- **Resolving a username as a bot:** there is no bot API to look a user up by username across Discord. A bot can only find members of servers it is in (its member cache, or each server's member search). That loses nothing here, because the DM can only reach someone who shares a server with RuneClan Bot anyway (next bullet).
- **Honest limit for the DM fallback:** a bot can only DM a user it shares a server with, and only if that user allows DMs from members of that server (Discord's per-server privacy setting); otherwise the send fails with "Cannot send messages to this user". Whether a **user-installed** RuneClan Bot can DM the person who installed it is **not documented**; T-115 treats it as unsupported until Drake tests it with the dev bot (test step in T-115). So in practice the fallback reaches only players who are in a server with RuneClan Bot and have server DMs on, which is a small slice of all RS3 players. Sign-in is the path that always works.

## 4. Flows

### 4.1 Primary: sign in with Discord (device-style)

1. Player opens the RuneLink panel and presses **Link Discord**. Plugin reads the display name and (if granted) tracking id, then `POST /v1/pair/start`.
2. Service creates a pairing and returns `userCode` (shown) and `deviceSecret` (never shown). Panel shows:
   *"On your phone or PC go to **link.example.org** and enter **BHKD-7Q2M**. Expires in 10 minutes."* (host from config).
3. Plugin polls `POST /v1/pair/poll` every `interval` seconds with the device secret.
4. In the browser, the page asks for the code first (`POST /web/code`). Valid code: page says "Code accepted. Now sign in with Discord." and starts OAuth. The RSN is **not** shown before sign-in, so a guessed code reveals nothing.
5. After Discord sign-in, the **confirm screen** shows, large: *"Link RuneScape name **Zezima** to Discord **@someone**?"* with the warning *"Only continue if you are logged in to RuneScape as Zezima right now and this code is on your own screen. Never enter a code someone sent you."* Buttons: **Link** / **This isn't me**.
6. **Link**: the service creates or reuses the link (section 9.2 rules) and marks the pairing approved. **This isn't me**: pairing denied, abuse event recorded.
7. The next poll returns `linked` with the **token, once**. Pairing becomes `consumed`; polling again returns `expired_or_unknown`. Plugin saves the token at once.
8. If the plugin never collects the token (game closed), the link still exists; the player just pairs again and the same link is reused (same alias), with a new token.

### 4.2 Fallback: DM code

1. In the panel the player types their Discord **username or user id** (decision Q4) and presses **Send me a code**. `POST /v1/pair/dm`.
   - Input rules: all digits and 17 to 20 long = an id; otherwise a username: trimmed, a leading `@` dropped, lowercased, 2 to 32 of `a-z 0-9 . _`. Old `name#1234` tags are refused with "type your new username".
   - Username resolution (T-115): exact username match only (never display names or nicknames), first RuneClan Bot's member cache, then each shared server's member search, stopping at the first hit; a username that matches two different ids is treated as not found. It runs in the background after the reply, never while the request waits, so response timing leaks nothing.
2. The service always answers the same way (`202`, a pairing id and device secret), whether or not a DM could be sent, so the endpoint cannot be used to learn whether an id or username exists, shares a server with RuneClan Bot or accepts DMs (decision Q3). The panel says: *"If RuneClan Bot can reach you, a DM with a code arrives within a minute (check Message Requests). No DM? Use Sign in instead; it always works."*
3. RuneClan Bot DMs (neutral text, section 13.3) the code, the RSN that asked, and two buttons: **Not me** (denies the pairing) and **Stop these messages** (opts the Discord ID out of DM codes for 90 days).
4. Player types the code into the panel: `POST /v1/pair/dm/confirm`. Correct code returns the token once. Five wrong codes lock the pairing.

### 4.3 After pairing

- On each login (first `ServerLogic` tick with a display name, and tracking id if granted) the plugin calls `POST /v1/session` with the token: updates last-seen and reports a new display name if it changed (section 11).
- Dependents ask the library; the library answers from cache or queues a call (section 10).
- Unlink from the plugin: `POST /v1/unlink`, then the token is deleted from PersistentDB.

## 5. API

All JSON, UTF-8, `Cache-Control: no-store`. Public paths are on the link host (`<LINK_PUBLIC_BASE_URL>/v1/...`, also `/api/link/v1/...` on any host for local dev). The website forwards to the bot at `/internal/rsnlink/...` with `X-Internal-Secret`; the bot is the authority for every rule. Errors are `{ "error": "<code>", "message": "<plain words>", "retryAfter": <seconds, optional> }`.

### 5.1 Plugin routes

| Route | Auth | Body | Success |
|---|---|---|---|
| `POST /v1/pair/start` | none | `{ "rsn": "Zezima", "trackingId": "123..." or null, "client": { "name": "RuneLink", "version": "1.0.0" } }` | `201 { "pairingId", "deviceSecret", "userCode": "BHKD-7Q2M", "verifyUrl": "https://link.example.org", "expiresIn": 600, "interval": 5 }` |
| `POST /v1/pair/poll` | device secret in body | `{ "pairingId", "deviceSecret" }` | `200 { "status": "pending" }`, `{ "status": "slow_down", "interval": 10 }`, `{ "status": "denied" }`, `{ "status": "refused", "message" }` (the name is held by a tracked or verified link, section 11), `{ "status": "expired_or_unknown" }`, or `{ "status": "linked", "token": "lnk_example-token-not-real", "verification": "implicit", "disputed": false }` (token only on this one response; `disputed: true` means the link is held for review, section 11) |
| `POST /v1/pair/dm` | none | `{ "rsn", "trackingId", "discord": "someone" or "1234...", "client" }` | always `202 { "pairingId", "deviceSecret", "expiresIn": 600 }` |
| `POST /v1/pair/dm/confirm` | device secret | `{ "pairingId", "deviceSecret", "code" }` | `200 { "status": "linked", "token", "verification" }`; wrong code `400 { "error": "wrong_code", "attemptsLeft": 3 }`; `423 locked` |
| `POST /v1/session` | `Authorization: Bearer lnk_...` | `{ "rsn", "trackingId" }` | `200 { "linked": true, "disputed": false, "verification": "implicit", "discordName": "someone", "xpReports": false }`, `401 token_revoked`, or `409 character_mismatch` (the token belongs to another character). `xpReports: true` asks the plugin to send `/v1/xp` (section 12.1) |
| `POST /v1/xp` (T-170) | `Authorization: Bearer lnk_...` | `{ "rsn", "trackingId", "state": "game" \| "lobby" \| "logout", "xp": { "10": 130344315, ... } }`: this character's exact XP in tenths (as `SkillTransmit` gives it), keyed by skill id, for the skills that changed since the last report | `200 { "accepted": true, "xpReports": true, "verification": "implicit" }`; `{ "accepted": false, "xpReports": false }` when the check is off or the link no longer needs it (the plugin stops sending); `409 character_mismatch` for another name or tracking id. Same per-token limit as `/v1/session` |
| `POST /v1/alias` | Bearer | `{ "plugin": "RuneSync" }` | `200 { "alias": "a1_7k2m...", "assertion": "<signed, 15 min>" }` |
| `POST /v1/unlink` | Bearer | `{}` | `200 { "unlinked": true }` (revokes every token on that link) |
| `POST /v1/forget-me` | Bearer | `{ "confirm": "DELETE" }` | `200`: deletes this character, its links and tokens (the plugin's "delete my data"). When another Discord account also has an active or disputed link on the character, only the caller's links and tokens are deleted |
| `GET /v1/keys` | none | | public key set for checking assertions (section 8.3) |

`discordName` goes only to the RuneLink panel so the player sees "Linked to @someone"; the library never passes it on.

Formats: user codes are 8 characters from the 27-symbol alphabet `BCDFGHJKMNPQRSTVWXZ23456789` (no vowels, so no accidental words; no 0/O, 1/I/L look-alikes), shown as `XXXX-XXXX`, drawn with `SecureRandom`. Device secrets are 32 random bytes, base64url. Tokens are `lnk_` + 43 base64url characters (32 random bytes), the same shape as `PluginTokens` but a different prefix so scanners and code can tell them apart. Pairing ids are random UUIDs.

Code input is forgiving: case-insensitive, spaces and hyphens ignored. RSN is cleaned with the same rule as `PluginPayload.normaliseRsn` (non-breaking spaces made ordinary, 1 to 12 of letters, digits, space, `-`, `_`); the lookup form lowercases it and treats space, `_` and `-` alike.

### 5.2 Sign-in page routes (cookie session on the link host only)

| Route | What |
|---|---|
| `GET /` | Code entry page (the address the player types). `?code=` pre-fills, for players reading the code from a phone photo. |
| `POST /web/code` | `{ "code" }` checks the code, binds the pairing to this browser session. |
| `GET /web/auth/discord`, `GET /web/auth/callback` | OAuth start and callback (state, `iss` and PKCE checked; token used once and dropped). |
| `GET /web/pairing` | What the confirm screen shows (RSN, Discord name), only after sign-in. |
| `POST /web/confirm` | `{ "decision": "link" | "deny" }`. |
| `GET /links` (page) + `GET /web/links` | The signed-in Discord's links: RSN, linked date, last seen, verification, method. |
| `POST /web/unlink` | `{ "linkId" }`. |
| `POST /web/delete-my-data` | `{ "confirm": "DELETE" }`, signs the session out after. |
| `POST /web/signout` | |

All POSTs need the session cookie (`HttpOnly`, `Secure`, `SameSite=Lax`, host-only, `__Host-` prefix) plus a CSRF token. No other cookies, no analytics.

### 5.3 Bot internal routes

`/internal/rsnlink/...` mirrors 5.1 and 5.2 (the website passes the client IP and, for web routes, the signed-in Discord id it got from the callback). As built (T-113): every route is a POST with a JSON body except `GET v1/keys`, so nothing secret sits in a URL. The client IP comes in `X-Link-Client-Ip`, the plugin token in `Authorization: Bearer`, and web routes carry `browserSession` (the opaque id in the link page's own cookie; the bot stores only its hash and binds the pairing to it) plus `discordId` and `discordName` once signed in. The web routes are `web/code`, `web/pairing`, `web/confirm` (answers `{ "result": "linked" | "disputed" | "denied" }`), `web/links`, `web/unlink`, `web/delete-my-data`. First-party resolve (T-200, RUNESYNC-DESIGN.md section 3): `POST /internal/rsnlink/resolve` {assertion} answers `{ discordId, rsn, verification, disputed }` for a valid, unexpired assertion made for a first-party plugin (today only `runesync`) whose alias still matches an active link; anything else is 401 `invalid_assertion`. It is not under `v1/`, so the website never forwards it; other plugins only ever see an alias. The RuneClan Admin routes for T-118 live under `/internal/admin/rsnlink/...` and are owner-only (`BotOwners`), audit-logged.

## 6. Data model (dedicated database)

Database from `LINK_DATABASE_URL`; locally a new database `jonnybot_link` on the dev Postgres (not the `younglings` schema, not a copy of live). Schema `rsnlink`, created by the service's own bootstrapper (`CREATE ... IF NOT EXISTS`, the house pattern) with a `schema_version` row so later changes are ordered migrations.

| Table | Columns (main ones) | Notes |
|---|---|---|
| `discord_user` | `id uuid pk`, `discord_lookup bytea unique` (HMAC of the id), `discord_enc bytea` (AES-256-GCM), `key_version smallint`, `name_enc bytea` (the username, encrypted, for "Linked to @someone"), `dm_optout_until timestamptz`, `created_at` | The raw Discord id is never stored in plain text. Lookups use the HMAC. |
| `game_character` (built name; `character` is a Postgres type keyword) | `id uuid pk`, `tracking_lookup bytea unique null` (HMAC of tracking id), `rsn_display text`, `rsn_lookup text`, `last_seen_at`, `created_at` | `rsn_lookup` is the lowercased form; not unique (recycling, section 11). |
| `link` | `id uuid pk`, `character_id`, `discord_user_id`, `status` (`active`, `disputed`, `unlinked`, `released`, `superseded`), `method` (`oauth`, `dm`), `verification text not null default 'implicit'`, `verification_data jsonb`, `verified_at`, `created_at`, `ended_at` | Partial unique index: one `active` link per character. A Discord user may have up to 20 active links (several characters). |
| `link_token` | `id`, `link_id`, `token_hash bytea unique` (SHA-256), `client text`, `created_at`, `last_used_at`, `revoked_at` | At most 5 active per link (several PCs); the oldest is revoked when a sixth is issued. |
| `pairing` | `id uuid pk`, `method`, `code_lookup bytea` (HMAC), `device_secret_hash bytea`, `rsn_display`, `rsn_lookup`, `tracking_lookup`, `target_discord_lookup` (DM only), `discord_user_id` (after sign-in), `browser_session_hash`, `status` (`pending`, `approved`, `denied`, `consumed`, `expired`, `locked`), `attempts smallint`, `created_at`, `expires_at`, `ip_lookup` | Deleted 24 h after it ends. Unique `code_lookup` among pending rows; a collision just draws again. |
| `rsn_history` | `character_id`, `rsn_display`, `seen_from`, `seen_to` | Shown only to the owner on `/links`. |
| `abuse_event` | `id`, `at`, `kind`, `subject_kind` (`ip`, `discord`, `tracking`, `rsn`), `subject_lookup bytea`, `detail jsonb` (counts, never codes or tokens) | Kept 30 days. Feeds T-118. |
| `link_block` (built name) | `subject_kind`, `subject_lookup`, `reason`, `until`, `created_by`, `created_at` | Owner-set or automatic (section 8). |
| `admin_audit` | `at`, `actor`, `action`, `target` | T-118 actions. |
| `dispute` (added 2026-10-20) | `id uuid pk`, `rsn_lookup`, `rsn_display`, `incumbent_link_id`, `claimant_link_id`, `status` (`open`, `resolved_incumbent`, `resolved_claimant`, `withdrawn`), `opened_at`, `resolved_at`, `resolved_by`, `note` | Two characters claiming one name (section 11). Shown in T-118; settled by the owner now, by the T-122 tie-breaker later. |
| `dm_send` (T-115, migration v2) | `id`, `target_lookup bytea` (HMAC of the Discord id), `at`, `outcome` (`sent`, `failed`, `opted_out`, `blocked`, `target_cooldown`, `target_daily_cap`, `breaker`) | Every DM attempt, for the per-target caps and the service-wide breaker. Kept 2 days. The typed Discord id of a DM pairing is stored only sealed on the pairing (`target_discord_enc`), deleted with it; a `discord_user` row is made only when the recipient confirms the code or presses "Stop these messages". |

Every "lookup" column is an HMAC-SHA-256 with a server key, never a plain hash: Discord ids, tracking ids, IPs and 8-character codes are guessable, so an unkeyed hash would not protect them.

### 6.1 Keys and secrets (all from env; never in git, never logged)

| Env | Use |
|---|---|
| `LINK_DATABASE_URL` | The link database. |
| `LINK_ENC_KEY` (+ `LINK_ENC_KEY_OLD` during rotation) | AES-256-GCM for Discord ids; `key_version` per row allows rotation by re-encrypting in the background. |
| `LINK_LOOKUP_KEY` | HMAC for the lookup columns. |
| `LINK_ALIAS_KEY` | HMAC for aliases (separate so it can never be rotated by accident: rotating it changes every alias). |
| `LINK_SIGNING_KEY` | Ed25519 private key for alias assertions; public half published at `/v1/keys`. |
| `LINK_DISCORD_CLIENT_ID`, `LINK_DISCORD_CLIENT_SECRET` | The OAuth application used for sign-in (website side only): RuneClan Bot's app for now, the separate neutral app later. |
| `LINK_PUBLIC_BASE_URL` | Section 14. |

The service refuses to start if a key is missing or shorter than 32 bytes. Local dev keys go in the git-ignored env files only. Tests build random keys at runtime; no key-shaped literal appears in the repo.

## 7. Lifetimes

| Thing | Lifetime |
|---|---|
| Pairing and its user code (both flows) | 10 minutes, one use |
| Poll interval | 5 s; `slow_down` adds 5 s each time a plugin polls too fast |
| OAuth `state` / PKCE verifier | 10 minutes, one use |
| Discord access token | not stored: used once in the callback, then dropped |
| Sign-in page session | 15 minutes idle, 1 hour max; only for pairing and managing links |
| Link token | no fixed expiry; revoked by unlink, delete, a sixth device, or 365 days unused |
| Alias assertion | 15 minutes |
| Ended pairings | deleted after 24 h |
| Ended links and their tokens | deleted 30 days after unlink (so an accidental unlink can be shown on `/links`), at once on delete-my-data |
| Abuse events | 30 days |
| DM opt-out | 90 days, renewable from the DM button |

## 8. Rate limits, lockouts and blocks

Two layers: the website applies coarse per-IP limits before forwarding (as `pluginProxy.ts` does), the bot applies the real ones below. The code, start and DM limits (wrong codes per Discord account, codes per IP, starts per IP / tracking id / RSN, DMs per IP / tracking id) are counted in the link database's `rate_window` table, so every replica shares one count (SCALING-STATE.md M7); the token, alias and web-account route limits stay in memory per process. Counters that must survive a restart (lockouts, blocks, DM caps) are in the database. All numbers are constants in one class so they can be tuned.

| Action | Limit | On breach |
|---|---|---|
| Wrong code on one pairing (web or DM confirm) | 5 attempts | pairing `locked` |
| Code entry on the web, per IP | 10 per minute, 30 per hour | 429 |
| Wrong codes per signed-in Discord | 10 per hour | that Discord cannot enter codes for 1 hour |
| `pair/start` per IP / per tracking id / per RSN | 10 / 5 / 5 per hour | 429 |
| `pair/dm` per target Discord | 1 per 10 minutes, 3 per day, none while opted out | silently not sent (same 202) |
| `pair/dm` per IP / per tracking id | 5 / 3 per hour | 429 |
| DMs in total (circuit breaker) | 300 per hour across the service | new DM requests silently not sent; owner alerted in T-118 |
| Poll per pairing | 1 per interval | `slow_down` |
| Token routes per token | 60 per minute | 429 |
| Alias route per token | 30 per minute (library caches) | 429 |
| Repeat offenders | 3 lockouts of the same IP, tracking id or Discord in 24 h | automatic `block` for 24 h, shown in T-118 |

429 answers carry `Retry-After`. Blocks answer exactly like rate limits, so a blocked party learns nothing extra.

## 9. Links, unlink and delete-my-data

### 9.1 What a link is

One character (tracking id if known, else the RSN) linked to one Discord account. A Discord account can link several characters. A character has at most one active Discord link.

### 9.2 Creating a link on approval

- Same character and same Discord already active: **reuse** the link (same id, so the same aliases), issue a new token.
- Same character (same tracking id), different Discord: the new link becomes active, the old one `superseded`. Only someone running the plugin as that character can present its tracking id, so this is the owner moving to another Discord.
- RSN already actively linked under a different tracking id, or a claim without a tracking id for an RSN whose link has one: see section 11 (recycling and forgery).
- Otherwise: new character row (if needed) and new link.

### 9.3 Plugin side storage

PersistentDB is shared by every character played on the PC (3.1a), so RuneLink keeps **one token per character** (`lib/store.lua`, RuneLink 0.2.0):

| Key | Holds |
|---|---|
| `token.t:<tracking id>` | the token, when trackingAccess is granted (survives renames) |
| `token.n:<rsn lookup form>` | the token otherwise |
| `trackingFor.<rsn lookup form>` | the tracking id that name last had, so the token is still found if trackingAccess is later turned off |

The `t:`/`n:` prefixes stop a name made only of digits from colliding with a tracking id. Older keys are moved on first use: RuneLink 0.1.0's `token.<tracking id>` and `token.<rsn lookup form>` (only to the character they name), and an early build's single `token` key (to the first character that logs in). When trackingAccess is granted later, a `token.n:` token moves to `token.t:`. Saved right after pairing; deleted from every place on unlink, 401 or forget-me. Never exported. RuneSync applies the same rule to its saved event queue (`queue.v2.<character>`).

On logout or a character switch RuneLink forgets the character, drops any answer still in flight for it, and tells dependents `false` before the next character's value (10).

### 9.4 Unlink

- From the plugin (`/v1/unlink`): this character's link ends, all its tokens revoked, the plugin deletes its stored token. Dependents get `onLinkChanged(false)`.
- From the web (`/links`): same, for any of the signed-in Discord's links. The plugin finds out on its next `/v1/session` (401 `token_revoked`), deletes the token and fires `onLinkChanged(false)`.

### 9.5 Delete my data

- **Web** (signed in with Discord): deletes the `discord_user` row, every link, token, pairing and history row of theirs, and abuse events that point at their Discord lookup. Only an active `block` is kept (as an HMAC, no readable id) until it ends, because otherwise deleting would be a way around a block; the privacy text says so.
- **Plugin** (`/v1/forget-me`): deletes this character, its links, tokens and history. A tracking-less character can be shared by several Discord accounts' claims; while another account has an active or disputed link on it, only the caller's links and tokens are ended and deleted, and the character, its history and the other accounts' links stay. The plugin then clears its PersistentDB.
- Both are immediate hard deletes. Backups: the honest wording on how long backups keep data is written at release once hosting is chosen (T-120).

## 10. Plugin library for dependents

Exported from RuneLink's `main.lua`. **Read-only** functions only (T-129): none returns the token, the Discord id or the Discord name, and none can pair, unlink or change anything. Any plugin that depends on RuneLink can call all of them (3.1a), so:

- They only read a status copy and post requests to an inbox (`lib/bridge.lua`); RuneLink's own tick validates and answers each request. They never call into RuneLink's core.
- They use **no globals**, so even a plugin using Lua's `debug` library to inspect their hidden variables (if the client exposes it) reaches only the bridge, not RuneLink's environment, storage or token.
- `main.lua` refuses to start if anything other than these functions is exported. `tools/check_exports.py` and `tests/run.lua` (run with `tools/run_tests.py`) check all of this.

```lua
-- In the dependent's plugin.json: "requiredDependencies": { "RuneLink": "RuneLink" }  (see 3.1; or optionalDependencies)

RuneLink:ApiVersion()                 -- 2 (RuneLink 0.2.0; 1 = 0.1.0)
RuneLink:IsLinked()                   -- true / false / nil; always the character logged in NOW (nil = nobody in game or not known yet)
RuneLink:GetVerification()            -- "implicit" today; later values in section 12
RuneLink:GetAlias("MyPlugin", function(alias, err) end)
RuneLink:GetAssertion("MyPlugin", function(assertion, err) end)   -- signed, for the dependent's own server
RuneLink:OnLinkChanged("MyPlugin", function(linked) end)          -- one callback per name, replaced on re-subscribe;
                                                                -- also false at logout / character switch
RuneLink:OpenLinkPanel()              -- shows the panel (no pairing starts by itself); at most once per 5 s
```

Rules for the implementation (T-116):
- No HTTP inside these functions (section 3.1): calls go on a queue that RuneLink's own `ServerLogic` subscription sends. Callbacks run later from RuneLink's loop, wrapped in `pcall` so a dependent's error cannot break RuneLink.
- Aliases are cached per plugin name for the session.
- `err` values: `"not_linked"`, `"offline"`, `"rate_limited"`, `"bad_name"`.
- Plugin names for aliases: trimmed, 1 to 64 of letters, digits, `.`, `_`, `-`; compared lowercase.
- Answers that arrive after a logout are dropped (aliases waiting for them get `"offline"`), so a dependent never receives the last character's alias for the next one.

Panel (T-129): every control is at least 36 units tall for touch; "Link Discord" shows the address and code and needs no typing in the game; on a device without a keyboard (`Keyboard.IsAvailable()` false) the DM fallback is behind "Use a DM code instead"; the code label is restricted (`config.RESTRICT_CODE_LABEL`, in case a client ever hides restricted labels from the player too).

## 11. RSN changes, recycling and forged claims

- **Rename:** `/v1/session` with the same tracking id and a new name: the character's `rsn_display` changes, the old one goes to `rsn_history`. Link and aliases stay. A rename onto a different name is refused (409 `name_unavailable`) while another character has an active or disputed link on that name; the plugin then drops its token and the player links again, which runs the claim rules below. A rename that goes ahead drops the link back to `implicit`, and the verifiers check the new name. Re-pairing the same character under a held name is not a plain rename either: the claim rules decide it and the character's old link is `superseded`.
- **Recycling** (a name released by one player and taken by another): the new owner's pairing carries a different tracking id, so it is a different character. If the old character with that name has **not** reported the name for 30 days, its link is marked `released` (the name is clearly no longer theirs) and the new link is made. If it reported the name within 30 days, **the claim is not refused** (changed 2026-10-20, see the next bullet).
- **Disputes (added 2026-10-20 from the plugin-auth research):** refusing the newcomer would let a squatter who claimed an unlinked name with an invented tracking id, and keeps calling `/v1/session`, lock the real owner out forever. So when a tracked claim meets a name another tracked character reported within 30 days, and the incumbent is still `implicit`, the newcomer's link is made with status `disputed` and a `dispute` row is opened. While disputed: the claimant's plugin gets its token but `linked: false` and no alias; the incumbent stays linked but `/v1/session` and assertions carry `disputed: true` so consumers can be careful. The dispute shows in the owner's abuse view (T-118), where the owner can settle it; when either side unlinks it settles itself (incumbent leaves: the claimant becomes active; claimant leaves: withdrawn). A link that is replaced by a new link for the same owner (pairing again with a tracking id, re-pairing under another name, or the tracked character moving to another Discord) does not leave: its open disputes pass to the new link and the claimant stays `disputed`. The automatic tie-breaker ("the plugin knew it first" XP check, a directed Hiscores challenge) is parked in T-122 and plugs into the `LinkVerifier` hook. A link verified above `implicit` cannot be disputed by an implicit claim; that claim gets the generic refusal ("This name can't be linked right now; if it is yours, try again later or use the plugin with tracking allowed").
- **Claims without a tracking id:** allowed (tracking is optional), but they cannot take a name that is actively linked with a tracking id; they get the generic refusal. Two tracking-less claims by different Discord accounts open a dispute too (neither can be proven). A tracking-id claim on a name another Discord account holds with a tracking-less link goes to a dispute while that link is `implicit` (the incumbent stays linked until it is settled), and gets the generic refusal when that link is verified above `implicit`. This holds with any `LINK_VERIFY_*` settings. The same Discord account pairing its own tracking-less character again with a tracking id replaces its old link (the tracking-less one becomes `superseded`). A tracking-less claim on a name whose tracking-less link is verified above `implicit` gets the generic refusal instead of a dispute, as for any implicit claim on a verified link. `/v1/session` attaches a reported tracking id to a tracking-less character only for an active link that is the character's one current link: while another Discord account has an active or disputed link on it, the character stays tracking-less and the tracking id is ignored.
- **Forged claims** (someone calling the API with curl and a name they don't own): with implicit trust the service cannot tell a real client from a script. What limits the harm: a forger cannot know another player's tracking id, so they cannot take over a tracked link; dependents only ever see an alias and the `implicit` level; and section 12's hook lets a real check be added. This is exactly why links are `implicit`, and consumers that need strong proof (clan ranks in T-121) keep their own checks.

## 12. Trust and the verification hook

- `link.verification` is a text value. The levels, weakest first (updated 2026-10-20): `implicit` (taken on trust), `public_data_corroborated` (public Jagex data later matched something only the player's own client could have known first, e.g. exact XP before Hiscores updated), `jagex_signed` (Jagex vouched for the session; does not exist yet, T-119). An unknown stored value counts as `implicit`. `public_data_corroborated` is reached by the check in 12.1 (T-170, off by default); everything else stays `implicit`. `verification_data` (jsonb) and `verified_at` hold the evidence. No migration is needed to add a method.
- Java hook: `interface LinkVerifier { String method(); Optional<VerificationResult> check(LinkContext ctx); }` plus defaults `wantsXpReports(level)`, `attach(Raiser)`, `describe(linkId)` and `overview()` (T-170). The service runs every verifier after a link is made, on `/v1/session` and on `/v1/xp`; the strongest result wins. A verifier that works in the background raises a link later through the `Raiser` it was given. Raising a link is also the **dispute tie-breaker**: in an open dispute where the other side is weaker, the raised link keeps the name and the other is `superseded` (`resolved_by = 'verification'`, abuse event `dispute_settled_by_verification`); when both sides are equally strong the owner decides. A level never goes down, and an ended link is never raised.
- The level is visible to the plugin (`GetVerification()`), in assertions (`ver`), and on `/links`, so consumers can decide how much to trust a link.
- Staff checks are never added (volume). Still parked in T-122: a Jagex-signed identity (asked in T-119); a directed Hiscores XP challenge (gain XP in a named skill; clue-scroll fallback for 200m-all accounts); title or privacy toggles.

### 12.1 "The plugin knew it first" (T-170; retired from the bot, the hub runs it now)

Code: `rsnlink.verify` (pure logic `XpVerifier`, ports `PublicData` and `VerifyStore`, adapters `HttpPublicData`, `PgVerifyStore`, `XpLinkVerifier`). It imports only the JDK, slf4j, JDA's JSON type and the `LinkVerifier` hook, so it moves to rsclans-hub unchanged (a test holds this).

1. **Reports.** When `/v1/session` says `xpReports: true`, the plugin sends `/v1/xp` with the exact XP (tenths) of skills that changed, batched (about once a minute while XP moves) and once more on `GameStateChanged` to the lobby or login screen. Each value is a claim. Capped (200m), zero and unknown skills are ignored; at most 120 values per link per hour.
2. **Is it public yet?** A single worker thread reads public data **after** the report, never while the plugin's request waits: Hiscores lite (plus the ironman table when the main one doesn't rank the skill) and the RuneMetrics profile. If the claim's whole XP is already shown anywhere, it is refused (`REFUSED_PUBLIC`). Otherwise it is a prediction (`PENDING`), with the best public value as its baseline. Public values only go up, so any read after the report that shows less proves it wasn't public at report time. A failed read of either source accepts nothing; a claim not checked within an hour is refused.
3. **After logout.** The worker reads the Hiscores again 2 minutes after a lobby/logout report, 15 minutes after reports stop otherwise, 30 seconds after the next login, then backs off (10 min, 30 min, 1 h ... 72 h). When a skill's public value changes: a pending claim equal to it (whole XP, i.e. floor of tenths / 10) is a `MATCH` if it gained at least `LINK_VERIFY_MIN_GAIN_XP` (100) on its baseline, else `MATCH_SMALL` (doesn't count; also when the baseline was unknown); the latest claim the public value moved past is a `MISS`; older ones are `SUPERSEDED`. A public value below the claim (an earlier session publishing) just keeps it waiting. Pending claims expire after 72 hours.
4. **Corroborated** when matches >= `LINK_VERIFY_MIN_MATCHES` (2) and misses <= 2 x matches (an owner who closes the client before the last batch misses now and then; a guesser misses thousands of times per lucky hit). Evidence stored: match and miss counts, skill names, publish time; never XP values. The link is then raised and the plugin is told to stop sending.
5. **Politeness.** At most `LINK_VERIFY_CHECKS_PER_MINUTE` (20) fresh checks a minute service-wide and `LINK_VERIFY_CHECKS_PER_LINK_DAY` (24) per link per UTC day; reads are shared for 60 s (both sides of a dispute read one name) but never reused for a claim made after the read. Identifying User-Agent.
6. **Only the Hiscores complete a match.** RuneMetrics is used only to refuse, so the check stays safe even if experiment E2 shows RuneMetrics updates during play.
7. **Storage.** `verify_claim` and `verify_watch` beside the link tables (rows go with their link), made only when the check is on. Settled claims and idle watches are deleted after 30 days.
8. **Views.** `/web/links` and the owner's link and dispute rows carry `verify.xp_first_seen` (state `off`, `not_started`, `waiting`, `idle` or `corroborated`, counts, next check); the owner's overview carries `verify` totals and `verifiedLinks`.

## 13. Aliases and assertions

### 13.1 Alias

```
alias = "a1_" + base32_lower( HMAC-SHA-256( LINK_ALIAS_KEY, "alias|v1|" + link.id + "|" + lowercase(pluginName) ) )[0..26]
```

- Stable for the life of the link: re-pairing on a new PC reuses the link, so the alias does not change. Unlinking and linking again makes a new link and therefore new aliases (a fresh start is a privacy feature).
- Different for every plugin name, and the key is server-side, so plugins cannot match players across each other.
- A plugin that lies about its name only gets a different alias for the **same local player**. It can never get another player's data: the token is the local player's, and no route takes an RSN or Discord id to look up someone else.
- Honest limit, stated in the README: a dishonest dependent running on the player's own PC could ask for the alias another plugin would get for that same player. It still learns nothing about the Discord account or other players.

### 13.2 Assertion (so a dependent's server can trust an alias)

A dependent's own server cannot tell a real alias from a made-up one, so `/v1/alias` also returns a short signed statement (compact JWS, EdDSA): `{ "iss": <base URL>, "plugin": "runesync", "alias": "a1_...", "rsn": "zezima", "ver": "implicit", "iat", "exp" (+15 min) }`. The server checks it against `/v1/keys`. `rsn` is the local player's own name, which the dependent already knows; no Discord data is in it.

### 13.3 DM text (T-115)

> Someone playing RuneScape as **Zezima** asked to link this Discord account using the RuneLink plugin.
> If that was you, type this code into the plugin: **BHKD-7Q2M** (expires in 10 minutes).
> If it wasn't you, ignore this message. Nothing is linked without the code.
> [Not me] [Stop these messages]

No clan name, no invite, no links other than the configured base URL.

## 14. Neutral branding and configuration

**The page** (T-114):
- Uses the website's visual theme (colours, fonts, components) but no clan name, clan logo, RuneClan Bot branding, Discord server invites, ads, donation links, or links into the clan site.
- Title and header are the plugin name and the words "Link your RuneScape name to Discord". Footer: privacy page, source link to the public plugin repo, contact email (T-120).
- OAuth app: RuneClan Bot's for now, so Discord's consent screen says RuneClan Bot; later a separate neutral app (Q2), switched by config only. The page itself stays unbranded either way.
- Not "official": it never uses Jagex or Discord logos, and says "not affiliated with Jagex or Discord".
- Phone-width first; clear messages for wrong, expired and locked codes and for rate limits.

**Privacy text** (short, must match the code):
> We store your RuneScape name (and the plugin's tracking number, if you allowed it) and your Discord account id. Your Discord id is encrypted, sign-in tokens are stored only as fingerprints, and we never see your Discord password or email. Plugins that use this link only see whether you are linked and a code that is different for every plugin; they never see your Discord account. You can unlink or delete everything at any time on this page.

**Config:**
- `LINK_PUBLIC_BASE_URL` means different things to its readers. **Hub** (where links live today) and the bot's legacy rsnlink service: the full address shown to the player, used for `verifyUrl`, DM text and assertions' `iss`, so it includes the path: live `https://runeclans.com/plugins/link` (the hub takes it from `HUB_SIGNIN_BASE_URL`), local `http://localhost:3200/plugins/link`. **Website** (runeclans.com): an origin only, `https://runeclans.com`, or unset to fall back to `NEXTAUTH_URL`; it adds `/plugins/link` and `/api/link/web/auth/callback` itself, so a value with a path breaks Discord sign-in. In production the bot's own value does not matter: the bot's rsnlink service is legacy (replaced by the hub, T-162) and nothing on the bot needs setting for RuneLink.
- The RuneLink pages live only on runeclans.com (`/plugins/link`); rsyounglings.com and its `LINK_HOST` face no longer have them.
- Plugin `plugin.json`: `"urls": { "link": "<base URL>" }`. Dev builds point at `http://localhost:3200`, the local runeclans.com app (the beta client allows plain http to localhost). The release value is `https://runeclans.com`; changing it after release means a plugin update and a new Jagex review.
- The address should be short enough to type from the panel (`runeclans.com/plugins/link`).

## 15. Plugin name: RuneLink (decided)

Drake picked **RS Link** (2026-10-19), replacing the earlier "Discord Link", and renamed it **RuneLink** (2026-10-26). The name does not contain "Discord", which keeps clear of Discord's brand guidelines (discord.com/branding): third parties may not put the Discord marks into a product, bot, brand or domain name, but may use the word to describe what a product works with, as long as it does not suggest Discord made or endorses it.

- Discord appears only in descriptions, e.g. "Link your RuneScape name to your Discord account", plus "not affiliated with Jagex or Discord" on the page and in the README.
- Lua import: dependents list `{ "RuneLink": "RuneLink" }` and call `RuneLink:...` (section 3.1). The old name "RS Link" had a space and needed the object form `{ "RSLink": "RS Link" }`.
- The web host, OAuth app and repo names avoid "discord" too.
- **Before release (T-120):** check against Jagex's plugin guidelines that the name does not suggest the plugin is official or made by Jagex, and that using "Rune" (as in RuneScape, a Jagex trademark) in a plugin name is allowed; if not, rename before any other plugin depends on it.

## 16. Abuse list and mitigations

| Abuse | Mitigation |
|---|---|
| **DM spam** (typing other people's Discord ids) | Per-target 1 per 10 min and 3 per day, per-IP and per-tracking limits, service-wide hourly cap, "Stop these messages" opt-out, neutral text that names the asking RSN, DMs only follow a player action. |
| **Code guessing** | 8 characters from 27 unambiguous symbols (about 2.8 x 10^11 codes) live for 10 minutes; 5 tries per pairing then locked; per-IP and per-Discord limits; repeat offenders blocked. |
| **Enumeration** (does this Discord id / RSN exist?) | `pair/dm` always answers 202; the RSN is shown only after sign-in; blocks look like rate limits; no route takes someone else's RSN or Discord id. |
| **Doxxing via nearby-player lookups** (seeing a player in game and looking up their Discord) | No RSN-to-Discord or Discord-to-RSN route exists; dependents get an alias only; Jagex rules forbid exporting other players' data anyway; aliases differ per plugin so services can't join their data. Raw lookups stay out until an opt-in design exists. |
| **Phishing look-alikes** (fake sign-in pages, "enter this code" scams) | One fixed host from config shown in the panel; the plugin never shows other links; OAuth scope `identify` only (nothing to steal but a username); confirm screen warns "never enter a code someone sent you"; privacy page explains we never ask for a password; public repo lets anyone check the real address. |
| **Consent phishing** (attacker gets a victim to approve the attacker's code, linking the attacker's character to the victim's Discord) | Confirm screen always names the RSN and warns to continue only if you are logged in as it now; "This isn't me" denies and records it; the result is only an `implicit` link the victim can see and remove on `/links`. |
| **RSN recycling** | Keyed on tracking id when granted; 30-day rule before a recycled name's old link is released; renames tracked in history (section 11). |
| **Forged claims / impersonation** | Cannot take over a tracked link without the tracking id; `implicit` level visible to every consumer; strong-proof uses keep their own checks; verification hook for later. |
| **Token theft** (from the player's PC) | Token only in RuneLink's own PersistentDB, never exported; revocable from `/links`; max 5 per link; prefix `lnk_` so secret scanners recognise it; stored server-side only as SHA-256. |
| **Malicious dependent** | Gets functions only; no token, Discord id or name; cannot make RuneLink call arbitrary URLs; plugin name only changes the alias. |
| **Database leak** | Discord ids encrypted, lookups are keyed HMACs, tokens hashed, codes hashed; keys only in env; separate database from clan data. |
| **Log leak** | Never log codes, tokens, device secrets, Discord ids, tracking ids or IPs in plain text; log the pairing id and lookup prefix only. A unit test runs the flows with a capturing logger and fails if any of those values appear. |
| **OAuth CSRF / session mix-up** | `state`, `iss` and PKCE checked; pairing bound to the browser session that entered the code; `__Host-` cookie, CSRF token on POSTs. |
| **Scale** (tens of thousands of players, login bursts after updates) | Polling only while a pairing is open (10 min max, `slow_down`); `/v1/session` once per login; aliases cached in the plugin; indexed lookups only; per-IP limits at the website; DM circuit breaker protects the bot from Discord limits; single-instance limits noted as a thing to revisit if the service is scaled out. |
| **Bot account risk** (Discord flags RuneClan Bot for DM volume) | Same DM caps and opt-out; DMs only to users who share a server; the primary flow sends no DMs at all. |

## 17. How each build ticket maps to this spec

| Ticket | Builds | Sections |
|---|---|---|
| **T-113** link service + database | `rsnlink` package with the clean boundary of section 2 (import test, `LinkDmSender`), own DataSource and schema, every table, keys, codes, tokens, pairing state machine, section 9 link rules, section 11 recycling, rate limits and blocks, aliases and assertions, verification field + `LinkVerifier` hook (no verifiers), internal routes 5.3, delete paths, retention jobs. Tests: expiry, lockout, limits, token hashing, alias stability, recycling rules, "no secrets in logs". | 2, 5, 6, 7, 8, 9, 11, 12, 13.1, 13.2, 16 |
| **T-114** neutral sign-in page | Link host face (`LINK_HOST`), plugin API proxy with per-IP limits, code entry, OAuth (RuneClan Bot's app for now, client from config), confirm screen, `/links` with unlink and delete-my-data, privacy text, preview-harness views of each screen with made-up data. | 3.2, 4.1, 5.1 (proxy), 5.2, 14 |
| **T-115** DM fallback | `pair/dm` + confirm, id or username input with resolution among shared servers, uniform 202, DM sending with JDA, DM buttons (Not me, Stop), opt-out, DM caps and circuit breaker, panel copy; Drake tests user-install DMs with the dev bot. | 3.2, 4.2, 8, 13.3 |
| **T-116** RuneLink plugin | New private repo (MIT), `plugin.json` (`httpAccess` required, `trackingAccess` optional, one `link` URL), pairing panel (code + address, Discord username-or-id box, code box, status, unlink, delete), token in PersistentDB, `/v1/session` on login, library section 10 with queue + `pcall`, README for dependents. | 3.1, 4, 9.3, 10, 14 |
| **T-117** RuneSync uses it | Adds RuneLink to RuneSync's dependencies, uses `IsLinked` / `GetAlias` / `GetAssertion`, drops the dev-echo pairing assumptions. | 10, 13.2 |
| **T-118** abuse view | RuneClan Admin page: counts and recent `abuse_event`s, lockouts, DM breaker state, block/unblock (IP, tracking, Discord), remove a link; owner-only, audit-logged; Discord ids shown only to the owner (decrypted on demand); never codes or tokens. | 5.3, 6, 8, 16 |
| T-119 (Drake asks Jagex) | Unknowns in 3.1. | 3.1 |
| T-120 (release) | Domain, region, privacy page, backup wording, contact email, repo public, submission. | 9.5, 14 |
| T-121 (clan server) | Needs Discord ids, which dependents never get: the opt-in for RuneClan Bot servers is decided there (Q5). | 1, 13 |
| T-122 (parked) | Verifiers against the hook. | 12 |

Website-first rule: the player's controls (link, unlink, delete, see links) are on the link host, not in Discord. Discord-only use is the DM fallback, which still works. Owner controls are in RuneClan Admin (T-118, runeclans.com/admin); there are no per-server settings in v1, so no dashboard page is needed until T-121.

## 18. Decisions (Drake, 2026-10-19)

1. **Name:** "RuneLink" (2026-10-26; was "RS Link", which replaced "Discord Link"); Discord only in descriptions; Jagex-guideline check on the name before release (section 15).
2. **Sign-in app:** a separate neutral Discord app eventually, RuneClan Bot's app for now. Client id, secret and redirect come from config, so switching is config only (sections 3.2, 6.1, 14).
3. **DM fallback answer:** always the same, whether or not a DM can be sent (section 4.2).
4. **DM fallback input:** a Discord id or a username. Usernames are unique since Discord's username change; they are resolved only among servers RuneClan Bot shares, exact match only, and the same-answer rule still applies (sections 3.2, 4.2).
5. **RuneClan Bot servers seeing the real link:** decided in T-121.
6. **Signed alias assertions:** included now (section 13.2).
7. **Recycled names:** 30-day wait (section 11).
8. **Where it runs:** inside the bot for now; split into its own service when ready, with a clean module boundary so the split is a move, not a rewrite (section 2).

Still open, not blocking the build tickets: the pre-release name check against Jagex's guidelines (section 15, T-120) and the Jagex unknowns in section 3.1 (T-119).
