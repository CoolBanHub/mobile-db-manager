import { spawnSync } from "node:child_process";
import { changedFileCommands, collectChangedFiles } from "./review-check-files.mjs";

const FAST_RUST_FEATURES = [
  "dbx/mq-admin",
  "dbx/sqlite-sqlcipher",
  "dbx-core/mq-admin",
  "dbx-core/sqlite-sqlcipher",
  "dbx-web/mq-admin",
  "dbx-web/sqlite-sqlcipher",
].join(",");

const args = process.argv.slice(2);
const full = args.includes("--full");
const baseIndex = args.indexOf("--base");

if (baseIndex !== -1 && !args[baseIndex + 1]) {
  console.error("--base requires a Git ref");
  process.exit(2);
}

const explicitBase = baseIndex === -1 ? undefined : args[baseIndex + 1];

function run(program, commandArgs, options = {}) {
  const nodeMajor = Number.parseInt(process.versions.node.split(".")[0], 10);
  const env = { ...process.env };
  const noProxy = new Set(
    [env.NO_PROXY, env.no_proxy]
      .filter(Boolean)
      .flatMap((value) => value.split(","))
      .map((value) => value.trim())
      .filter(Boolean),
  );

  // Local test servers must never be routed through a developer's HTTP or
  // macOS system proxy; doing so turns expected connection errors into 502s.
  noProxy.add("127.0.0.1");
  noProxy.add("localhost");
  env.NO_PROXY = [...noProxy].join(",");
  env.no_proxy = env.NO_PROXY;

  // Node 25+ exposes an experimental localStorage global that shadows
  // happy-dom's implementation unless a persistence file is configured.
  // CI uses Node 22, so disable the experiment locally to keep test semantics
  // aligned with CI rather than writing test state to disk.
  if (program === "pnpm" && nodeMajor >= 25) {
    env.NODE_OPTIONS = [env.NODE_OPTIONS, "--no-experimental-webstorage"].filter(Boolean).join(" ");
  }

  const result = spawnSync(program, commandArgs, {
    cwd: process.cwd(),
    env,
    encoding: "utf8",
    stdio: options.capture ? "pipe" : "inherit",
  });

  if (result.error) {
    if (!options.allowFailure) {
      console.error(`${program} is unavailable: ${result.error.message}`);
      process.exit(1);
    }
    return { status: 1, stdout: "", stderr: result.error.message };
  }

  if (result.status !== 0 && !options.allowFailure) {
    process.exit(result.status ?? 1);
  }

  return result;
}

function git(commandArgs, allowFailure = false) {
  return run("git", commandArgs, { capture: true, allowFailure });
}

function validRef(ref) {
  return git(["rev-parse", "--verify", "--quiet", ref], true).status === 0;
}

function resolveBase() {
  if (explicitBase) {
    if (!validRef(explicitBase)) {
      console.error(`Unknown base ref: ${explicitBase}`);
      process.exit(2);
    }
    return explicitBase;
  }

  for (const candidate of ["origin/main", "main", "HEAD~1"]) {
    if (validRef(candidate)) return candidate;
  }

  return undefined;
}

function changedFiles(base) {
  const outputs = [];
  for (const commandArgs of changedFileCommands(base)) {
    const result = git(commandArgs, true);
    if (result.status === 0) {
      outputs.push(result.stdout);
    }
  }
  return collectChangedFiles(outputs);
}

function matches(file, prefixes, exact = []) {
  return exact.includes(file) || prefixes.some((prefix) => file.startsWith(prefix));
}

const base = resolveBase();
const files = full ? [] : changedFiles(base);
const rootInputs = ["package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml"];
const reviewCheckInputs = [
  "scripts/review-check.mjs",
  "scripts/review-check-files.mjs",
  "scripts/review-check-files.test.mjs",
];
const allAreas = full || files.some((file) => reviewCheckInputs.includes(file));

const areas = {
  desktop:
    allAreas ||
    files.some((file) =>
      matches(file, ["apps/desktop/"], [...rootInputs, ".oxfmtrc.json", "scripts/run-check.mjs"]),
    ),
  android: allAreas || files.some((file) => matches(file, ["apps/android/"], rootInputs)),
  packages:
    allAreas ||
    files.some((file) =>
      matches(file, ["packages/cli/", "packages/mcp-server/", "crates/dbx-cli/", "crates/dbx-mcp/"], [
        ...rootInputs,
        "Cargo.toml",
        "Cargo.lock",
      ]),
    ),
  rust:
    allAreas ||
    files.some((file) =>
      matches(file, ["crates/", "src-tauri/"], [
        "Cargo.toml",
        "Cargo.lock",
        "rustfmt.toml",
        "clippy.toml",
      ]),
    ),
};

const tasks = [];

if (allAreas) {
  tasks.push(["Review-check regression tests", "pnpm", ["test:review-check"]]);
}

if (areas.desktop) {
  tasks.push(["Desktop format, lint, typecheck and tests", "pnpm", ["check"]]);
}
if (areas.android) {
  tasks.push([
    "Android unit tests",
    "pnpm",
    ["--filter", "@dbx-app/android", "test"],
  ]);
  tasks.push([
    "Android typecheck and web build",
    "pnpm",
    ["--filter", "@dbx-app/android", "build"],
  ]);
}
if (areas.packages) {
  tasks.push(["Node package tests", "pnpm", ["test:packages"]]);
}
if (areas.rust) {
  tasks.push(["Rust formatting", "cargo", ["fmt", "--all", "--", "--check"]]);
  tasks.push([
    "Rust clippy (PR fast feature set)",
    "cargo",
    [
      "clippy",
      "--workspace",
      "--locked",
      "--all-targets",
      "--no-default-features",
      "--features",
      FAST_RUST_FEATURES,
      "--",
      "-D",
      "warnings",
    ],
  ]);
  tasks.push([
    "Rust tests (PR fast feature set)",
    "cargo",
    [
      "test",
      "--workspace",
      "--locked",
      "--no-default-features",
      "--features",
      FAST_RUST_FEATURES,
    ],
  ]);
}

console.log(`Review base: ${base ?? "(none)"}`);
console.log(full ? "Scope: full repository" : `Changed files: ${files.length}`);

if (tasks.length === 0) {
  console.log("No deterministic checks are mapped to the changed files.");
  process.exit(0);
}

for (const [name, program, commandArgs] of tasks) {
  console.log(`\n==> ${name}`);
  run(program, commandArgs);
}

console.log("\nAll selected review checks passed.");
