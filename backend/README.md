# Team Tracker API

The Team Tracker backend: Java 25 + Spring Boot 3. It serves the `/api/v1` HTTP API of the frontend and is the source of
its API contract: `frontend/src/shared/api/openapi.json` is this application's OpenAPI document.

```
backend/
  src/main/java/tracker/
    TrackerApplication   @SpringBootApplication
    domain/              plain Java rules: working-day calendar, flags, scheduling, naming rules, capacity, timesheet,
                         analysis, anken roll-ups, Q&A, leave import validation
    application/         ports (repositories, JiraReader/JiraWriter, SecretBox), SyncService, TrackerContextLoader,
                         settings/ (TrackerSettings, SettingsStore), service/ (one @Service per screen)
    adapters/            web/ (controllers, Bean Validation request records, ProblemDetail errors, springdoc, SPA),
                         persistence/ (@Repository classes on JdbcClient, SQLite data source), jira/ (Jira Cloud REST v3),
                         fake/ (the simulated demo site), crypto/ (token encryption), scheduling/ (periodic sync)
    config/              TrackerProperties, CoreConfig (clock, encryption), DemoConfig (profile demo),
                         JiraSiteConfig (without demo)
  src/main/resources/
    application.properties, application-demo.properties
    db/migration/        Flyway migrations
```

`ArchitectureTest` keeps the layers apart (domain ← application ← adapters ← config): the domain is plain Java, and the
application uses Spring only for components and transactions — no web, JDBC or JSON.

## Conventions

- **Dependency injection**: constructor injection everywhere; configuration via `@ConfigurationProperties`
  (`TrackerProperties`) and Spring profiles.
- **Transactions**: `@Transactional` on service methods that write (`TransactionTemplate` inside `SyncService`).
  SQLite transactions take the write lock when they begin (`transaction_mode=IMMEDIATE` in the JDBC URL), so reads run
  without a transaction. A sync reads Jira first and then writes in one short transaction.
- **Persistence**: `JdbcClient` with named parameters, records mapped from columns, upserts with `ON CONFLICT`; the
  schema is managed by Flyway. Settings are one JSON document.
- **Errors**: RFC 9457 problem details (`application/problem+json`) with two extra members, `code` (`invalid`,
  `not_found`, `conflict`, `capability_disabled`, `jira_unavailable`, …) and `errors` (`[{field, message}]`, the field
  as a snake_case JSON path such as `overrides.1.start`). Malformed requests and Bean Validation failures answer 400,
  business rules 422, unknown routes 404.
- **JSON**: Jackson configured with `spring.jackson.*` properties: snake_case names, enum values (`fullTent`), ISO-8601
  instants.
- **Time**: a `java.time.Clock` bean in the team's time zone; `tracker.today` pins the date.
- **Contract**: springdoc serves the OpenAPI 3.1 document at `/openapi.json`; `@Nullable` (JSpecify) record components
  become `T | null` and every response property is required. `BootTest` fails when
  `frontend/src/shared/api/openapi.json` differs from it; `npm run gen:api` in `frontend/` rewrites the file and
  regenerates the TypeScript types.
- **Operations**: `/actuator/health` (with liveness and readiness probes).

## Run

Needs a JDK 25 (`java -version`). Maven comes with the wrapper (`./mvnw`). From the repository root,
`scripts/demo.sh`, `scripts/dev.sh [jira]` and `scripts/check.sh` build and run everything; from `backend/`:

```bash
./mvnw test                                                    # all backend tests
./mvnw package && SPRING_PROFILES_ACTIVE=demo java -jar target/tracker.jar   # demo on http://localhost:8000
```

| Setting (environment variable) | Meaning | Default |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `demo`: simulated Jira with writes and demo data recreated on every start; none: your Jira site, read-only in phase 1 | none |
| `SPRING_DATASOURCE_URL` | JDBC URL of the database | `jdbc:sqlite:data/tracker.db?…` (demo: `../.tmp/demo/tracker.db`) |
| `TRACKER_TODAY` | pin "today" (YYYY-MM-DD) | demo: `2026-10-07`; otherwise the real date |
| `TRACKER_TIME_ZONE` | the team's time zone | `Asia/Ho_Chi_Minh` |
| `TRACKER_SECRET_KEY` | key that encrypts the stored Jira token (AES-256-GCM) | `data/secret.key`, created on first run |
| `TRACKER_DATA_DIR` | database and key file directory | `data` |
| `TRACKER_STATIC_DIR` | built frontend to serve | `../frontend/dist` when it exists |
| `PORT` | HTTP port | `8000` |
| `SPRING_LDAP_URLS` | LDAP server URL(s), e.g. `ldaps://ad.company.local:636` (demo: the embedded server) | none |
| `SPRING_LDAP_BASE` | LDAP search base | none |
| `SPRING_LDAP_USERNAME` / `SPRING_LDAP_PASSWORD` | bind account used to search for the user | none |
| `TRACKER_LDAP_AD_DOMAIN` | Active Directory without a bind account: bind as `username@domain`, read the own entry by `userPrincipalName` | none |
| `TRACKER_LDAP_USER_FILTER` | filter for the typed username (`{0}`) | `(\|(uid={0})(sAMAccountName={0})(mail={0}))` |
| `TRACKER_LDAP_MAIL_ATTRIBUTE` / `TRACKER_LDAP_NAME_ATTRIBUTE` | attributes with the identity e-mail and the display name | `mail` / `displayName` |
| `TRACKER_ADMINS` | comma-separated e-mails of the bootstrap admins (always admin and active) | none |
| `TRACKER_BREAKGLASS_PASSWORD_HASH` | bcrypt hash for the user `breakglass`; off when unset. `htpasswd -bnBC 12 "" 'pw' \| tr -d ':\n'` | none |

Paths are relative to `backend/` (the working directory of the scripts). Without the demo profile a sync runs when
the interval set in Settings → Jira has passed since the last one (checked every minute).

Sign-in is LDAP with server-side sessions in memory (a restart signs everyone out). Behind HTTPS or a reverse proxy set
`server.servlet.session.cookie.secure=true` and `server.forward-headers-strategy=native` (or `framework`): the login
throttle counts per username and client address, and without forwarded headers every client shares the proxy's address.
See "Sign-in and access" in the top-level README. The Jira token is stored encrypted and never returned by the API.

## Tests

`./mvnw test` runs:

- domain tests (plain JUnit);
- `ServiceReadsTest` / `ServiceWritesTest`: the services on the demo data synced through the real pipeline
  (`@DemoTest`: the demo profile on its own SQLite file; write tests get a fresh context);
- `GoldenTest`: the demo data reproduces the figures of the UI mock (`golden.json`);
- `SyncTest`, `PersistenceTest`, `SyncSchedulerTest` (`@TrackerTest`: an empty database per test), the Jira reader
  against recorded REST v3 responses, the simulated Jira site;
- `ApiReadsTest` / `ApiWritesTest` / `ApiJiraModeTest`: the HTTP API through MockMvc, validation and problem details;
- `BootTest`: the application on a random port: SPA serving, upload limits, and the OpenAPI contract
  (`-Dopenapi.write=true` rewrites it);
- `AuthApiTest` (login, logout, throttle, CSRF, access request), `AccessGateTest` (every `/api` handler declares its access),
  `ApiAdminTest` (users and access requests), `AccessServiceTest` (roles, overrides, admins);
- anken separation: `AnkenMemberSeederTest` (memberships seeded once), `AnkenMembersServiceTest` (members panel, not-members),
  `ScopeServiceTest` (anken scope per user), `AnkenScopeApiTest` (hidden anken answer 404) and `ApiAnkenMembersTest`;
- allocation: `AllocationsTest` (domain), `AllocationServiceTest`, `ApiAllocationTest`; the Timesheet and Planning capacity
  figures are in `ServiceReadsTest` (the demo seeds allocations once, so tests that change one restore it);
- `ArchitectureTest`: layer rules.

`JiraLiveTest` reads a real site and is excluded by default:
`TRACKER_JIRA_URL=… TRACKER_JIRA_EMAIL=… TRACKER_JIRA_TOKEN=… TRACKER_JIRA_EPIC=… ./mvnw test -Dgroups=jira_live -DexcludedGroups=`.
