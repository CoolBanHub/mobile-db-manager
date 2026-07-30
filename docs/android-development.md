# Android development and rollback

Android work is isolated from the desktop application and developed on
`feature/android-mvp`.

The current Android architecture is standalone: the Vue UI calls the local
`DirectDatabase` Capacitor plugin, and the native layer connects directly to the
database. DBX Web mobile routes, server profiles, and mobile login sessions are
not part of the Android runtime.

## Stable baseline

The annotated tag `android-baseline-v0` points to the stable repository state
before Android development began.

Verify it with:

```bash
git show android-baseline-v0
```

## Commit policy

Each runnable milestone is committed separately. Android changes are merged into
`main` only after build verification and review.

Do not rewrite shared history with `git reset --hard` or force-push. Use `git
revert` so rollbacks remain auditable.

## Roll back one milestone

```bash
git switch feature/android-mvp
git revert <commit-sha>
git push
```

## Restore a branch from the pre-Android baseline

This creates a new recovery branch without changing `main`:

```bash
git switch -c recovery/android-baseline android-baseline-v0
```

## Remove Android after a merge

Revert the Android merge commit:

```bash
git switch main
git pull --ff-only
git revert -m 1 <merge-commit-sha>
git push
```

## Build rollback

Milestone tags must use `android-mvp-vN` and point to a commit whose debug APK
passes CI. A previous tag can always be checked out and rebuilt:

```bash
git switch --detach android-mvp-v1
pnpm install --frozen-lockfile
pnpm android:build:debug
```

Available recovery points:

- `android-baseline-v0`: repository state before Android development
- `android-mvp-v1`: runnable Android shell and server profile
- `android-mvp-v2`: mobile login and display-safe connection catalog

Device configuration formats must remain versioned and backward compatible.
Legacy `dbx-mobile.server-profile.v1` data belongs to the retired DBX
Web-dependent prototype and is not read by the standalone client.
