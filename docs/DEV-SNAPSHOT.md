# Dev snapshots

A snapshot bundles the bot's state at one moment into a zip with an id, so a bug report can say "snapshot `snap-20261018-143005-a3f9`" and whoever investigates can see what the bot, the server's settings and the relevant database rows looked like then. Added in T-093; the dashboard button (T-094) and the investigation kit (T-095) build on this document, so **the id and the zip layout are a contract**: change them only together with `formatVersion`.

## Who and how

Bot owner only (`BotOwners`: the Discord application's owner or team, plus `OWNER_IDS`). Everyone else gets 403 on every path.

| Way in | What it does |
|---|---|
| Dashboard, owner-only **Snapshots** page | take one (with an optional focus), list, download, delete |
| `GET /internal/admin/dev/snapshot` | take a snapshot of the server in the `X-Guild-Id` header now |
| `POST /internal/admin/dev/snapshot` | the same, body `{"focus": "event:12", "siteCommit": "<git sha>"}` (both optional) |
| `GET /internal/admin/dev/snapshots` | list: `{snapshots:[{id, guildId, createdAt, sizeBytes, requestedBy, focus}], keepDays, maxBytes}` |
| `GET /internal/admin/dev/snapshots/<id>` | one snapshot with its zip as `zipBase64`, plus `filename` |
| `DELETE /internal/admin/dev/snapshots/<id>` | delete it |
| `/dev snapshot [focus] [guild]` | the same from Discord; `/dev` exists on test servers only, so on the live bot use the dashboard |

The create calls return `{id, createdAt, focus, sizeBytes, filename, zipBase64}`. The usual internal-API rules apply (secret header, who is asking).

## The id

`snap-yyyyMMdd-HHmmss-xxxx`: UTC time of creation, then four random lowercase hex digits. Pattern: `snap-\d{8}-\d{6}-[0-9a-f]{4}`. The id is also the zip's file name (`<id>.zip`) and the primary key in `younglings.dev_snapshot`.

## Focus

`event:<id>`, `ticket:<id>` or `member:<discord id>`; none means a general snapshot. The event or ticket must belong to the server asked about (404 otherwise).

- **event**: `db/event.json` and `db/<table>.json` for every table with an `event_id` column (participants, teams, claims, progress, signups, ...).
- **ticket**: `db/ticket.json` and every table with a `ticket_id` column (helpers, transcript, flags).
- **member**: every table in the bot's schema that has a `guild_id` plus one of `user_id`, `requester_id`, `discord_id`, `member_id`, filtered to that member in that server.

Rows are capped per table (500, then 150, 40, 10 if the zip would pass 5 MB). Whole-number columns are written as strings so 19-digit Discord ids survive any JSON reader.

## Zip layout (formatVersion 1)

| File | Contents |
|---|---|
| `manifest.json` | `format` ("jonnybot-dev-snapshot"), `formatVersion`, `id`, `createdAtUtc`, `guildId`, `guildName`, `requestedBy{id,name}`, `focus`, `environment` (`live` / `dev` / `local`), `botCommit`, `siteCommit` (null unless the website passed it), `rowCapPerTable` |
| `environment.json` | environment, commits, start time and uptime, gateway status / ping / intents / server count, Java version, memory, **database server time**, whether auto-polling is on |
| `guild.json` | the server's effective settings (`effectiveSettings`), `featuresWithSettingsStored` (settings tables that have a row for the server) and the rows themselves under `settings` |
| `logs.txt` | WARN and ERROR log lines from the 30 minutes before the snapshot (in-memory buffer, 400 lines max, 12 stack lines each) |
| `db/*.json` | the focused rows, one file per table, a JSON array of objects |
| `README.txt` | a short human description of the above |

## Secrets

Nothing secret may be in a snapshot. Every file name and every byte of text goes through `SnapshotWriter`, which applies `SnapshotRedactor`:

1. every secret the bot was started with (environment / `.env` entries whose names contain token, secret, password, key, database url, plus the user and password inside any connection string) is replaced with `[REDACTED]` wherever it appears, also URL-encoded;
2. text shaped like a secret is masked even if the bot was never told it: Discord bot tokens, JWTs, `scheme://user:password@host`, `Bearer ...`, `key=value` / `"key": "value"` with a secret-looking key;
3. database columns named like secrets (token, secret, password, hash, key, session, ...) are never copied; tables `plugin_token`, `uploaded_image`, `dev_snapshot` and `admin_audit` are never read.

`SnapshotRedactionTest` seeds every kind of secret into every source of text and fails if one survives in the zip. A bare secret with no known value and no recognisable shape (a random word in a free-text column) cannot be detected, so snapshots still go only to the bot owner.

## Storage

`younglings.dev_snapshot (id, guild_id, created_at, size_bytes, requested_by, focus, data BYTEA)`. Capped at 5 MB per zip. Rows older than 14 days are deleted whenever a new snapshot is taken.
