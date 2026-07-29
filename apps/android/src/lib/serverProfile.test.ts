import { describe, expect, it } from "vitest";
import { deleteServerProfile, findServerNetworkSettings, loadServerProfile, loadServerProfiles, normalizeCertificatePin, normalizeProxyUrl, normalizeServerUrl, saveServerProfile, setActiveServerProfile, SERVER_PROFILE_STORAGE_KEY } from "./serverProfile";

function memoryStorage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
    values,
  };
}

describe("serverProfile", () => {
  it("normalizes a server address and removes query data", () => {
    expect(normalizeServerUrl("dbx.example.com/team/?from=mobile")).toBe("https://dbx.example.com/team");
  });

  it("rejects credentials embedded in a server address", () => {
    expect(() => normalizeServerUrl("https://admin:secret@example.com")).toThrow("用户名或密码");
  });

  it("round trips a saved profile", () => {
    const storage = memoryStorage();
    saveServerProfile({ name: "  Production  ", baseUrl: "https://dbx.example.com/" }, storage);

    expect(storage.values.has(SERVER_PROFILE_STORAGE_KEY)).toBe(true);
    expect(loadServerProfile(storage)).toMatchObject({
      name: "Production",
      baseUrl: "https://dbx.example.com",
      network: { requestTimeoutMs: 8_000 },
    });
  });

  it("normalizes per-server proxy and certificate pin settings", () => {
    const storage = memoryStorage();
    const profile = saveServerProfile(
      {
        name: "Pinned",
        baseUrl: "https://dbx.example.com",
        network: {
          requestTimeoutMs: 15_000,
          proxyUrl: "proxy.internal",
          certificatePin: "aa".repeat(32),
          allowInvalidCertificate: true,
        },
      },
      storage,
    );

    expect(profile.network.proxyUrl).toBe("http://proxy.internal:8080");
    expect(profile.network.certificatePin.split(":")).toHaveLength(32);
    expect(findServerNetworkSettings("https://dbx.example.com/", storage)).toEqual(profile.network);
  });

  it("accepts SHA-256 fingerprint and SPKI pin formats", () => {
    expect(normalizeCertificatePin("AA:".repeat(31) + "AA")).toBe("AA:".repeat(31) + "AA");
    expect(normalizeCertificatePin(`sha256/${"A".repeat(43)}=`)).toBe(`sha256/${"A".repeat(43)}=`);
  });

  it("rejects proxy credentials, unsupported schemes, and malformed pins", () => {
    expect(() => normalizeProxyUrl("http://user:secret@proxy.internal:8080")).toThrow("用户名或密码");
    expect(() => normalizeProxyUrl("https://proxy.internal:8443")).toThrow("HTTP 或 SOCKS5");
    expect(() => normalizeCertificatePin("not-a-fingerprint")).toThrow("证书指纹");
  });

  it("drops malformed legacy network fields without dropping the server profile", () => {
    const storage = memoryStorage();
    storage.setItem(
      SERVER_PROFILE_STORAGE_KEY,
      JSON.stringify({
        activeId: "server-one",
        profiles: [
          {
            id: "server-one",
            name: "Existing",
            baseUrl: "https://dbx.example.com",
            network: {
              requestTimeoutMs: 30_000,
              proxyUrl: "ftp://proxy.invalid",
              certificatePin: "broken",
              allowInvalidCertificate: true,
            },
          },
        ],
      }),
    );

    expect(loadServerProfile(storage)).toMatchObject({
      id: "server-one",
      network: {
        requestTimeoutMs: 30_000,
        proxyUrl: "",
        certificatePin: "",
        allowInvalidCertificate: true,
      },
    });
  });

  it("keeps multiple server profiles and switches the active profile", () => {
    const storage = memoryStorage();
    const production = saveServerProfile({ name: "Production", baseUrl: "prod.example.com" }, storage);
    const staging = saveServerProfile({ name: "Staging", baseUrl: "staging.example.com" }, storage);

    expect(loadServerProfiles(storage)).toHaveLength(2);
    expect(loadServerProfile(storage)?.id).toBe(staging.id);
    expect(setActiveServerProfile(production.id, storage).name).toBe("Production");
    expect(deleteServerProfile(production.id, storage)?.id).toBe(staging.id);
  });

  it("uses a stable id while migrating a legacy single-server profile", () => {
    const storage = memoryStorage();
    storage.setItem("dbx-mobile.server-profile.v1", JSON.stringify({ name: "Legacy", baseUrl: "https://legacy.example.com" }));

    expect(loadServerProfile(storage)?.id).toBe("legacy-default");
    expect(loadServerProfiles(storage)[0]?.id).toBe("legacy-default");
  });
});
