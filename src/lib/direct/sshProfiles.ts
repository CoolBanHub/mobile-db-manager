import type { MobileSshProfileDraft, MobileSshProfileSummary } from "../mobileTypes";
import { DirectDatabase, requireNative } from "./native";

// WebView 只处理不含秘密的摘要和用户本次主动输入的草稿。读取接口永远不会返回
// 已保存的密码、私钥或口令；编辑时空值由原生保险箱解释为“沿用旧值”。
export async function listSshProfiles(): Promise<MobileSshProfileSummary[]> {
  requireNative();
  return (await DirectDatabase.listSshProfiles()).value;
}

export async function getSshProfile(id: string): Promise<MobileSshProfileSummary> {
  requireNative();
  return (await DirectDatabase.getSshProfile({ id })).value;
}

export async function saveSshProfile(profile: MobileSshProfileDraft): Promise<MobileSshProfileSummary> {
  requireNative();
  return (await DirectDatabase.saveSshProfile({ profile })).value;
}

export async function deleteSshProfile(id: string): Promise<void> {
  requireNative();
  await DirectDatabase.deleteSshProfile({ id });
}
