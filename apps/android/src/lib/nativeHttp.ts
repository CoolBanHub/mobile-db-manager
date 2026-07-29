import { Capacitor, registerPlugin } from "@capacitor/core";
import { findServerNetworkSettings, type ServerNetworkSettings } from "./serverProfile";

interface NativeHttpRequest {
  requestId: string;
  url: string;
  method: string;
  headers: Record<string, string>;
  body?: string;
  timeoutMs: number;
  proxyUrl: string;
  certificatePin: string;
  allowInvalidCertificate: boolean;
}

interface NativeHttpResponse {
  status: number;
  headers: Record<string, string>;
  body: string;
}

interface NativeHttpPlugin {
  request(options: NativeHttpRequest): Promise<NativeHttpResponse>;
  cancel(options: { requestId: string }): Promise<void>;
}

const NativeHttp = registerPlugin<NativeHttpPlugin>("NativeHttp");

function requestHeaders(headers?: HeadersInit): Record<string, string> {
  return Object.fromEntries(new Headers(headers).entries());
}

function requestBody(body: BodyInit | null | undefined): string | undefined {
  if (body === undefined || body === null) return undefined;
  if (typeof body === "string") return body;
  if (body instanceof URLSearchParams) return body.toString();
  throw new Error("原生移动请求目前只支持文本或 JSON 请求体");
}

function requestId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `native-http-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function usesNativeHttp(): boolean {
  return Capacitor.isNativePlatform();
}

export async function nativeAwareFetch(baseUrl: string, url: string, init: RequestInit, networkSettings?: ServerNetworkSettings): Promise<Response> {
  if (!usesNativeHttp()) return fetch(url, init);

  const settings = networkSettings ?? findServerNetworkSettings(baseUrl);
  const id = requestId();
  const abort = () => {
    void NativeHttp.cancel({ requestId: id }).catch(() => undefined);
  };
  if (init.signal?.aborted) throw init.signal.reason ?? new DOMException("Aborted", "AbortError");
  init.signal?.addEventListener("abort", abort, { once: true });

  try {
    const response = await NativeHttp.request({
      requestId: id,
      url,
      method: init.method ?? "GET",
      headers: requestHeaders(init.headers),
      body: requestBody(init.body),
      timeoutMs: settings.requestTimeoutMs,
      proxyUrl: settings.proxyUrl,
      certificatePin: settings.certificatePin,
      allowInvalidCertificate: settings.allowInvalidCertificate,
    });
    const body = response.status === 204 || response.status === 205 ? null : response.body;
    return new Response(body, {
      status: response.status,
      headers: response.headers,
    });
  } catch (error) {
    if (init.signal?.aborted) throw init.signal.reason ?? new DOMException("Aborted", "AbortError");
    throw error;
  } finally {
    init.signal?.removeEventListener("abort", abort);
  }
}
