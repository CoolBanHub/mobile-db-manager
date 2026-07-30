export type ConnectionEnvironment = "production" | "staging" | "development";

export interface ConnectionPreference {
  group: string;
  favorite: boolean;
  environment: ConnectionEnvironment;
}

const STORAGE_KEY = "dbx-mobile.connection-preferences.v1";

type PreferenceMap = Record<string, ConnectionPreference>;

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
  return (
    load(storage)[connectionId] ?? {
      group: "未分组",
      favorite: false,
      environment: isProduction ? "production" : "development",
    }
  );
}

export function saveConnectionPreference(
  connectionId: string,
  preference: ConnectionPreference,
  storage: Pick<Storage, "getItem" | "setItem"> = localStorage,
): void {
  const values = load(storage);
  values[connectionId] = preference;
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
