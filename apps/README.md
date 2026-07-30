# Apps

Runnable DBX applications live here.

## Directories

- `desktop/` - the Vue frontend used by the Tauri desktop app and the Docker/web build.
- `android/` - the standalone Capacitor Android client. It connects directly
  through bundled native JDBC drivers and does not call DBX Web APIs.

The Tauri native shell remains in `src-tauri/` because that is the conventional Tauri project location used by the existing build and release tooling.
