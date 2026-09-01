import {
  spawn,
  type ChildProcessWithoutNullStreams,
} from "node:child_process";
import type { SidecarTransport } from "./types.js";

const STDERR_LIMIT = 4096;

export class ChildProcessTransport implements SidecarTransport {
  readonly child: ChildProcessWithoutNullStreams;
  readonly startupFailure: Promise<never>;
  private readonly lineHandlers: Array<(line: string) => void> = [];
  private stdoutBuffer = "";
  private stderrBuffer = "";

  constructor(command: string, args: string[]) {
    this.child = spawn(command, args, {
      stdio: ["pipe", "pipe", "pipe"],
    });

    this.child.stdout.setEncoding("utf8");
    this.child.stdout.on("data", (chunk: string) => this.handleStdout(chunk));
    this.child.stdout.on("end", () => this.flushStdout());
    this.child.stderr.setEncoding("utf8");
    this.child.stderr.on("data", (chunk: string) => {
      this.stderrBuffer = (this.stderrBuffer + chunk).slice(-STDERR_LIMIT);
    });

    this.startupFailure = new Promise<never>((_, reject) => {
      this.child.once("error", (error) => {
        reject(new Error(`Failed to spawn ${command}: ${error.message}`));
      });
      this.child.once("exit", (code, signal) => {
        const outcome =
          code === null ? `signal ${signal ?? "unknown"}` : `code ${code}`;
        const stderr = this.stderrBuffer.trim();
        reject(
          new Error(
            `Sidecar exited before ready (${outcome})${
              stderr ? `\n${stderr}` : ""
            }`,
          ),
        );
      });
    });
  }

  writeLine(line: string): void {
    this.child.stdin.write(`${line}\n`);
  }

  onLine(handler: (line: string) => void): void {
    this.lineHandlers.push(handler);
  }

  async close(): Promise<void> {
    this.child.kill();
  }

  private handleStdout(chunk: string): void {
    this.stdoutBuffer += chunk;
    const lines = this.stdoutBuffer.split("\n");
    this.stdoutBuffer = lines.pop() ?? "";
    for (const line of lines) {
      this.emitLine(line.replace(/\r$/, ""));
    }
  }

  private flushStdout(): void {
    if (this.stdoutBuffer) {
      this.emitLine(this.stdoutBuffer.replace(/\r$/, ""));
      this.stdoutBuffer = "";
    }
  }

  private emitLine(line: string): void {
    for (const handler of this.lineHandlers) {
      handler(line);
    }
  }
}
