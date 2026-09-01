import type { SidecarClient } from "./sidecar-client.js";

export function patchFetch(
  client: SidecarClient,
  target: typeof globalThis,
): () => void {
  const originalFetch = target.fetch;

  target.fetch = async (input, init) => {
    const method =
      init?.method ?? (input instanceof Request ? input.method : "GET");
    const url =
      typeof input === "string" || input instanceof URL
        ? String(input)
        : input.url;

    let decision;
    try {
      decision = await client.decide(method, url);
    } catch (error) {
      console.warn("Fault injector decision failed; request will proceed", error);
      return originalFetch.call(target, input, init);
    }

    if (
      decision.instruction === "INJECT_DELAY" ||
      decision.instruction === "INJECT_DELAY_AND_ERROR"
    ) {
      await sleep(decision.delayMs ?? 0);
    }

    if (
      decision.instruction === "INJECT_ERROR" ||
      decision.instruction === "INJECT_DELAY_AND_ERROR"
    ) {
      return new Response(decision.errorMessage ?? "", {
        status: decision.errorStatus ?? 503,
      });
    }

    return originalFetch.call(target, input, init);
  };

  return () => {
    target.fetch = originalFetch;
  };
}

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
