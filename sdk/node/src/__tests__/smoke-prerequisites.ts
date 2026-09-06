import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";

export function smokeTestSkipReason(jarPath: string): string | false {
  if (!existsSync(jarPath)) {
    return "sidecar jar has not been packaged";
  }

  const javaBin = process.env.FAULT_INJECTOR_JAVA ?? "java";
  const result = spawnSync(javaBin, ["-version"], { stdio: "ignore" });
  if (result.error ?? result.status !== 0) {
    return `java is not available (${javaBin})`;
  }

  return false;
}
