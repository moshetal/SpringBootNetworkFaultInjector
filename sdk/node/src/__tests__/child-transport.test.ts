import assert from "node:assert/strict";
import { once } from "node:events";
import test from "node:test";
import { ChildProcessTransport } from "../child-transport.ts";

test("handles stdin pipe errors after the child pipe closes", async (t) => {
  const transport = new ChildProcessTransport(process.execPath, [
    "-e",
    "setInterval(() => {}, 1000)",
  ]);
  void transport.startupFailure.catch(() => {});
  t.after(() => transport.close());
  await once(transport.child, "spawn");

  transport.child.stdin.destroy();

  assert.doesNotThrow(() => {
    transport.child.stdin.emit(
      "error",
      Object.assign(new Error("broken pipe"), { code: "EPIPE" }),
    );
  });
  assert.doesNotThrow(() => transport.writeLine('{"op":"decide"}'));
});
