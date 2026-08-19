export type ConnectionEnvironment = "production" | "staging" | "development";

export interface ConnectionPreference {
  group: string;
  favorite: boolean;
  pinned: boolean;
  environment: ConnectionEnvironment;
  tags: string[];
  lastUsedAt: number;
}

export type ConnectionSortMode = "recent" | "name" | "environment" | "pinned";

const STORAGE_KEY = "mobile-db-manager.connection-preferences.v1";
const SORT_MODE_STORAGE_KEY = "mobile-db-manager.connection-sort-mode.v1";

type PreferenceMap = Record<string, ConnectionPreference>;

export function parseConnectionTags(value: string): string[] {
  // 同时兼容中英文分隔符；按 Unicode 字符截断，避免把代理对从中间切开。
  const seen = new Set<string>();
  const result: string[] = [];
  for (const item of value.split(/[,，、\n]+/)) {
    const tag = [...item.trim()].slice(0, 24).join("");
    const key = tag.toLocaleLowerCase();
    if (!tag || seen.has(key)) continue;
    seen.add(key);
    result.push(tag);
    if (result.length === 12) break;
  }
  return result;
}

function load(storage: Pick<Storage, "getItem"> = localStorage): PreferenceMap {
  try {
    const value = JSON.parse(storage.getItem(STORAGE_KEY) ?? "{}") as PreferenceMap;
    return value && typeof value === "object" ? value : {};
  } catch {
    return {};
  }
}

export function getConnectionPreference(
  connectionId: string,
  isProduction: boolean,
  storage: Pick<Storage, "getItem"> = localStorage,
): ConnectionPreference {
  const saved = load(storage)[connectionId];
  // 每个字段独立兜底，以兼容尚未包含 tags/environment 的旧版 localStorage 数据。
  return {
    group: saved?.group || "未分组",
    favorite: saved?.favorite === true,
    pinned: saved?.pinned === true,
    environment: saved?.environment ?? (isProduction ? "production" : "development"),
    tags: Array.isArray(saved?.tags) ? parseConnectionTags(saved.tags.join(",")) : [],
    lastUsedAt: Number.isFinite(saved?.lastUsedAt) ? saved.lastUsedAt : 0,
  };
}

export function getConnectionSortMode(
  storage: Pick<Storage, "getItem"> = localStorage,
): ConnectionSortMode {
  const value = storage.getItem(SORT_MODE_STORAGE_KEY);
  return value === "name" || value === "environment" || value === "pinned" ? value : "recent";
}

export function saveConnectionSortMode(
  mode: ConnectionSortMode,
  storage: Pick<Storage, "setItem"> = localStorage,
): void {
  storage.setItem(SORT_MODE_STORAGE_KEY, mode);
}

export function saveConnectionPreference(
  connectionId: string,
  preference: ConnectionPreference,
  storage: Pick<Storage, "getItem" | "setItem"> = localStorage,
): void {
  const values = load(storage);
  // 写入前再次规范化，调用方即使绕过表单也不能保存重复或超长标签。
  values[connectionId] = {
    ...preference,
    tags: parseConnectionTags(preference.tags.join(",")),
  };
  storage.setItem(STORAGE_KEY, JSON.stringify(values));
}

export function removeConnectionPreference(
  connectionId: string,
  storage: Pick<Storage, "getItem" | "setItem"> = localStorage,
): void {
  const values = load(storage);
  delete values[connectionId];
  storage.setItem(STORAGE_KEY, JSON.stringify(values));
}
