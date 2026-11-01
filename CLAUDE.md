# RuneClan Bot (repo RuneClan-Bot; formerly JonnyBot)

The jar (`JonnyBot.jar`) and some code identifiers still use the old name; product text says RuneClan Bot.

A Java 21 + Maven Discord bot (JDA + BotCommands) with an internal HTTP API that the RuneClans website calls. Branch from `dev` and open PRs into `dev`; `master` is the production deploy.

This repository is **public**: never commit secrets, real Discord ids (no 18-digit literals; build fake ids at runtime, e.g. `"1".repeat(18)`), internal plans or lessons files.

## Build and test
- **Run the full suite with a database, or about 109 DB tests skip silently and the build still says SUCCESS.**
  1. Start a throwaway Postgres (trust auth, user `ci`, 127.0.0.1 only). The cloud environment has `rc-pg-up`; otherwise mirror `.github/workflows/build-test.yml`.
  2. Create `local_tests` and `startup_check` (UTF-8, `-T template0`) and load `docs/bc-schema.sql` into both.
  3. In the SAME command as Maven:
     `export RUNECLAN_BOT_TEST_DATABASE_URL=postgresql://ci:@127.0.0.1:55433/local_tests STARTUP_CHECK_DATABASE_URL=postgresql://ci:@127.0.0.1:55433/startup_check STARTUP_CHECK_REQUIRED=true && mvn verify`
  - The URLs must be URIs (`postgresql://ci:@host:port/db`). The `jdbc:postgresql://...?user=` form makes every DB test skip.
  - Check the counts: a full run is about 2,800 tests with few or no skips. `StartupWiringTest` must run (Skipped: 0) and pass.
  - `HubDatabaseTest`, `PermissionGroupDatabaseTest` and `PlayerAccountsDatabaseTest` use the same `RUNECLAN_BOT_TEST_DATABASE_URL` (via `TestDatabaseUrl`, which falls back to the older JDBC-form `RUNECLAN_BOT_TEST_DB_URL`; the old `JONNYBOT_*` names still work), so they run in the normal full run.
- Don't use `mvn -q` when you need the `Tests run:` lines. On a fresh machine the first run must be online (`mvn -o` lacks plugins).
- Run `mvn -q test-compile` after every merge of `dev`: new constructor parameters on `DirectoryApi` / `SiteApi` / `ClanSiteAdminApi` break test constructors.

## Rules the tests enforce
- Every `@BService` / `@Command` class has exactly ONE constructor (`ServiceShapeTest`). Test seams go in a static factory or a nested class; test fixtures stay unannotated.
- Before creating a table, grep `CREATE TABLE IF NOT EXISTS younglings.<name>`: a name created twice fails `ServiceShapeTest`.
- Scheduled work uses `BotScheduler.lane("name")`, never your own executor (`SharedSchedulerTest`). Timed posts respect the dev-bot switch (`AutoPostRouter` / `posting.isEnabled()`).
- Every new environment variable the bot reads is documented in `docs/DEV-SETUP.md` section 3c (`SettingsDocumentedTest`).
- New `ListenerAdapter`s must be registered in `Bot.java`, or their buttons silently time out.
- Tests never hard-code the size of a registry other work extends (`PremiumFeatures.all()`, route lists): count from the registry.

## Patterns
- **Mentions:** the default blocks @everyone, @here and role pings. Use `Mentions.onlyRoles(...)` to ping a role. A message carrying user-written or other-server text allows no mentions.
- **Never wait on Discord without a time limit inside a web request.** The website gives up after 8 s. Use `complete(false)` / short timeouts: answer 503 "try again" for access checks, show "unknown" for display. Never cache "no answer" as "not allowed".
- **Website-triggered Discord writes** (posts, nicknames, roles) go through the Discord job outbox. Posts are never auto-retried after an unclear failure.
- **Route modules claim only the paths they serve:** the first module that returns `true` wins, so a catch-all 404 hides later ones. New `/internal/me/<x>` routes are an `InternalRouteModule`.
- `clan-site/...` routes in `ClanSiteAdminApi` also serve bot-less sites through `BotlessAdminApi` (`SiteCaller.botless()`), so new routes work for both. Bot-less callers are judged on the website's signed facts (`ActorFacts`, `ServerFacts`).
- `ClanSiteLayoutRulesV2` rebuilds site layouts from KNOWN fields: a new layout, page or widget field on the website silently drops unless it is added here too.
- Prefer extension hooks over constructor edits in shared registries (`AdminRouteExtension`, `InternalRouteModule`, `DirectoryRouteModule`, `MemberRouteExtension`).
- JDBC: Postgres jsonb `?` collides with parameter markers; use `jsonb_exists(...)` or `->>`.
- Shared caches that hand out `DataObject`s must be copied before mutating (`DataObject.fromJson(x.toJson())`).
- "never saved" defaults are the Java ones (`ClanProfile.empty`, `GuildSettingsService`), not the DDL `DEFAULT`.
- Product text uses the one bot-name constant (`BotBrand.NAME`); text inside one server says "the bot".

## Never
- Never run the bot against Discord from a cloud machine: the dev bot lives in a real community server.
- Never touch `master` or force-push.
