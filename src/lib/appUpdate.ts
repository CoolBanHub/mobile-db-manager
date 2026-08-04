import { Capacitor, registerPlugin } from "@capacitor/core";

interface NativeResult<T> {
  value: T;
}

export interface AppUpdateInfo {
  currentVersion: string;
  currentVersionCode: number;
  latestTag: string;
  latestVersion: string;
  latestVersionCode: number;
  releaseUrl: string;
  apkDownloadUrl: string;
  apkName: string;
  apkSize: number;
  publishedAt: string;
  hasUpdate: boolean;
}

export interface AppUpdateDownload {
  downloadId: number;
  fileName: string;
  openedExternal: boolean;
}

interface AppUpdatePlugin {
  check(): Promise<NativeResult<AppUpdateInfo>>;
  downloadLatest(options?: { url?: string; fileName?: string }): Promise<NativeResult<AppUpdateDownload>>;
}

const AppUpdate = registerPlugin<AppUpdatePlugin>("AppUpdate");

export function supportsAppUpdate() {
  return Capacitor.isNativePlatform();
}

export async function checkLatestAppUpdate() {
  if (!supportsAppUpdate()) {
    return {
      currentVersion: "web",
      currentVersionCode: 0,
      latestTag: "",
      latestVersion: "",
      latestVersionCode: 0,
      releaseUrl: "",
      apkDownloadUrl: "",
      apkName: "",
      apkSize: 0,
      publishedAt: "",
      hasUpdate: false,
    } satisfies AppUpdateInfo;
  }
  return (await AppUpdate.check()).value;
}

export async function downloadLatestAppUpdate(info?: Pick<AppUpdateInfo, "apkDownloadUrl" | "apkName">) {
  if (!supportsAppUpdate()) throw new Error("自动更新只能在 Android App 中运行");
  return (
    await AppUpdate.downloadLatest({
      url: info?.apkDownloadUrl,
      fileName: info?.apkName,
    })
  ).value;
}
