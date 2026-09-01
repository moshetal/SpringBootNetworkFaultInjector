import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";
import { SidecarClient, type SidecarTransport } from "../sidecar-client.ts";

class MemoryTransport implements SidecarTransport {
  readonly writes: string[] = [];
  closed = false;
  private readonly ee = new EventEmitter();

  writeLine(line: string) {
    this.writes.push(line);
    const msg = JSON.parse(line);
    if (msg.op === "decide") {
      this.push({
        id: msg.id,
        instruction: "INJECT_DELAY",
        delayMs: 50,
        ruleName: "always-delay",
      });
    } else if (msg.op === "metrics") {
      this.push({
        id: msg.id,
        rules: { "always-delay": { matchCount: 1, triggerCount: 1 } },
      });
    } else if (msg.op === "setEnabled" || msg.op === "shutdown") {
      this.push({ id: msg.id, ok: true });
    }
  }

  onLine(handler: (line: string) => void) {
    this.ee.on("line", handler);
  }

  push(obj: unknown) {
    this.ee.emit("line", JSON.stringify(obj));
  }

  async close() {
    this.closed = true;
  }
}

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
  assert.equal(m.rules["always-delay"].triggerCount, 1);
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
