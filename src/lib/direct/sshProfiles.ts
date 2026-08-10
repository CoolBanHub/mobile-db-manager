import type { MobileSshProfileDraft, MobileSshProfileSummary } from "../mobileTypes";
import { DirectDatabase, requireNative } from "./native";

// WebView 只处理不含秘密的摘要和用户本次主动输入的密码。私钥由独立密钥库
// 通过 keyId 引用；读取接口永远不会返回已保存的密码或私钥。
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
