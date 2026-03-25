import fs from "fs/promises";
import type { Logger } from "./types.js";

/**
 * Persists lastEventId to disk for crash recovery.
 * Uses atomic write (temp file + rename) to prevent corruption.
 * Optional — if no stateFile is configured, state is in-memory only.
 */
export class StatePersistence {
  private lastEventId: string | null = null;

  constructor(
    private readonly stateFile: string | null,
    private readonly logger: Logger,
  ) {}

  async load(): Promise<string | null> {
    if (!this.stateFile) return null;

    try {
      const data = await fs.readFile(this.stateFile, "utf-8");
      const state = JSON.parse(data);
      this.lastEventId = state.lastEventId ?? null;
      this.logger.log(`[VaultServerSDK] State loaded: lastEventId=${this.lastEventId}`);
      return this.lastEventId;
    } catch {
      // File doesn't exist or is corrupt — start fresh
      return null;
    }
  }

  getLastEventId(): string | null {
    return this.lastEventId;
  }

  async setLastEventId(eventId: string): Promise<void> {
    this.lastEventId = eventId;

    if (!this.stateFile) return;

    // Atomic write: temp file + rename
    const tmpPath = this.stateFile + ".tmp";
    const data = JSON.stringify({ lastEventId: eventId, updatedAt: new Date().toISOString() });

    try {
      await fs.writeFile(tmpPath, data, "utf-8");
      await fs.rename(tmpPath, this.stateFile);
    } catch (err) {
      this.logger.error(`[VaultServerSDK] State persist failed:`, err);
    }
  }
}
