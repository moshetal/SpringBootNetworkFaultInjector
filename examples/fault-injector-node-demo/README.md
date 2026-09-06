# Node fault-injector demo

This demo starts a local HTTP server and uses the Node SDK to inject a delay
into a `fetch` request and a 503 response into an Axios request.

## Prerequisites

- Java 17+
- Maven
- Node.js 18+
- npm

From the repository root:

```bash
make demo-node
```

Without Make:

```bash
mvn -pl fault-injector-sidecar -am package -DskipTests
cd sdk/node && npm install && npm run build
cd ../../examples/fault-injector-node-demo && npm install && npm start
```

The output includes a successful `GET /slow` after approximately 500ms, an
injected 503 for `GET /flaky`, and per-rule trigger metrics.
