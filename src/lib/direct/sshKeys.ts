import type { MobileSshKeyDraft, MobileSshKeySummary } from "../mobileTypes";
import { DirectDatabase, requireNative } from "./native";

// 密钥列表只读取非敏感摘要。文件导入由 Android 文档选择器直接交给原生保险箱，
// 私钥文件内容不会经过 Capacitor 返回 WebView；粘贴导入仅传递用户本次输入。
export async function listSshKeys(): Promise<MobileSshKeySummary[]> {
  requireNative();
  return (await DirectDatabase.listSshKeys()).value;
}

export async function saveSshKey(key: MobileSshKeyDraft): Promise<MobileSshKeySummary> {
  requireNative();
  return (await DirectDatabase.saveSshKey({ key })).value;
}

export async function importSshKeyFile(name: string, passphrase: string, id?: string): Promise<MobileSshKeySummary> {
  requireNative();
  return (await DirectDatabase.importSshKeyFile({ id, name, passphrase })).value;
}

export async function deleteSshKey(id: string): Promise<void> {
  requireNative();
  await DirectDatabase.deleteSshKey({ id });
}
