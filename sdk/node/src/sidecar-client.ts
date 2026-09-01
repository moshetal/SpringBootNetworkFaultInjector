import type {
  Decision,
  MetricsSnapshot,
  SidecarTransport,
} from "./types.js";

export type { SidecarTransport } from "./types.js";

export const DEFAULT_DECIDE_MS = 2000;
export const DEFAULT_READY_MS = 15000;

interface PendingRequest {
  resolve: (payload: Record<string, unknown>) => void;
  reject: (error: Error) => void;
  timer: ReturnType<typeof setTimeout>;
}

export class SidecarClient {
  private readonly pending = new Map<string, PendingRequest>();
  private readonly decideMs: number;
  private readonly readyPromise: Promise<void>;
  private markReady!: () => void;
  private nextId = 1;
  private ready = false;

  constructor(
    private readonly transport: SidecarTransport,
    options: { decideMs?: number } = {},
  ) {
    this.decideMs = options.decideMs ?? DEFAULT_DECIDE_MS;
    this.readyPromise = new Promise((resolve) => {
      this.markReady = resolve;
    });
    transport.onLine((line) => this.handleLine(line));
  }

  waitUntilReady(readyMs = DEFAULT_READY_MS): Promise<void> {
    if (this.ready) {
      return Promise.resolve();
    }

    return this.withTimeout(
      this.readyPromise,
      readyMs,
      "Sidecar readiness timed out",
    );
  }

  decide(method: string, url: string): Promise<Decision> {
    return this.request<Decision>({ op: "decide", method, url });
  }

  metrics(): Promise<MetricsSnapshot> {
    return this.request<MetricsSnapshot>({ op: "metrics" });
  }

  async setEnabled(enabled: boolean): Promise<void> {
    await this.request({ op: "setEnabled", enabled });
  }

  async shutdown(): Promise<void> {
    await this.request({ op: "shutdown" });
    await this.transport.close();
  }

  private request<T>(message: Record<string, unknown>): Promise<T> {
    const id = String(this.nextId++);

    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${String(message.op)} request timed out`));
      }, this.decideMs);

      this.pending.set(id, {
        resolve: (payload) => resolve(payload as T),
        reject,
        timer,
      });
      this.transport.writeLine(JSON.stringify({ ...message, id }));
    });
  }

  private handleLine(line: string): void {
    let payload: Record<string, unknown>;
    try {
      payload = JSON.parse(line) as Record<string, unknown>;
    } catch {
      return;
    }

    if (payload.op === "ready") {
      this.ready = true;
      this.markReady();
      return;
    }

    if (typeof payload.id !== "string") {
      return;
    }

    const request = this.pending.get(payload.id);
    if (!request) {
      return;
    }

    clearTimeout(request.timer);
    this.pending.delete(payload.id);
    if (typeof payload.error === "string") {
      request.reject(new Error(payload.error));
    } else {
      request.resolve(payload);
    }
  }

  private withTimeout<T>(
    promise: Promise<T>,
    timeoutMs: number,
    message: string,
  ): Promise<T> {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(message)), timeoutMs);
      promise.then(
        (value) => {
          clearTimeout(timer);
          resolve(value);
        },
        (error: unknown) => {
          clearTimeout(timer);
          reject(error);
        },
      );
    });
  }
}
