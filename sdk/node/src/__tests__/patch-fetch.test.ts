import assert from "node:assert/strict";
import test from "node:test";
import { FaultInjector } from "../fault-injector.ts";
import { SidecarClient } from "../sidecar-client.ts";
import { MemoryTransport } from "./memory-transport.ts";

type FetchTarget = typeof globalThis;

interface TestContext {
  transport: MemoryTransport;
  client: SidecarClient;
  injector?: FaultInjector;
  target: FetchTarget;
  originalFetch: typeof fetch;
  calls: Array<[RequestInfo | URL, RequestInit | undefined]>;
}

function setup(
  reply: Record<string, unknown> | Error,
): TestContext {
  const transport = new MemoryTransport((message, memory) => {
    if (message.op === "decide") {
      memory.push(
        reply instanceof Error
          ? { id: message.id, error: reply.message }
          : { id: message.id, ...reply },
      );
    } else if (message.op === "shutdown") {
      memory.push({ id: message.id, ok: true });
    }
  });
  const client = new SidecarClient(transport, { decideMs: 200 });
  transport.push({ op: "ready" });

  const calls: Array<[RequestInfo | URL, RequestInit | undefined]> = [];
  const originalFetch: typeof fetch = async (input, init) => {
    calls.push([input, init]);
    return new Response("ok");
  };
  const target = { fetch: originalFetch } as FetchTarget;

  return {
    transport,
    client,
    target,
    originalFetch,
    calls,
  };
}

async function started(
  reply: Record<string, unknown> | Error,
): Promise<TestContext & { injector: FaultInjector }> {
  const context = setup(reply);
  context.injector = await FaultInjector.start({
    configPath: "unused.yaml",
    client: context.client,
  });
  context.injector.patchFetch(context.target);
  return context as TestContext & { injector: FaultInjector };
}

test("INJECT_ERROR returns a synthetic response without calling fetch", async () => {
  const { target, calls } = await started({
    instruction: "INJECT_ERROR",
    errorStatus: 503,
  });

  const response = await target.fetch("https://api.example.com");

  assert.equal(response.status, 503);
  assert.equal(calls.length, 0);
});

test("INJECT_DELAY waits before calling the original fetch", async () => {
  const { target, calls } = await started({
    instruction: "INJECT_DELAY",
    delayMs: 30,
  });
  const startedAt = performance.now();

  await target.fetch("https://api.example.com");

  assert.equal(calls.length, 1);
  assert.ok(performance.now() - startedAt >= 25);
});

test("PASS immediately calls the original fetch with method and Request URL", async () => {
  const context = await started({ instruction: "PASS" });
  const request = new Request("https://api.example.com/resource", {
    method: "POST",
  });

  await context.target.fetch(request);

  assert.equal(context.calls.length, 1);
  assert.deepEqual(JSON.parse(context.transport.writes.at(-1) ?? ""), {
    op: "decide",
    method: "POST",
    url: "https://api.example.com/resource",
    id: "1",
  });
});

test("a decision error warns and fails open", async (t) => {
  const warn = t.mock.method(console, "warn", () => {});
  const { target, calls } = await started(new Error("sidecar unavailable"));

  const response = await target.fetch("https://api.example.com");

  assert.equal(await response.text(), "ok");
  assert.equal(calls.length, 1);
  assert.equal(warn.mock.callCount(), 1);
});

test("an invalid injected response warns and fails open", async (t) => {
  const warn = t.mock.method(console, "warn", () => {});
  const { target, calls } = await started({
    instruction: "INJECT_ERROR",
    errorStatus: 999,
  });

  const response = await target.fetch("https://api.example.com");

  assert.equal(await response.text(), "ok");
  assert.equal(calls.length, 1);
  assert.equal(warn.mock.callCount(), 1);
});

test("stop restores the original fetch even when shutdown fails", async () => {
  const transport = new MemoryTransport((message, memory) => {
    if (message.op === "shutdown") {
      memory.push({ id: message.id, error: "shutdown failed" });
    } else {
      memory.push({ id: message.id, instruction: "PASS" });
    }
  });
  const client = new SidecarClient(transport, { decideMs: 200 });
  transport.push({ op: "ready" });
  const injector = await FaultInjector.start({
    configPath: "unused.yaml",
    client,
  });
  const originalFetch: typeof fetch = async () => new Response("ok");
  const target = { fetch: originalFetch } as FetchTarget;
  injector.patchFetch(target);

  await assert.rejects(() => injector.stop(), /shutdown failed/);

  assert.equal(target.fetch, originalFetch);
});
