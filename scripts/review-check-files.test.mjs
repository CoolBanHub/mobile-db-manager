import assert from "node:assert/strict";
import test from "node:test";

import {
  REVIEW_DIFF_FILTER,
  changedFileCommands,
  collectChangedFiles,
} from "./review-check-files.mjs";

test("incremental review includes deleted tracked files", () => {
  assert.equal(REVIEW_DIFF_FILTER, "ACDMR");
  assert.deepEqual(changedFileCommands("origin/main")[0], [
    "diff",
    "--name-only",
    "--diff-filter=ACDMR",
    "origin/main...HEAD",
  ]);
});

test("incremental review asks Git for untracked non-ignored files", () => {
  assert.deepEqual(changedFileCommands(undefined).at(-1), [
    "ls-files",
    "--others",
    "--exclude-standard",
  ]);
});

test("incremental review merges committed, working tree, staged, and untracked paths", () => {
  assert.deepEqual(
    collectChangedFiles([
      "crates/dbx-core/src/deleted.rs\n",
      "apps/android/src/App.vue\n",
      "apps/android/src/App.vue\nscripts/new-check.mjs\n",
    ]),
    [
      "apps/android/src/App.vue",
      "crates/dbx-core/src/deleted.rs",
      "scripts/new-check.mjs",
    ],
  );
});
