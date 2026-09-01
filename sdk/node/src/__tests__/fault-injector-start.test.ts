import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { FaultInjector } from "../fault-injector.ts";
import { SidecarClient } from "../sidecar-client.ts";
import { MemoryTransport } from "./memory-transport.ts";

test("start accepts a ready injected client and delegates control methods", async () => {
  const transport = new MemoryTransport();
  const client = new SidecarClient(transport);
  transport.push({ op: "ready" });

  const injector = await FaultInjector.start({
    configPath: "unused.yaml",
    client,
  });

  assert.equal(
    (await injector.metrics()).rules["always-delay"].triggerCount,
    1,
  );
  await injector.setEnabled(false);
  assert.throws(() => injector.patchAxios({} as never), /not implemented/i);
  await injector.stop();
  assert.equal(transport.closed, true);
});

test("start applies the configured readiness timeout to an injected client", async () => {
  const client = new SidecarClient(
    new MemoryTransport(() => {}),
    { decideMs: 20 },
  );

  await assert.rejects(
    () =>
      FaultInjector.start({
        configPath: "unused.yaml",
        client,
        timeouts: { readyMs: 5 },
      }),
    /readiness timed out/i,
  );
});

test("start rejects a missing sidecar jar before spawning", async () => {
  await assert.rejects(
    () =>
      FaultInjector.start({
        configPath: "config.yaml",
        jar: join(tmpdir(), "definitely-missing-sidecar.jar"),
      }),
    /sidecar jar.*does not exist/i,
  );
});

test("start reports Java spawn errors", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "fault-injector-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const jar = join(directory, "sidecar.jar");
  await writeFile(jar, "");

  await assert.rejects(
    () =>
      FaultInjector.start({
        configPath: "config.yaml",
        jar,
        java: join(directory, "missing-java"),
        timeouts: { readyMs: 100 },
      }),
    /failed to spawn.*missing-java/i,
  );
});

test("start reports stderr when the child exits before ready", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "fault-injector-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const jar = join(directory, "sidecar.jar");
  await writeFile(jar, "");

  await assert.rejects(
    () =>
      FaultInjector.start({
        configPath: "config.yaml",
        jar,
        java: process.execPath,
        timeouts: { readyMs: 500 },
      }),
    /exited before ready[\s\S]*-jar/i,
  );
});
