# Mobile DB Manager

[![Android Release APK](https://github.com/CoolBanHub/mobile-db-manager/actions/workflows/android-release.yml/badge.svg)](https://github.com/CoolBanHub/mobile-db-manager/actions/workflows/android-release.yml)

Language: English | [Simplified Chinese](README.md)

Mobile DB Manager is an Android database manager and mobile SQL client. It connects
directly from the phone to PostgreSQL, MySQL/MariaDB, SQL Server, Redis, MongoDB,
and etcd through native Android drivers. No backend service, desktop proxy,
account system, or mobile API gateway is required.

## App Preview

These screenshots come from the app running in an Android emulator. The etcd data
is served by a local Docker test container.

| Connections | etcd keyspace | Key details |
| --- | --- | --- |
| <img src="docs/screenshots/connections.png" width="280" alt="Mobile DB Manager connection manager"> | <img src="docs/screenshots/etcd-browser.png" width="280" alt="Mobile DB Manager etcd keyspace browser"> | <img src="docs/screenshots/etcd-key-detail.png" width="280" alt="Mobile DB Manager etcd key details"> |

## Search Keywords

Android database manager, mobile database client, Android SQL client, mobile SQL
editor, PostgreSQL Android client, MySQL Android client, MariaDB client, SQL
Server Android client, Redis browser, MongoDB mobile client, etcd browser,
database browser, Capacitor Android app, Vue database tool, JDBC mobile client,
SSH tunnel, HTTP CONNECT proxy.

## Features

- Create, edit, test, search, favorite, group, and delete database connections on
  the device. Classify and filter connections with consistent pale-blue
  Development, Staging, and Production environment labels; new connections
  default to Development.
- Pre-save and manage reusable SSH jump-host profiles from Settings. Database
  connections reference only a profile ID; database passwords, connection
  strings, proxy passwords, SSH passwords, and private keys are protected with
  Android Keystore AES-GCM encryption and never returned to the WebView.
- Open interface density, SSH jump hosts, privacy and security, and About as
  focused subpages from the Settings index. Android Back returns to the Settings
  index first, and the app uses a fixed light interface.
- Browse metadata, run SQL queries, and edit table data for PostgreSQL,
  MySQL/MariaDB, and SQL Server. Inserts, updates, and deletes are staged for
  review, then executed in one JDBC transaction with full rollback on failure.
  Visually design new table columns, indexes, foreign keys, checks, options, and
  comments before previewing the SQL.
- Diagnose PostgreSQL, MySQL/MariaDB, and SQL Server sessions, running SQL,
  long transactions, lock waits, and blocking chains. Query cancellation and
  session termination remain protected by read-only and Production safeguards.
- Browse Redis Standalone keys, inspect values, edit supported data types, and
  manage TTL.
- Browse MongoDB databases, collections, documents, and edit documents.
- Browse etcd v3 JSON Gateway prefixes, inspect key details, and write single
  keys.
- Use TLS, SSH local port forwarding, and HTTP CONNECT proxy tunnels.
- Format SQL, use named JSON parameters, autocomplete metadata, cancel queries,
  store query history, save local SQL snippets, and share exported results.
  Advanced SQL is always guarded as potentially writable: read-only connections
  cannot run it, and Production requires one-shot confirmation plus the full
  connection name.
- Check GitHub Releases for the latest `release/v*` APK and download updates
  through Android's system download manager.

## Database Support

| Database | Connection Test | Browse / Query | Write |
| --- | --- | --- | --- |
| PostgreSQL | Supported | SQL and table data | Supported, primary key required |
| MySQL / MariaDB | Supported | SQL and table data | Supported, primary key required |
| SQL Server | Supported | SQL and table data | Supported, primary key required |
| Redis Standalone | Supported | Key browser | Supported fixed actions |
| MongoDB | Supported | Collections and documents | Insert, replace, delete |
| etcd | Supported | Prefixes and key details | put/delete |

Redis Sentinel and Redis Cluster are not supported yet. MongoDB URI already
contains routing information, so it cannot be combined with the app's SSH or HTTP
tunnel settings. etcd requires the v3 JSON Gateway.

## Development

Requirements:

- Node.js 22.13 or newer
- pnpm 10.27.0
- JDK 21
- Android SDK 36
- Android 8.0 (API 26) or newer device/emulator

Install dependencies:

```bash
pnpm install --frozen-lockfile
```

Run frontend unit tests:

```bash
pnpm test
```

Build WebView assets and sync the Capacitor Android project:

```bash
pnpm sync
```

Build Debug APK:

```bash
pnpm android:build:debug
```

APK output:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Open Android Studio:

```bash
pnpm open
```

Run Android unit tests:

```bash
cd android
./gradlew :app:testDebugUnitTest
```

Run device instrumentation tests after connecting a device:

```bash
pnpm android:device:test
```

## Project Structure

For a detailed feature-to-file index, see [Code Map](docs/CODEMAP.md).

Frontend pages live in `src/features/`:

- `connections/`: connection management.
- `settings/`: local preferences and reusable SSH profile management.
- `query/`: SQL workbench, history, and saved SQL.
- `browse/relational/`: relational metadata and table data browser.
- `browse/redis/`, `browse/mongo/`, `browse/etcd/`: database-specific browsers.
- `update/`: GitHub Release update banner and APK download entry point.

Frontend direct database APIs live in `src/lib/direct/`:

- `connections.ts`: connection list, save, delete, and test.
- `sshProfiles.ts`: reusable SSH profile list, save, and delete.
- `metadata.ts`: databases, schemas, tables, columns, indexes, and other
  metadata.
- `query.ts`: SQL execution, cancellation, and Explain.
- `tableData.ts`: table browsing and row-level create/update/delete.
- `redis.ts`, `mongo.ts`, `etcd.ts`: database-specific browser and write actions.
- `history.ts`, `savedSql.ts`: local query history and saved SQL library.
- `native.ts`, `localStore.ts`: Capacitor native bridge and localStorage helpers.

App update APIs live in `src/lib/appUpdate.ts`. They call the Android
`AppUpdate` plugin and stay separate from the direct database APIs.

Android native direct-connect code lives in
`android/app/src/main/java/com/coolbanhub/mobiledbmanager/`:

- `DirectDatabasePlugin.java`: Capacitor entry point; parameter reading, safety
  gates, and dispatch only.
- `DirectJdbcConnectionFactory.java`: JDBC URL generation, driver loading, SSL,
  and tunnel routing for PostgreSQL, MySQL/MariaDB, and SQL Server.
- `DirectJdbcMetadata.java`: JDBC metadata loading.
- `DirectJdbcQueryRunner.java`: SQL execution, pagination, result serialization,
  and cancellation.
- `DirectJdbcTableTransaction.java`: validates live table metadata and atomically
  commits table mutations with parameterized statements.
- `DirectJdbcDiagnostics.java`: fixed session, long-transaction, lock-wait, and
  blocking-chain diagnostic actions.
- `DirectRedisActions.java`, `DirectMongoActions.java`, `DirectEtcdActions.java`:
  UI action mapping and write protection.
- `DirectRedisConnection.java`, `DirectMongoConnection.java`,
  `DirectEtcdConnection.java`: low-level database clients.
- `DirectTransport.java`: SSH forwarding and HTTP CONNECT tunnels.
- `DirectConnectionStore.java`, `DirectSshProfileStore.java`, and
  `SecureVaultStore.java`: connection profiles, reusable SSH profiles, and
  secret storage.
- `AppUpdatePlugin.java`, `AppReleaseUpdateService.java`, `AppUpdateVersion.java`:
  GitHub Release checks, APK downloads, and version comparison.

When adding a new database type, add a matching frontend API file, native action
file, and low-level connection client. Do not put new database logic back into
`DirectDatabasePlugin.java`.

## GitHub Release

Push a `release/v*` tag to trigger GitHub Actions. The workflow builds a signed
Release APK and uploads it to the matching GitHub Release.

Every version must include `docs/releases/vMAJOR.MINOR.PATCH.md`. The workflow
validates this file and publishes it as the complete GitHub Release notes.

```bash
git tag release/v0.1.0
git push origin release/v0.1.0
```

You can also run the `Android Release APK` workflow manually from GitHub Actions
and provide a tag such as `release/v0.1.0`.

The workflow derives Android `versionName` and monotonic `versionCode` values
from `release/vMAJOR.MINOR.PATCH`. In-app update checks use the same rule. Android
can only install an APK update over an existing app when every release uses the
same signing certificate.

## Release Signing

GitHub Actions APK releases require these repository secrets:

- `MOBILE_DB_MANAGER_ANDROID_KEYSTORE_BASE64`
- `MOBILE_DB_MANAGER_ANDROID_STORE_PASSWORD`
- `MOBILE_DB_MANAGER_ANDROID_KEY_ALIAS`
- `MOBILE_DB_MANAGER_ANDROID_KEY_PASSWORD`

`MOBILE_DB_MANAGER_ANDROID_KEYSTORE_BASE64` is the base64-encoded keystore file:

```bash
base64 < android/signing/mobile-db-manager-release.jks | tr -d '\n'
```

Set these environment variables before creating a local signed release build:

- `MOBILE_DB_MANAGER_ANDROID_KEYSTORE`
- `MOBILE_DB_MANAGER_ANDROID_STORE_PASSWORD`
- `MOBILE_DB_MANAGER_ANDROID_KEY_ALIAS`
- `MOBILE_DB_MANAGER_ANDROID_KEY_PASSWORD`
- `MOBILE_DB_MANAGER_ANDROID_VERSION_CODE`
- `MOBILE_DB_MANAGER_ANDROID_VERSION_NAME`

Build a signed APK:

```bash
pnpm android:build:release-apk
```

APK output:

```text
android/app/build/outputs/apk/release/app-release.apk
```

Build a signed AAB:

```bash
pnpm android:release
```

Release bundle output:

```text
android/app/build/outputs/bundle/release/app-release.aab
```

Keystores and passwords must be provided through environment variables and must
not be committed to the repository.

## Security Notes

- Use WireGuard, Tailscale, or a company VPN for private database networks.
- Do not expose production database ports directly to the public internet.
- Use dedicated least-privilege database accounts.
- Enable production protection for writable production connections.
- Prefer TLS certificate and hostname verification. Use encryption-only mode only
  in controlled self-signed environments.
- Pin the SSH server SHA256 host key fingerprint when using SSH tunnels.

## Android Driver Compatibility

Android does not provide the full Java SE `java.lang.management` and JMX APIs.
The build script creates Android-compatible driver jars for selected drivers:

- PostgreSQL: replaces result-buffer memory detection.
- MySQL / MariaDB: removes connection pool JMX registration.
- MongoDB: removes JMX registration and provides Android-missing SASL contracts.

SQL Server uses jTDS because it is compatible with Android ART.
