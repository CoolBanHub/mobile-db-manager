# Mobile DB Manager

[![Android Release APK](https://github.com/CoolBanHub/mobile-db-manager/actions/workflows/android-release.yml/badge.svg)](https://github.com/CoolBanHub/mobile-db-manager/actions/workflows/android-release.yml)

Language: English | [Simplified Chinese](README.md)

Mobile DB Manager is an Android database manager and mobile SQL client. It connects
directly from the phone to PostgreSQL, MySQL/MariaDB, SQL Server, Redis, MongoDB,
and etcd through native Android drivers. No backend service, desktop proxy,
account system, or mobile API gateway is required.

## Search Keywords

Android database manager, mobile database client, Android SQL client, mobile SQL
editor, PostgreSQL Android client, MySQL Android client, MariaDB client, SQL
Server Android client, Redis browser, MongoDB mobile client, etcd browser,
database browser, Capacitor Android app, Vue database tool, JDBC mobile client,
SSH tunnel, HTTP CONNECT proxy.

## Features

- Create, edit, test, search, favorite, group, and delete database connections on
  the device.
- Store database passwords, connection strings, proxy passwords, SSH passwords,
  and private keys with Android Keystore AES-GCM encryption.
- Browse metadata, run SQL queries, and edit table data for PostgreSQL,
  MySQL/MariaDB, and SQL Server.
- Browse Redis Standalone keys, inspect values, edit supported data types, and
  manage TTL.
- Browse MongoDB databases, collections, documents, and edit documents.
- Browse etcd v3 JSON Gateway prefixes, inspect key details, and write single
  keys.
- Use TLS, SSH local port forwarding, and HTTP CONNECT proxy tunnels.
- Format SQL, use named JSON parameters, autocomplete metadata, cancel queries,
  store query history, save local SQL snippets, and share exported results.

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

Frontend direct database APIs live in `src/lib/direct/`:

- `connections.ts`: connection list, save, delete, and test.
- `metadata.ts`: databases, schemas, tables, columns, indexes, and other
  metadata.
- `query.ts`: SQL execution, cancellation, and Explain.
- `tableData.ts`: table browsing and row-level create/update/delete.
- `redis.ts`, `mongo.ts`, `etcd.ts`: database-specific browser and write actions.
- `history.ts`, `savedSql.ts`: local query history and saved SQL library.
- `native.ts`, `localStore.ts`: Capacitor native bridge and localStorage helpers.

Android native direct-connect code lives in
`android/app/src/main/java/com/coolbanhub/mobiledbmanager/`:

- `DirectDatabasePlugin.java`: Capacitor entry point; parameter reading, safety
  gates, and dispatch only.
- `DirectJdbcConnectionFactory.java`: JDBC URL generation, driver loading, SSL,
  and tunnel routing for PostgreSQL, MySQL/MariaDB, and SQL Server.
- `DirectJdbcMetadata.java`: JDBC metadata loading.
- `DirectJdbcQueryRunner.java`: SQL execution, pagination, result serialization,
  and cancellation.
- `DirectRedisActions.java`, `DirectMongoActions.java`, `DirectEtcdActions.java`:
  UI action mapping and write protection.
- `DirectRedisConnection.java`, `DirectMongoConnection.java`,
  `DirectEtcdConnection.java`: low-level database clients.
- `DirectTransport.java`: SSH forwarding and HTTP CONNECT tunnels.
- `DirectConnectionStore.java`, `SecureVaultStore.java`: connection config and
  secret storage.

When adding a new database type, add a matching frontend API file, native action
file, and low-level connection client. Do not put new database logic back into
`DirectDatabasePlugin.java`.

## GitHub Release

Push a `release/v*` tag to trigger GitHub Actions. The workflow builds a Debug APK
and uploads it to the matching GitHub Release.

```bash
git tag release/v0.1.0
git push origin release/v0.1.0
```

You can also run the `Android Release APK` workflow manually from GitHub Actions
and provide a tag such as `release/v0.1.0`.

## Release Signing

Set these environment variables before creating a signed release build:

- `MOBILE_DB_MANAGER_ANDROID_KEYSTORE`
- `MOBILE_DB_MANAGER_ANDROID_STORE_PASSWORD`
- `MOBILE_DB_MANAGER_ANDROID_KEY_ALIAS`
- `MOBILE_DB_MANAGER_ANDROID_KEY_PASSWORD`
- `MOBILE_DB_MANAGER_ANDROID_VERSION_CODE`
- `MOBILE_DB_MANAGER_ANDROID_VERSION_NAME`

Then run:

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
