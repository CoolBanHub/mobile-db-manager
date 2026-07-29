# DBX Mobile

DBX Mobile is the Android client for a DBX Web server. The Android app owns the
mobile interaction layer; database drivers, credentials, tunnels, and long-running
jobs remain on the DBX server.

## Requirements

- Node.js 22.13 or newer
- pnpm 10.27.0
- Android Studio with Android SDK 36
- JDK 21 (the Android Studio bundled runtime is supported)

## Web development

```bash
pnpm dev:android
```

The mobile Vite server runs on `http://localhost:5174`.

## Android development

```bash
pnpm android:sync
pnpm android:open
```

Build a debug APK without opening Android Studio:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" pnpm android:build:debug
```

The APK is written to:

```text
apps/android/android/app/build/outputs/apk/debug/app-debug.apk
```

Build an unsigned release bundle for CI/build validation:

```bash
pnpm android:build:bundle
```

For a signed Play-ready bundle, set `DBX_ANDROID_KEYSTORE`,
`DBX_ANDROID_STORE_PASSWORD`, `DBX_ANDROID_KEY_ALIAS`, and
`DBX_ANDROID_KEY_PASSWORD`, then run:

```bash
DBX_ANDROID_VERSION_CODE=2 DBX_ANDROID_VERSION_NAME=0.1.1 pnpm android:release
```

The signed bundle is written to
`apps/android/android/app/build/outputs/bundle/release/app-release.aab`. Keystore
files and passwords are read only from the environment and must not be committed.

## Network policy

Release builds reject cleartext HTTP. Debug builds allow HTTP so a developer can
connect to a DBX server on the local network. Production server profiles should
always use HTTPS. Android API traffic uses the native per-profile transport, which
supports an unauthenticated HTTP or SOCKS5 proxy, request cancellation/timeouts,
SHA-256 leaf-certificate fingerprints or `sha256/BASE64` SPKI pins, and an explicit
opt-in for invalid certificates. A configured pin is still enforced when invalid
certificates are allowed.

## Current milestone

The current milestone contains:

- the Android shell, server profile validation, and native Gradle project;
- password login through a dedicated 30-day mobile Bearer session;
- a 30-day device token encrypted with Android Keystore, with optional biometric
  or system-credential unlock (web development keeps only a tab-scoped token);
- multiple named DBX server profiles with quick switching and per-server request
  timeout, proxy, certificate pinning, and invalid-certificate settings;
- a display-safe connection catalog with create, edit, test, and delete workflows;
- local connection groups, search, favorites, and development/staging/production
  filters;
- connection-level timeout, read-only/production protection, proxy, TLS, CA, and
  client-certificate settings. Secret values are write-only from mobile and remain
  in the DBX Server secret store.
- drill-down browsing from database to Schema, table/view, and column metadata,
  including indexes, foreign keys, constraints, triggers, DDL/view definitions,
  and function/stored-procedure source;
- paged table loading for large database catalogs.
- a server-enforced read-only SQL workbench limited to one statement, 50-row
  server pages (up to a 100,000-row offset), a 2 MiB response, a 30-second
  statement timeout, and a 35-second overall server budget across supported SQL
  databases, with dialect-aware write blocking;
- a visible query cancellation action backed by server execution IDs; request
  disconnects and client timeouts also trigger driver cancellation and cleanup;
- metadata-aware SQL completion, formatting, table SELECT generation, and
  field-driven WHERE/AND condition generation;
- horizontally scrollable results with server-side next/previous page loading,
  adjustable and auto-fit column widths, full cell details, and cell/row copy;
- CSV, JSON, Markdown, and XLSX result export with spreadsheet-formula
  neutralization where applicable, browser download fallback, and Android native
  file sharing from the app cache;
- PostgreSQL, MySQL, SQL Server, Oracle, SQLite, and ClickHouse table-data preview
  with dialect-aware, server-enforced read-only pagination, adjustable page sizes,
  field filters, field sorting with stable primary-key tie-breaking, and
  metadata-driven SELECT templates that open directly in the query workbench;
  table preview also includes full cell details, cell/row copy, adjustable and
  auto-fit column widths, and current-page CSV/JSON/Markdown/XLSX export;
- server-synchronized query history, including successful and failed mobile queries;
- a server-synchronized saved SQL library with nested folders, create, open, overwrite,
  rename, move, recursive folder deletion, search, and favorite-from-history actions.

Changing the DBX management password revokes every browser and mobile session.
Explicit logout and server switching keep the local token until the server
confirms revocation, so a failed network request can be retried.
Authentication checks, login, and logout have an 8-second client-side timeout
that covers both connection setup and response processing.

Before producing a signed release, connect an Android device or start an emulator
with API 26 or newer and run `pnpm android:device:test`. The suite exercises the
Android Keystore vault, secure-unlock capability, mobile login, connection editing,
query cancellation, and file sharing through the real Capacitor WebView and native
plugins. `pnpm android:release` runs the same connected-device gate automatically
before creating the signed bundle. Use `pnpm android:device:test:build` when only
compiling the instrumentation APK in CI.

Database passwords, proxy passwords, connection strings, private-key contents,
and initialization scripts are never returned by the mobile connection catalog
endpoint. The authenticated editor endpoint returns only safe metadata, usernames,
server-side certificate paths, and boolean “credential configured” indicators.
