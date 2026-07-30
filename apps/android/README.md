# DBX Mobile

DBX Mobile is a standalone Android database client. Bundled native JDBC drivers
connect from the phone directly to the database; DBX Web is not required.
Complete connection profiles are encrypted with an AES-GCM key held by Android
Keystore. The WebView only refers to saved profiles by id after creation.

## Architecture

The Vue UI talks only to the local `DirectDatabase` Capacitor plugin through the
`dbx-direct://local` adapter. Database connections, metadata reads, queries,
cancellation, SSL, SSH, and HTTP proxy handling run in the Android native layer.
The app has no DBX Web URL, Web login, mobile Bearer session, or `/api/mobile/*`
server dependency.

The route-shaped strings used inside the TypeScript adapter are local dispatch
keys retained for UI component reuse; they are not HTTP endpoints.

## Requirements

- Node.js 22.13 or newer
- pnpm 10.27.0
- Android Studio with Android SDK 36
- JDK 21 (the Android Studio bundled runtime is supported)

## Web development

```bash
pnpm dev:android
```

The mobile Vite server runs on `http://localhost:5174`. Browser mode can render
and test the interface, but it cannot open database connections because the
drivers and credential vault exist only in the Android native layer.

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

The phone must be able to reach the database host and port. Do not expose a
production database port to the public internet. Prefer a system VPN such as
WireGuard/Tailscale or an enterprise VPN, database IP allowlists, and a dedicated
least-privilege database account.

The connection editor provides independent SSL, SSH, and HTTP tabs:

- SSL can be disabled, required without identity verification for local
  self-signed environments, verified against a trusted CA, or verified against
  both a trusted CA and the database host name.
- SSH uses an in-process local port forward with password or pasted
  OpenSSH/PEM private-key authentication. Pin the server's SHA256 host-key
  fingerprint for production connections.
- HTTP uses an HTTP CONNECT proxy with optional Basic authentication. When SSH
  and HTTP are both enabled, the HTTP proxy is used as the SSH session's
  upstream transport.

Tunnel passwords, private keys, and passphrases are encrypted in the same
Android Keystore-backed profile as database credentials. SOCKS5 and Navicat/PHP
HTTP script tunnels are not supported by the Android direct client.

## Current milestone

The standalone milestone contains:

- bundled PostgreSQL, MySQL/MariaDB, and SQL Server drivers;
- create, edit, test, and delete for direct database profiles;
- driver-native SSL modes, SSH local port forwarding, and HTTP CONNECT proxying;
- AES-GCM encrypted profiles backed by Android Keystore; list/editor responses
  never return saved passwords or connection strings to the WebView;
- local groups, search, favorites, and environment filters;
- database, schema, table/view, column, index, foreign-key, and routine browsing
  through JDBC metadata;
- read-only SQL execution, guarded advanced writes, production-name confirmation,
  statement timeouts, paging, and driver-level cancellation;
- paged table preview with filters and sorting;
- metadata-aware SQL completion, formatting, result export, cell/row copy;
- local query history and a local saved-SQL library.

Oracle, SQLite, DuckDB, ClickHouse, MongoDB, Redis, custom CA/client
certificates, and the remaining DBX drivers are intentionally not advertised in
this milestone. They need Android-compatible native drivers and device tests
before being enabled.

Before producing a signed release, connect an Android device or start an emulator
with API 26 or newer and run `pnpm android:device:test`. Add real-device smoke
coverage for each bundled database driver before publishing.
