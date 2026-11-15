// 与 relay 约定的信封格式；payload 由 agent / app 自行解释。
export type Kind = 'request' | 'response' | 'event' | 'ping';

export interface Envelope {
  kind: Kind;
  id?: string;
  seq?: number;
  payload?: unknown;
}

// ---- app -> agent (request) ----

export type RequestPayload =
  | { op: 'workspaces' }
  | { op: 'setRoots'; roots: string[] }
  | { op: 'list'; root: string; path: string }
  | { op: 'read'; root: string; path: string }
  | { op: 'session.start'; root: string; prompt: string; model?: string }
  | { op: 'session.prompt'; session: string; text: string }
  | { op: 'session.interrupt'; session: string }
  | { op: 'session.list' }
  | { op: 'session.history'; session: string }
  | { op: 'session.kill'; session: string };

// ---- agent -> app (event) ----

export type EventPayload =
  | { type: 'status'; connected: boolean; detail?: string }
  | { type: 'session.started'; session: string; root: string }
  | { type: 'session.delta'; session: string; text: string }
  | { type: 'session.tool'; session: string; name: string; detail: string }
  | { type: 'session.result'; session: string; text: string; costUsd?: number; durationMs?: number }
  | { type: 'session.error'; session: string; message: string }
  | { type: 'session.ended'; session: string; code: number | null };
