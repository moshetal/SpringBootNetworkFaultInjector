export type Instruction =
  | "PASS"
  | "INJECT_DELAY"
  | "INJECT_ERROR"
  | "INJECT_DELAY_AND_ERROR";

export interface Decision {
  instruction: Instruction;
  delayMs?: number;
  errorStatus?: number;
  errorMessage?: string;
  ruleName?: string;
}

export interface MetricsSnapshot {
  rules: Record<string, { matchCount: number; triggerCount: number }>;
}

export interface SidecarTransport {
  writeLine(line: string): void;
  onLine(handler: (line: string) => void): void;
  close(): Promise<void>;
}
