import { EventEmitter } from "node:events";
import type { SidecarTransport } from "../sidecar-client.ts";

type Message = Record<string, unknown>;
type Responder = (message: Message, transport: MemoryTransport) => void;

export class MemoryTransport implements SidecarTransport {
  readonly writes: string[] = [];
  closed = false;
  private readonly ee = new EventEmitter();

  constructor(private readonly responder: Responder = defaultResponder) {}

  writeLine(line: string): void {
    this.writes.push(line);
    this.responder(JSON.parse(line) as Message, this);
  }

  onLine(handler: (line: string) => void): void {
    this.ee.on("line", handler);
  }

  push(obj: unknown): void {
    this.ee.emit("line", JSON.stringify(obj));
  }

  async close(): Promise<void> {
    this.closed = true;
  }
}

function defaultResponder(message: Message, transport: MemoryTransport): void {
  if (message.op === "decide") {
    transport.push({
      id: message.id,
      instruction: "INJECT_DELAY",
      delayMs: 50,
      ruleName: "always-delay",
    });
  } else if (message.op === "metrics") {
    transport.push({
      id: message.id,
      rules: { "always-delay": { matchCount: 1, triggerCount: 1 } },
    });
  } else if (message.op === "setEnabled" || message.op === "shutdown") {
    transport.push({ id: message.id, ok: true });
  }
}
