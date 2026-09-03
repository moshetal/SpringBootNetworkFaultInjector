# fault-injector-sdk (Node)

A proof-of-concept Node.js SDK for the Spring Boot Network Fault Injector.

## Why this exists

The fault-injection engine and its rule model are implemented in Java, but they
are **not Spring-only**. This SDK demonstrates that any runtime can reuse the
exact same engine and the same `fault.injection` YAML configuration by speaking
the sidecar's line-oriented stdio protocol.

The SDK launches the packaged sidecar jar as a child process
(`java -jar fault-injector-sidecar.jar --config <yaml>`) and exchanges
newline-delimited JSON messages over stdin/stdout. The protocol is small:

| Op          | Direction        | Purpose                                          |
| ----------- | ---------------- | ------------------------------------------------ |
| `ready`     | sidecar → client | emitted once the engine is initialized           |
| `decide`    | client → sidecar | ask whether to inject a fault for `method`+`url` |
| `metrics`   | client → sidecar | per-rule match/trigger counts                    |
| `setEnabled`| client → sidecar | toggle injection on/off at runtime               |
| `shutdown`  | client → sidecar | stop the engine and close the process            |

Because the decision logic lives entirely in the Java engine, a Node process
gets identical behaviour to a Spring app pointed at the same YAML — the SDK only
patches `fetch` / `axios` to consult the sidecar before each request.

## Scope

This is a POC of cross-runtime reuse, not a feature-complete client.

**In scope**

- Spawning and driving the Java sidecar over the stdio JSON-lines protocol.
- Reusing the Java engine and the shared `fault.injection` YAML unchanged.
- Patching `fetch` and `axios` to apply `DELAY` / `ERROR` faults.
- Reading per-rule trigger metrics.

**Deliberately out of scope**

- Runtime rule editing, a local UI, and the Spring actuator surface.
- Cluster mode and central console visibility.
- Resilience signals (retry/circuit-breaker/bulkhead telemetry).

## Requirements

- Node.js 18+
- Java 17+
- The sidecar jar, built from the repository root:

  ```bash
  mvn -pl fault-injector-sidecar -am package -DskipTests
  ```

  The build copies the jar to `sdk/node/sidecar/fault-injector-sidecar.jar`,
  where the SDK looks for it by default (override with `FAULT_INJECTOR_SIDECAR_JAR`).

## Usage

```js
import axios from "axios";
import { FaultInjector } from "fault-injector-sdk";

const injector = await FaultInjector.start({ configPath: "./fault-injection.yml" });

// Route fetch and/or axios traffic through the fault-injection engine.
injector.patchFetch(globalThis);
injector.patchAxios(axios);

try {
  // Requests matching an ERROR rule reject with a real AxiosError:
  //   axios.isAxiosError(err) === true, err.code, err.config, err.response are set.
  await axios.get("https://api.example.com/flaky");
} catch (error) {
  if (axios.isAxiosError(error)) {
    console.log(error.response?.status); // e.g. 503
  }
}

console.log(await injector.metrics());
await injector.stop();
```

See `examples/fault-injector-node-demo` for a runnable end-to-end demo
(`make demo-node` from the repository root).
