import type {
  AxiosInstance,
  InternalAxiosRequestConfig,
} from "axios";
import type { SidecarClient } from "./sidecar-client.js";

export function patchAxios(
  client: SidecarClient,
  instance: AxiosInstance,
): () => void {
  const interceptorId = instance.interceptors.request.use(async (config) => {
    try {
      const method = config.method?.toUpperCase() ?? "GET";
      const url = instance.getUri(config);
      const decision = await client.decide(method, url);

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
        return Promise.reject(
          createInjectedError(
            decision.errorStatus ?? 503,
            decision.errorMessage ?? "",
          ),
        );
      }
    } catch (error) {
      console.warn("Fault injector decision failed; request will proceed", error);
    }

    return config;
  });

  return () => instance.interceptors.request.eject(interceptorId);
}

interface InjectedError extends Error {
  response: {
    status: number;
    data: string;
  };
}

function createInjectedError(status: number, data: string): InjectedError {
  if (!Number.isInteger(status) || status < 100 || status > 599) {
    throw new RangeError(`Invalid injected HTTP status: ${status}`);
  }

  const error = new Error(data) as InjectedError;
  error.response = { status, data };
  return error;
}

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
