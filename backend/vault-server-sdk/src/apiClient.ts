import type {
  InitiateUploadOptions,
  InitiateUploadResult,
  UploadStatusResult,
  DownloadUrlResult,
  ListUploadsOptions,
  ListUploadsResult,
} from "./types.js";
import type { TokenManager } from "./tokenManager.js";

/**
 * Typed API client for server-to-server operations with VaultSDK Backend.
 * Authentication is handled automatically via TokenManager.
 */
export class APIClient {
  constructor(
    private readonly baseUrl: string,
    private readonly tokenManager: TokenManager,
  ) {}

  async initiateUpload(options: InitiateUploadOptions): Promise<InitiateUploadResult> {
    return this.request("POST", "/v1/uploads/initiate", options);
  }

  async getUploadStatus(uploadId: string): Promise<UploadStatusResult> {
    return this.request("GET", `/v1/uploads/${uploadId}/status`);
  }

  async cancelUpload(uploadId: string): Promise<{ success: boolean }> {
    return this.request("DELETE", `/v1/uploads/${uploadId}`);
  }

  async getDownloadUrl(fileId: string): Promise<DownloadUrlResult> {
    return this.request("GET", `/v1/uploads/${fileId}/download-url`);
  }

  async listUploads(options?: ListUploadsOptions): Promise<ListUploadsResult> {
    const params = new URLSearchParams();
    if (options?.page) params.set("page", String(options.page));
    if (options?.limit) params.set("limit", String(options.limit));
    if (options?.status) params.set("status", options.status);
    if (options?.entityType) params.set("entityType", options.entityType);

    const query = params.toString();
    return this.request("GET", `/v1/uploads/list${query ? `?${query}` : ""}`);
  }

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const token = await this.tokenManager.getToken();
    const url = `${this.baseUrl}${path}`;

    const response = await fetch(url, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body ? { "Content-Type": "application/json" } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });

    if (!response.ok) {
      const text = await response.text().catch(() => "");
      throw new Error(`API request failed: ${method} ${path} → HTTP ${response.status} ${text}`);
    }

    const result = (await response.json()) as { success: boolean; data: T };
    return result.data;
  }
}
