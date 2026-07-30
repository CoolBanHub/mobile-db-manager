# DBX for Android

DBX for Android is a standalone mobile database client. It connects from the
device directly to a database through bundled native drivers—no DBX Web
deployment, account, mobile API, or Bearer session is required.

[简体中文](README.zh-CN.md) · [Android developer guide](apps/android/README.md) ·
[Releases](https://github.com/t8y2/dbx/releases/latest)

> The Android app is one product in the DBX repository. The desktop, Docker/Web,
> CLI, and MCP products remain available, but their features are not implied to
> be present in the Android app.

## What the Android app can do

### Direct connections

- Create, edit, test, search, group, favorite, and delete local connections.
- Label connections as development, staging, or production.
- Connect directly to PostgreSQL, MySQL/MariaDB, and SQL Server for relational
  browsing and SQL execution.
- Create and test standalone Redis connections.
- Create and test MongoDB host/port or URI connections.
- Use TLS, SSH local port forwarding, and HTTP CONNECT proxies where supported.

Redis key browsing and MongoDB document browsing are not yet implemented on
Android. Redis Sentinel and Cluster modes are also not supported. A MongoDB URI
already contains its routing information and therefore cannot be combined with
the app's SSH or HTTP tunnel.

### Schema and data browsing

For PostgreSQL, MySQL/MariaDB, and SQL Server, the Android app can browse:

- databases and schemas;
- tables and views;
- columns and primary keys;
- indexes and foreign keys;
- stored procedures exposed by the JDBC metadata driver.

The table browser supports pagination, column sorting, filters, full cell
details, column resizing, and generated `SELECT` statements. Rows can be
inserted, deleted, or edited when the connection is writable and the table has a
primary key.

### SQL workspace

- Metadata-aware table and column suggestions.
- SQL formatting and named JSON parameters.
- Safe read-only mode and explicitly confirmed advanced mode.
- Production write confirmation using the complete connection name.
- Paginated results, query cancellation, and PostgreSQL/MySQL `EXPLAIN`.
- Local `.sql` file opening up to 1 MiB.
- Result viewing, column auto-fit, simple charts, cell inspection, and copy
  actions.
- Export the current result page as CSV, JSON, Markdown, or XLSX and open the
  Android share sheet.

The Android app does not currently include the desktop/Web AI SQL assistant,
manual transaction sessions, server-side background exports, or multi-statement
script execution.

### Local history and saved SQL

- Keeps up to 1,000 query history entries on the device.
- Search and filter history by connection, status, and date.
- Save SQL in a local folder hierarchy.
- Rename, move, reopen, and delete saved SQL and folders.

History and saved SQL are local to the Android app and are not synchronized
through DBX Web.

## Security model

Complete connection profiles are stored in the app's private storage. Database
passwords, connection strings, proxy credentials, SSH passwords, and private
keys are encrypted with an Android Keystore-backed AES-GCM key.

The WebView calls the native database plugin with a connection ID. Reading a
connection for editing does not return stored passwords, private keys, or the
complete saved connection string.

For production use:

- reach private databases through WireGuard, Tailscale, or an enterprise VPN;
- do not expose production database ports directly to the public internet;
- use a dedicated least-privilege database account;
- enable production protection for connections that permit writes;
- prefer certificate and hostname verification for TLS;
- pin the SSH server's SHA-256 host key fingerprint.

## Supported databases

| Database | Connection test | Metadata and SQL | Data editing |
| --- | --- | --- | --- |
| PostgreSQL | Yes | Yes | Yes, with a primary key |
| MySQL / MariaDB | Yes | Yes | Yes, with a primary key |
| SQL Server | Yes | Yes | Yes, with a primary key |
| Redis Standalone | Yes | Not yet | No |
| MongoDB | Yes | Not yet | No |

## Install

Android packages are published through the
[DBX releases](https://github.com/t8y2/dbx/releases/latest) when available.
Android 8.0 (API 26) or newer is required.

When installing an APK outside an app store, verify that it came from the
official `t8y2/dbx` release page before allowing installation from an unknown
source.

## Build from source

Requirements:

- Node.js 22.13 or newer;
- pnpm 10.27.0;
- JDK 21;
- Android SDK 36;
- Android 8.0 (API 26) or newer device or emulator.

Install dependencies and build a debug APK:

```bash
pnpm install --frozen-lockfile
pnpm android:build:debug
```

The APK is written to:

```text
apps/android/android/app/build/outputs/apk/debug/app-debug.apk
```

Other common commands:

```bash
pnpm android:sync               # build WebView assets and run Capacitor sync
pnpm android:open               # open the project in Android Studio
pnpm --filter @dbx-app/android test
pnpm android:device:test        # requires a connected device or emulator
```

Use JDK 21 explicitly if the system default is an older Java runtime. More
information about signing, release builds, and the Android-compatible driver
packaging is available in the
[Android developer guide](apps/android/README.md).

## Other DBX products

This repository also contains:

- the Tauri desktop application for macOS, Windows, and Linux;
- the Docker/Web self-hosted application;
- [`@dbx-app/cli`](packages/cli/README.md);
- [`@dbx-app/mcp-server`](packages/mcp-server/README.md);
- optional database agents and JDBC plugins.

These products have a broader database and feature matrix than the standalone
Android client.

## License and community

DBX is licensed under the [Apache License 2.0](LICENSE).

- [GitHub issues](https://github.com/t8y2/dbx/issues)
- [QQ group: 1087880322](https://qm.qq.com/cgi-bin/qm/qr?k=&group_code=1087880322)
- [Discord](https://discord.gg/W7NyVDRt6a)
