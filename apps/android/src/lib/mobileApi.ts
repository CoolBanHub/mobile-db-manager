export interface MobileLoginResponse {
  ok: boolean;
  token: string | null;
  expiresAt: number | null;
}

export interface MobileConnectionSummary {
  id: string;
  name: string;
  note: string;
  dbType: string;
  host: string;
  port: number;
  database: string | null;
  color: string | null;
  ssl: boolean;
  readOnly: boolean;
  isProduction: boolean;
}

export function buildApiHeaders(token: string | null, initial?: HeadersInit): Headers {
  const headers = new Headers(initial);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  return headers;
}

export function apiFetch(baseUrl: string, path: string, token: string | null, init: RequestInit = {}): Promise<Response> {
  return fetch(`${baseUrl}${path}`, {
    ...init,
    headers: buildApiHeaders(token, init.headers),
  });
}
