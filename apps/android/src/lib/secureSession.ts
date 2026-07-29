import { Capacitor, registerPlugin } from "@capacitor/core";

interface SecureVaultPlugin {
  set(options: { key: string; value: string; requireUnlock: boolean }): Promise<void>;
  get(options: { key: string; prompt: string }): Promise<{ value: string | null }>;
  remove(options: { key: string }): Promise<void>;
}

const SecureVault = registerPlugin<SecureVaultPlugin>("SecureVault");
const fallbackPrefix = "dbx-mobile.session-fallback.";

export interface StoredSession {
  token: string;
  expiresAt: number | null;
}

export async function saveSecureSession(profileId: string, session: StoredSession, requireUnlock: boolean): Promise<void> {
  const value = JSON.stringify(session);
  if (Capacitor.isNativePlatform()) {
    await SecureVault.set({ key: profileId, value, requireUnlock });
    return;
  }
  sessionStorage.setItem(`${fallbackPrefix}${profileId}`, value);
}

export async function loadSecureSession(profileId: string): Promise<StoredSession | null> {
  const raw = Capacitor.isNativePlatform()
    ? (await SecureVault.get({ key: profileId, prompt: "解锁 DBX 登录令牌" })).value
    : sessionStorage.getItem(`${fallbackPrefix}${profileId}`);
  if (!raw) return null;
  try {
    const value = JSON.parse(raw) as StoredSession;
    if (typeof value.token !== "string" || !value.token) return null;
    if (value.expiresAt && value.expiresAt * 1_000 <= Date.now()) {
      await removeSecureSession(profileId);
      return null;
    }
    return value;
  } catch {
    return null;
  }
}

export async function removeSecureSession(profileId: string): Promise<void> {
  if (Capacitor.isNativePlatform()) {
    await SecureVault.remove({ key: profileId });
    return;
  }
  sessionStorage.removeItem(`${fallbackPrefix}${profileId}`);
}
