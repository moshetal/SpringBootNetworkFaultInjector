import http from "node:http";
import { fileURLToPath } from "node:url";
import axios from "axios";
import { FaultInjector } from "fault-injector-sdk";

const server = http.createServer((_request, response) => {
  response.writeHead(200, { "content-type": "text/plain" });
  response.end("ok");
});

await new Promise((resolve, reject) => {
  server.once("error", reject);
  server.listen(0, "127.0.0.1", resolve);
});

const address = server.address();
if (!address || typeof address === "string") {
  throw new Error("Demo server did not bind to a TCP port");
}

const baseUrl = `http://127.0.0.1:${address.port}`;
const configPath = fileURLToPath(
  new URL("./fault-injection.yml", import.meta.url),
);
let faultInjector;

try {
  faultInjector = await FaultInjector.start({ configPath });
  faultInjector.patchFetch(globalThis);
  faultInjector.patchAxios(axios);

  const startedAt = performance.now();
  const slowResponse = await fetch(`${baseUrl}/slow`);
  const elapsedMs = Math.round(performance.now() - startedAt);
  console.log(`fetch GET /slow: ${slowResponse.status} in ${elapsedMs}ms`);

  try {
    await axios.get(`${baseUrl}/flaky`);
  } catch (error) {
    const status =
      error && typeof error === "object" ? error.response?.status : undefined;
    if (typeof status !== "number") {
      throw error;
    }
    console.log(`axios GET /flaky: ${status}`);
  }

  console.log("metrics:", JSON.stringify(await faultInjector.metrics()));
} finally {
  try {
    await faultInjector?.stop();
  } finally {
    await new Promise((resolve, reject) => {
      server.close((error) => (error ? reject(error) : resolve()));
    });
  }
}
