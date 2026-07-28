export const SERVER_PROFILE_STORAGE_KEY = "dbx-mobile.server-profile.v1";

export interface ServerProfile {
  name: string;
  baseUrl: string;
}

export function normalizeServerUrl(value: string): string {
  const trimmed = value.trim();
  if (!trimmed) throw new Error("请输入 DBX Server 地址");

  const withProtocol = /^[a-z][a-z\d+.-]*:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
  const parsed = new URL(withProtocol);

  if (parsed.protocol !== "https:" && parsed.protocol !== "http:") {
    throw new Error("服务器地址只支持 HTTP 或 HTTPS");
  }
  if (parsed.username || parsed.password) {
    throw new Error("请勿在服务器地址中填写用户名或密码");
  }

  parsed.hash = "";
  parsed.search = "";
  parsed.pathname = parsed.pathname.replace(/\/+$/, "");
  return parsed.toString().replace(/\/$/, "");
}

export function loadServerProfile(storage: Pick<Storage, "getItem"> = localStorage): ServerProfile | null {
  const raw = storage.getItem(SERVER_PROFILE_STORAGE_KEY);
  if (!raw) return null;

  try {
    const value = JSON.parse(raw) as Partial<ServerProfile>;
    if (typeof value.name !== "string" || typeof value.baseUrl !== "string") return null;
    return {
      name: value.name.trim() || "DBX Server",
      baseUrl: normalizeServerUrl(value.baseUrl),
    };
  } catch {
    return null;
  }
}

export function saveServerProfile(profile: ServerProfile, storage: Pick<Storage, "setItem"> = localStorage): ServerProfile {
  const normalized = {
    name: profile.name.trim() || "DBX Server",
    baseUrl: normalizeServerUrl(profile.baseUrl),
  };
  storage.setItem(SERVER_PROFILE_STORAGE_KEY, JSON.stringify(normalized));
  return normalized;
}

export function clearServerProfile(storage: Pick<Storage, "removeItem"> = localStorage): void {
  storage.removeItem(SERVER_PROFILE_STORAGE_KEY);
}
