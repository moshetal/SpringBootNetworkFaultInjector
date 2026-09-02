import assert from "node:assert/strict";
import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
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

test("start closes a spawned transport when readiness times out", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "fault-injector-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const jar = join(directory, "sidecar.jar");
  const java = join(directory, "fake-java");
  const pidFile = join(directory, "pid");
  await writeFile(jar, "");
  await writeFile(
    java,
    `#!/bin/sh
echo $$ > ${JSON.stringify(pidFile)}
while :; do :; done
`,
  );
  await chmod(java, 0o755);
  t.after(async () => {
    try {
      process.kill(Number(await readFile(pidFile, "utf8")));
    } catch {
      // The expected close already terminated the child.
    }
  });

  await assert.rejects(
    () =>
      FaultInjector.start({
        configPath: "config.yaml",
        jar,
        java,
        timeouts: { readyMs: 500 },
      }),
    /readiness timed out/i,
  );

  const pid = Number(await readFile(pidFile, "utf8"));
  let running = true;
  for (let attempt = 0; attempt < 50 && running; attempt += 1) {
    try {
      process.kill(pid, 0);
      await delay(10);
    } catch {
      running = false;
    }
  }
  assert.equal(running, false);
});
