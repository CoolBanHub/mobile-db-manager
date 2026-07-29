export type ConnectionEnvironment = "production" | "staging" | "development";

export interface ConnectionPreference {
  group: string;
  favorite: boolean;
  environment: ConnectionEnvironment;
}

const STORAGE_KEY = "dbx-mobile.connection-preferences.v1";

type PreferenceMap = Record<string, ConnectionPreference>;

function key(serverId: string, connectionId: string): string {
  return `${serverId}:${connectionId}`;
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
  serverId: string,
  connectionId: string,
  isProduction: boolean,
  storage: Pick<Storage, "getItem"> = localStorage,
): ConnectionPreference {
  return (
    load(storage)[key(serverId, connectionId)] ?? {
      group: "未分组",
      favorite: false,
      environment: isProduction ? "production" : "development",
    }
  );
}

export function saveConnectionPreference(
  serverId: string,
  connectionId: string,
  preference: ConnectionPreference,
  storage: Pick<Storage, "getItem" | "setItem"> = localStorage,
): void {
  const values = load(storage);
  values[key(serverId, connectionId)] = preference;
  storage.setItem(STORAGE_KEY, JSON.stringify(values));
}

export function removeConnectionPreference(
  serverId: string,
  connectionId: string,
  storage: Pick<Storage, "getItem" | "setItem"> = localStorage,
): void {
  const values = load(storage);
  delete values[key(serverId, connectionId)];
  storage.setItem(STORAGE_KEY, JSON.stringify(values));
}
