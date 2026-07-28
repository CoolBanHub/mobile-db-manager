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

## Network policy

Release builds reject cleartext HTTP. Debug builds allow HTTP so a developer can
connect to a DBX server on the local network. Production server profiles should
always use HTTPS.

## Current milestone

The current milestone contains:

- the Android shell, server profile validation, and native Gradle project;
- password login through a dedicated 30-day mobile Bearer session;
- an in-memory device token that is discarded when the app process restarts;
- a connection catalog containing display-safe fields only.
- drill-down browsing from database to Schema, table/view, and column metadata;
- paged table loading for large database catalogs.

Changing the DBX management password revokes every browser and mobile session.
Explicit logout and server switching keep the local token until the server
confirms revocation, so a failed network request can be retried.
Authentication checks, login, and logout have an 8-second client-side timeout
that covers both connection setup and response processing.

Database usernames, passwords, connection strings, certificates, client keys,
transport configuration, and initialization scripts are never returned by the
mobile connection catalog endpoint.

The next milestone will add the mobile SQL editor and paged query results.
