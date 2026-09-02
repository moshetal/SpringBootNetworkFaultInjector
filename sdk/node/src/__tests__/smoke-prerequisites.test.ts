import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { smokeTestSkipReason } from "./smoke-prerequisites.ts";

test("smokeTestSkipReason skips when the jar is missing", () => {
  assert.equal(
    smokeTestSkipReason("/tmp/fault-injector-missing-jar.test"),
    "sidecar jar has not been packaged",
  );
});

test("smokeTestSkipReason skips when java is unavailable", () => {
  const previous = process.env.FAULT_INJECTOR_JAVA;
  process.env.FAULT_INJECTOR_JAVA = "/tmp/fault-injector-missing-java.test";

  try {
    assert.match(
      smokeTestSkipReason(fileURLToPath(import.meta.url)),
      /^java is not available/,
    );
  } finally {
    if (previous === undefined) {
      delete process.env.FAULT_INJECTOR_JAVA;
    } else {
      process.env.FAULT_INJECTOR_JAVA = previous;
    }
  }
});
