import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";

const required = [
  "DBX_ANDROID_KEYSTORE",
  "DBX_ANDROID_STORE_PASSWORD",
  "DBX_ANDROID_KEY_ALIAS",
  "DBX_ANDROID_KEY_PASSWORD",
];
const missing = required.filter((name) => !process.env[name]);
if (missing.length > 0) {
  console.error(`Missing Android release signing variables: ${missing.join(", ")}`);
  process.exit(1);
}
if (!existsSync(process.env.DBX_ANDROID_KEYSTORE)) {
  console.error(`Android release keystore does not exist: ${process.env.DBX_ANDROID_KEYSTORE}`);
  process.exit(1);
}

for (const [command, args, cwd] of [
  ["pnpm", ["sync"], process.cwd()],
  [process.platform === "win32" ? "gradlew.bat" : "./gradlew", [":app:connectedDebugAndroidTest"], new URL("../android/", import.meta.url)],
  [process.platform === "win32" ? "gradlew.bat" : "./gradlew", ["bundleRelease"], new URL("../android/", import.meta.url)],
]) {
  const result = spawnSync(command, args, {
    cwd,
    env: process.env,
    stdio: "inherit",
    shell: process.platform === "win32",
  });
  if (result.status !== 0) process.exit(result.status ?? 1);
}
