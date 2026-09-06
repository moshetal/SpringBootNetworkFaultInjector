import assert from "node:assert/strict";
import { createServer } from "node:http";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { FaultInjector } from "../fault-injector.ts";
import { smokeTestSkipReason } from "./smoke-prerequisites.ts";

const jar = fileURLToPath(
  new URL("../../sidecar/fault-injector-sidecar.jar", import.meta.url),
);
const configPath = fileURLToPath(new URL("./fixtures/smoke.yml", import.meta.url));

test(
  "real sidecar delays a local fetch and reports the trigger",
  { skip: smokeTestSkipReason(jar) },
  async (t) => {
    let requestReceived = false;
    const server = createServer((_request, response) => {
      requestReceived = true;
      response.end("ok");
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    t.after(() => new Promise<void>((resolve) => server.close(() => resolve())));

    const address = server.address();
    assert(address && typeof address !== "string");

    const injector = await FaultInjector.start({ configPath, jar });
    t.after(() => injector.stop());
    injector.patchFetch(globalThis);

    const startedAt = Date.now();
    const response = await fetch(`http://127.0.0.1:${address.port}/smoke`);
    const elapsed = Date.now() - startedAt;

    assert.equal(await response.text(), "ok");
    assert.equal(requestReceived, true);
    assert(elapsed >= 70, `expected at least 70ms delay, observed ${elapsed}ms`);
    assert(
      (await injector.metrics()).rules["smoke-delay"].triggerCount >= 1,
      "expected the smoke-delay rule to trigger",
    );
  },
);
