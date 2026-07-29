export const SERVER_PROFILE_STORAGE_KEY = "dbx-mobile.server-profiles.v2";
const LEGACY_SERVER_PROFILE_STORAGE_KEY = "dbx-mobile.server-profile.v1";

export interface ServerNetworkSettings {
  requestTimeoutMs: number;
  proxyUrl: string;
  certificatePin: string;
  allowInvalidCertificate: boolean;
}

export interface ServerProfile {
  id: string;
  name: string;
  baseUrl: string;
  network: ServerNetworkSettings;
}

interface ServerProfileState {
  activeId: string | null;
  profiles: ServerProfile[];
}

export const DEFAULT_NETWORK_SETTINGS: ServerNetworkSettings = {
  requestTimeoutMs: 8_000,
  proxyUrl: "",
  certificatePin: "",
  allowInvalidCertificate: false,
};

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

export function normalizeProxyUrl(value: string): string {
  const trimmed = value.trim();
  if (!trimmed) return "";
  const withProtocol = /^[a-z][a-z\d+.-]*:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`;
  const parsed = new URL(withProtocol);
  if (!["http:", "socks5:"].includes(parsed.protocol)) {
    throw new Error("代理只支持 HTTP 或 SOCKS5");
  }
  if (!parsed.hostname) throw new Error("代理地址缺少主机名");
  if (parsed.username || parsed.password) throw new Error("代理地址暂不支持用户名或密码");
  if (parsed.pathname !== "/" || parsed.search || parsed.hash) {
    throw new Error("代理地址不能包含路径、查询参数或锚点");
  }
  if (!parsed.port) parsed.port = parsed.protocol === "socks5:" ? "1080" : "8080";
  return parsed.toString().replace(/\/$/, "");
}

export function normalizeCertificatePin(value: string): string {
  const trimmed = value.trim();
  if (!trimmed) return "";

  const prefixed = trimmed.match(/^sha256\/([a-z\d+/]{43}=?)$/i);
  if (prefixed) return `sha256/${prefixed[1]}`;

  const fingerprint = trimmed.replace(/^sha-?256\s*[:=]?\s*/i, "").replace(/[\s:-]/g, "");
  if (/^[a-f\d]{64}$/i.test(fingerprint)) {
    return fingerprint.toUpperCase().match(/.{2}/g)?.join(":") ?? fingerprint.toUpperCase();
  }
  throw new Error("证书指纹应为 64 位 SHA-256 十六进制，或 sha256/BASE64 SPKI");
}

function normalizeNetworkSettings(value?: Partial<ServerNetworkSettings>, strict = false): ServerNetworkSettings {
  const requestTimeoutMs = Number(value?.requestTimeoutMs);
  let proxyUrl = "";
  let certificatePin = "";
  try {
    proxyUrl = normalizeProxyUrl(typeof value?.proxyUrl === "string" ? value.proxyUrl : "");
  } catch (error) {
    if (strict) throw error;
  }
  try {
    certificatePin = normalizeCertificatePin(typeof value?.certificatePin === "string" ? value.certificatePin : "");
  } catch (error) {
    if (strict) throw error;
  }
  return {
    requestTimeoutMs: Number.isFinite(requestTimeoutMs) && requestTimeoutMs >= 3_000 && requestTimeoutMs <= 120_000 ? Math.round(requestTimeoutMs) : DEFAULT_NETWORK_SETTINGS.requestTimeoutMs,
    proxyUrl,
    certificatePin,
    allowInvalidCertificate: value?.allowInvalidCertificate === true,
  };
}

function normalizeProfile(profile: Partial<ServerProfile> & Pick<ServerProfile, "name" | "baseUrl">, strictNetwork = false): ServerProfile {
  return {
    id: typeof profile.id === "string" && profile.id ? profile.id : (globalThis.crypto?.randomUUID?.() ?? `server-${Date.now()}-${Math.random().toString(36).slice(2)}`),
    name: profile.name.trim() || "DBX Server",
    baseUrl: normalizeServerUrl(profile.baseUrl),
    network: normalizeNetworkSettings(profile.network, strictNetwork),
  };
}

function parseState(storage: Pick<Storage, "getItem">): ServerProfileState {
  const raw = storage.getItem(SERVER_PROFILE_STORAGE_KEY);
  if (raw) {
    try {
      const value = JSON.parse(raw) as Partial<ServerProfileState>;
      const profiles = Array.isArray(value.profiles) ? value.profiles.filter((profile): profile is ServerProfile => Boolean(profile) && typeof profile.name === "string" && typeof profile.baseUrl === "string").map((profile) => normalizeProfile(profile)) : [];
      const activeId = typeof value.activeId === "string" && profiles.some((profile) => profile.id === value.activeId) ? value.activeId : (profiles[0]?.id ?? null);
      return { activeId, profiles };
    } catch {
      // Fall through to the legacy migration.
    }
  }

  const legacy = storage.getItem(LEGACY_SERVER_PROFILE_STORAGE_KEY);
  if (!legacy) return { activeId: null, profiles: [] };
  try {
    const value = JSON.parse(legacy) as { name?: unknown; baseUrl?: unknown };
    if (typeof value.name !== "string" || typeof value.baseUrl !== "string") return { activeId: null, profiles: [] };
    const profile = normalizeProfile({ id: "legacy-default", name: value.name, baseUrl: value.baseUrl });
    return { activeId: profile.id, profiles: [profile] };
  } catch {
    return { activeId: null, profiles: [] };
  }
}

function persistState(state: ServerProfileState, storage: Pick<Storage, "setItem">): void {
  storage.setItem(SERVER_PROFILE_STORAGE_KEY, JSON.stringify(state));
}

export function loadServerProfiles(storage: Pick<Storage, "getItem"> = localStorage): ServerProfile[] {
  return parseState(storage).profiles;
}

export function loadServerProfile(storage: Pick<Storage, "getItem"> = localStorage): ServerProfile | null {
  const state = parseState(storage);
  return state.profiles.find((profile) => profile.id === state.activeId) ?? state.profiles[0] ?? null;
}

export function findServerNetworkSettings(baseUrl: string, storage?: Pick<Storage, "getItem">): ServerNetworkSettings {
  const source = storage ?? (typeof localStorage === "undefined" ? undefined : localStorage);
  if (!source) return { ...DEFAULT_NETWORK_SETTINGS };
  const normalizedBaseUrl = normalizeServerUrl(baseUrl);
  const profile = parseState(source).profiles.find((item) => item.baseUrl === normalizedBaseUrl);
  return profile?.network ?? { ...DEFAULT_NETWORK_SETTINGS };
}

export function saveServerProfile(profile: Omit<ServerProfile, "id" | "network"> & Partial<Pick<ServerProfile, "id" | "network">>, storage: Pick<Storage, "getItem" | "setItem"> = localStorage): ServerProfile {
  const state = parseState(storage);
  const normalized = normalizeProfile(profile, true);
  const index = state.profiles.findIndex((item) => item.id === normalized.id);
  if (index >= 0) state.profiles[index] = normalized;
  else state.profiles.push(normalized);
  state.activeId = normalized.id;
  persistState(state, storage);
  return normalized;
}

export function setActiveServerProfile(id: string, storage: Pick<Storage, "getItem" | "setItem"> = localStorage): ServerProfile {
  const state = parseState(storage);
  const profile = state.profiles.find((item) => item.id === id);
  if (!profile) throw new Error("服务器配置不存在");
  state.activeId = id;
  persistState(state, storage);
  return profile;
}

export function deleteServerProfile(id: string, storage: Pick<Storage, "getItem" | "setItem"> = localStorage): ServerProfile | null {
  const state = parseState(storage);
  state.profiles = state.profiles.filter((profile) => profile.id !== id);
  if (state.activeId === id) state.activeId = state.profiles[0]?.id ?? null;
  persistState(state, storage);
  return state.profiles.find((profile) => profile.id === state.activeId) ?? null;
}

export function clearServerProfile(storage: Pick<Storage, "setItem"> = localStorage): void {
  persistState({ activeId: null, profiles: [] }, storage);
}
