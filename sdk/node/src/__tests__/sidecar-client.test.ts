import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";
import { MemoryTransport } from "./memory-transport.ts";
import {
  DEFAULT_DECIDE_MS,
  DEFAULT_READY_MS,
  SidecarClient,
  type SidecarTransport,
} from "../sidecar-client.ts";

test("default timeout constants", () => {
  assert.equal(DEFAULT_DECIDE_MS, 2000);
  assert.equal(DEFAULT_READY_MS, 15000);
});

test("waitUntilReady without args uses DEFAULT_READY_MS", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const transport: SidecarTransport = {
    writeLine() {},
    onLine() {},
    async close() {},
  };
  const c = new SidecarClient(transport);
  const readyPromise = c.waitUntilReady();
  t.mock.timers.tick(DEFAULT_READY_MS - 1);
  await Promise.resolve();
  t.mock.timers.tick(1);
  await assert.rejects(readyPromise, /readiness timed out/i);
  t.mock.timers.reset();
});

test("decide returns delay decision after ready", async () => {
  const t = new MemoryTransport();
  const c = new SidecarClient(t, { decideMs: 200 });
  t.push({ op: "ready" });
  await c.waitUntilReady(200);
  const d = await c.decide("GET", "https://api.example.com/x");
  assert.equal(d.instruction, "INJECT_DELAY");
  assert.equal(d.delayMs, 50);
});

test("metrics returns counters", async () => {
  const t = new MemoryTransport();
  const c = new SidecarClient(t, { decideMs: 200 });
  t.push({ op: "ready" });
  await c.waitUntilReady(200);
  const m = await c.metrics();
  assert.deepEqual(m, {
    rules: { "always-delay": { matchCount: 1, triggerCount: 1 } },
  });
});

test("decide times out", async () => {
  const t: SidecarTransport = {
    writeLine() {},
    onLine() {},
    async close() {},
  };
  const c = new SidecarClient(t, { decideMs: 20 });
  await assert.rejects(
    () => c.decide("GET", "https://x"),
    /timed? ?out/i,
  );
});

test("decide rejects a protocol error", async () => {
  const ee = new EventEmitter();
  const t: SidecarTransport = {
    writeLine(line) {
      const { id } = JSON.parse(line);
      ee.emit("line", JSON.stringify({ id, error: "invalid request" }));
    },
    onLine(handler) {
      ee.on("line", handler);
    },
    async close() {},
  };
  const c = new SidecarClient(t);
  await assert.rejects(
    () => c.decide("GET", "https://x"),
    /invalid request/,
  );
});

test("control requests use incrementing string ids and shutdown closes", async () => {
  const t = new MemoryTransport();
  const c = new SidecarClient(t);
  await c.setEnabled(false);
  await c.shutdown();

  assert.deepEqual(JSON.parse(t.writes[0]), {
    op: "setEnabled",
    enabled: false,
    id: "1",
  });
  assert.deepEqual(JSON.parse(t.writes[1]), {
    op: "shutdown",
    id: "2",
  });
  assert.equal(t.closed, true);
});

test("shutdown closes the transport when the shutdown request times out", async () => {
  let closed = false;
  const transport: SidecarTransport = {
    writeLine() {},
    onLine() {},
    async close() {
      closed = true;
    },
  };
  const client = new SidecarClient(transport, { decideMs: 20 });

  await assert.rejects(() => client.shutdown(), /shutdown request timed out/i);

  assert.equal(closed, true);
});
