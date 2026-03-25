import type { Logger, AuthTokenResponse } from "./types.js";

/**
 * Manages JWT authentication with VaultSDK Backend.
 * Auto-refreshes tokens 2 minutes before expiry.
 * Tokens live in memory only — never persisted to disk.
 */
export class TokenManager {
  private token: string | null = null;
  private expiresAt = 0;

  constructor(
    private readonly baseUrl: string,
    private readonly clientId: string,
    private readonly clientSecret: string,
    private readonly logger: Logger,
  ) {}

  async getToken(): Promise<string> {
    // Refresh 2 minutes before expiry
    if (this.token && Date.now() < this.expiresAt - 120_000) {
      return this.token;
    }

    this.logger.log("[VaultServerSDK] Authenticating...");

    const response = await fetch(`${this.baseUrl}/v1/auth/token`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        client_id: this.clientId,
        client_secret: this.clientSecret,
      }),
    });

    if (!response.ok) {
      const body = await response.text().catch(() => "");
      throw new Error(`Authentication failed: HTTP ${response.status} ${body}`);
    }

    const result = (await response.json()) as AuthTokenResponse;
    this.token = result.data.access_token;
    this.expiresAt = Date.now() + result.data.expires_in * 1000;

    this.logger.log(`[VaultServerSDK] Authenticated (expires in ${result.data.expires_in}s)`);
    return this.token;
  }

  invalidate(): void {
    this.token = null;
    this.expiresAt = 0;
  }
}
