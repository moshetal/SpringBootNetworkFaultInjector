import assert from "node:assert/strict";
import test from "node:test";
import axios, { type AxiosAdapter, type AxiosInstance } from "axios";
import { FaultInjector } from "../fault-injector.ts";
import { SidecarClient } from "../sidecar-client.ts";
import { MemoryTransport } from "./memory-transport.ts";

interface TestContext {
  adapterCalls: number;
  axiosInstance: AxiosInstance;
  injector: FaultInjector;
  transport: MemoryTransport;
}

async function started(
  reply: Record<string, unknown> | Error,
  shutdownError?: string,
): Promise<TestContext> {
  const transport = new MemoryTransport((message, memory) => {
    if (message.op === "decide") {
      memory.push(
        reply instanceof Error
          ? { id: message.id, error: reply.message }
          : { id: message.id, ...reply },
      );
    } else if (message.op === "shutdown") {
      memory.push(
        shutdownError
          ? { id: message.id, error: shutdownError }
          : { id: message.id, ok: true },
      );
    }
  });
  const client = new SidecarClient(transport, { decideMs: 200 });
  transport.push({ op: "ready" });
  const injector = await FaultInjector.start({
    configPath: "unused.yaml",
    client,
  });

  const context = {
    adapterCalls: 0,
    axiosInstance: undefined as unknown as AxiosInstance,
    injector,
    transport,
  };
  const adapter: AxiosAdapter = async (config) => {
    context.adapterCalls += 1;
    return {
      status: 200,
      data: "ok",
      statusText: "OK",
      headers: {},
      config,
    };
  };
  context.axiosInstance = axios.create({ adapter });
  injector.patchAxios(context.axiosInstance);
  return context;
}

test("INJECT_ERROR rejects with a synthetic response without calling the adapter", async () => {
  const context = await started({
    instruction: "INJECT_ERROR",
    errorStatus: 503,
    errorMessage: "unavailable",
  });

  await assert.rejects(
    () => context.axiosInstance.get("https://api.example.com/x"),
    (error: unknown) => {
      const response = (error as { response?: { status?: number; data?: unknown } })
        .response;
      assert.equal(response?.status, 503);
      assert.equal(response?.data, "unavailable");
      return true;
    },
  );
  assert.equal(context.adapterCalls, 0);
});

test("INJECT_DELAY waits before calling the adapter", async () => {
  const context = await started({
    instruction: "INJECT_DELAY",
    delayMs: 30,
  });
  const startedAt = performance.now();

  await context.axiosInstance.get("https://api.example.com/x");

  assert.equal(context.adapterCalls, 1);
  assert.ok(performance.now() - startedAt >= 25);
});

test("PASS calls the adapter and sends the combined URL and default method", async () => {
  const context = await started({ instruction: "PASS" });

  await context.axiosInstance.get("/x", {
    baseURL: "https://api.example.com",
  });

  assert.equal(context.adapterCalls, 1);
  assert.deepEqual(JSON.parse(context.transport.writes.at(-1) ?? ""), {
    op: "decide",
    method: "GET",
    url: "https://api.example.com/x",
    id: "1",
  });
});

test("a decision error warns and fails open", async (t) => {
  const warn = t.mock.method(console, "warn", () => {});
  const context = await started(new Error("sidecar unavailable"));

  const response = await context.axiosInstance.get("https://api.example.com/x");

  assert.equal(response.data, "ok");
  assert.equal(context.adapterCalls, 1);
  assert.equal(warn.mock.callCount(), 1);
});

test("an invalid injected status warns and fails open", async (t) => {
  const warn = t.mock.method(console, "warn", () => {});
  const context = await started({
    instruction: "INJECT_ERROR",
    errorStatus: 999,
  });

  const response = await context.axiosInstance.get("https://api.example.com/x");

  assert.equal(response.data, "ok");
  assert.equal(context.adapterCalls, 1);
  assert.equal(warn.mock.callCount(), 1);
});

test("stop ejects the interceptor even when shutdown fails", async () => {
  const context = await started(
    {
      instruction: "INJECT_DELAY",
      delayMs: 100,
    },
    "shutdown failed",
  );

  await assert.rejects(() => context.injector.stop(), /shutdown failed/);
  const startedAt = performance.now();
  await context.axiosInstance.get("https://api.example.com/x");

  assert.equal(context.adapterCalls, 1);
  assert.ok(performance.now() - startedAt < 75);
});
