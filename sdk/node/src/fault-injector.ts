import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import type { AxiosInstance } from "axios";
import { ChildProcessTransport } from "./child-transport.js";
import {
  DEFAULT_READY_MS,
  SidecarClient,
} from "./sidecar-client.js";
import { patchFetch as installFetchPatch } from "./patch-fetch.js";
import type { MetricsSnapshot } from "./types.js";

export interface StartOptions {
  configPath: string;
  java?: string;
  jar?: string;
  timeouts?: { readyMs?: number; decideMs?: number };
  /** Tests only: skip spawning the sidecar process. */
  client?: SidecarClient;
}

export class FaultInjector {
  private restoreFetch?: () => void;

  private constructor(private readonly client: SidecarClient) {}

  static async start(options: StartOptions): Promise<FaultInjector> {
    let client = options.client;
    let startupFailure: Promise<never> | undefined;

    if (!client) {
      const java = options.java ?? process.env.FAULT_INJECTOR_JAVA ?? "java";
      const jar =
        options.jar ??
        process.env.FAULT_INJECTOR_SIDECAR_JAR ??
        fileURLToPath(
          new URL("../sidecar/fault-injector-sidecar.jar", import.meta.url),
        );

      if (!existsSync(jar)) {
        throw new Error(`Sidecar jar does not exist: ${jar}`);
      }

      const transport = new ChildProcessTransport(java, [
        "-jar",
        jar,
        "--config",
        options.configPath,
      ]);
      startupFailure = transport.startupFailure;
      client = new SidecarClient(transport, {
        decideMs: options.timeouts?.decideMs,
      });
    }

    const ready = client.waitUntilReady(
      options.timeouts?.readyMs ?? DEFAULT_READY_MS,
    );
    await (startupFailure ? Promise.race([ready, startupFailure]) : ready);
    return new FaultInjector(client);
  }

  patchFetch(target: typeof globalThis): void {
    this.restoreFetch?.();
    this.restoreFetch = installFetchPatch(this.client, target);
  }

  patchAxios(_instance: AxiosInstance): void {
    throw new Error("patchAxios is not implemented");
  }

  metrics(): Promise<MetricsSnapshot> {
    return this.client.metrics();
  }

  setEnabled(enabled: boolean): Promise<void> {
    return this.client.setEnabled(enabled);
  }

  async stop(): Promise<void> {
    try {
      await this.client.shutdown();
    } finally {
      this.restoreFetch?.();
      this.restoreFetch = undefined;
    }
  }
}
