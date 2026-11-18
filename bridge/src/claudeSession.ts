import { spawn, ChildProcessWithoutNullStreams } from 'child_process';
import { randomUUID } from 'crypto';
import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { EventPayload } from './protocol';

/** 定位 claude 可执行文件：优先用户设置，其次 VSCode 扩展内置，最后 PATH。 */
export function resolveClaudePath(setting: string): string {
  if (setting && fs.existsSync(setting)) return setting;

  const extRoots = [
    path.join(vscode.env.appRoot, '..', '..', 'extensions'),
    path.join(process.env.USERPROFILE ?? '', '.vscode', 'extensions'),
    path.join(process.env.HOME ?? '', '.vscode', 'extensions'),
  ];
  const exe = process.platform === 'win32' ? 'claude.exe' : 'claude';

  for (const root of extRoots) {
    let names: string[];
    try {
      names = fs.readdirSync(root);
    } catch {
      continue;
    }
    // 按名字排序取版本号最大的一个
    const candidates = names
      .filter((n) => n.startsWith('anthropic.claude-code-'))
      .sort()
      .reverse();
    for (const name of candidates) {
      const p = path.join(root, name, 'resources', 'native-binary', exe);
      if (fs.existsSync(p)) return p;
    }
  }
  return exe; // 兜底：交给 PATH
}

export interface SessionSink {
  (ev: EventPayload): void;
}

interface Turn {
  text: string;
  tools: string[];
}

/** 文本增量的合并窗口：流式输出按 token 到达，逐条发送会把 UI 和隧道压满。 */
const DELTA_FLUSH_MS = 75;

/**
 * 一个常驻的 claude 进程，通过 stream-json 双向通信。
 * 用 --input-format stream-json 保持进程存活，因此同一会话可以多轮对话。
 */
export class ClaudeSession {
  readonly id = randomUUID();
  private proc?: ChildProcessWithoutNullStreams;
  private buffer = '';
  private turn: Turn = { text: '', tools: [] };
  private started = false;
  private busy = false;
  private readonly history: { role: 'user' | 'assistant'; text: string }[] = [];
  /** 尚未发出的文本增量，定时合并后一次性 emit */
  private pendingDelta = '';
  private flushTimer?: NodeJS.Timeout;

  constructor(
    readonly root: string,
    private readonly claudePath: string,
    private readonly model: string | undefined,
    private readonly emit: SessionSink,
    private readonly log: (msg: string) => void,
  ) {}

  get isRunning(): boolean {
    return !!this.proc && this.proc.exitCode === null;
  }

  start(firstPrompt: string): void {
    if (this.started) throw new Error('session already started');
    this.started = true;

    const args = [
      '--print',
      '--output-format', 'stream-json',
      '--input-format', 'stream-json',
      '--verbose',
      '--include-partial-messages',
    ];
    if (this.model) args.push('--model', this.model);

    this.log(`spawn ${this.claudePath} in ${this.root}`);
    const proc = spawn(this.claudePath, args, {
      cwd: this.root,
      env: { ...process.env, CLAUDE_CODE_ENTRYPOINT: 'sdk-ts' },
      windowsHide: true,
    });
    this.proc = proc;

    proc.stdout.setEncoding('utf8');
    proc.stdout.on('data', (chunk: string) => this.onStdout(chunk));

    proc.stderr.setEncoding('utf8');
    proc.stderr.on('data', (chunk: string) => {
      const line = chunk.trim();
      if (line) this.log(`[stderr] ${line}`);
    });

    proc.on('error', (err) => {
      this.flushDelta();
      this.emit({ type: 'session.error', session: this.id, message: err.message });
    });

    proc.on('exit', (code) => {
      // 先把缓冲里的文字发出去，否则最后一段会随 ended 一起丢掉
      this.flushDelta();
      this.emit({ type: 'session.ended', session: this.id, code });
    });

    this.emit({ type: 'session.started', session: this.id, root: this.root });
    this.sendPrompt(firstPrompt);
  }

  sendPrompt(text: string): void {
    if (!this.proc?.stdin.writable) throw new Error('session not running');
    this.busy = true;
    this.history.push({ role: 'user', text });
    const msg = {
      type: 'user',
      message: { role: 'user', content: [{ type: 'text', text }] },
      parent_tool_use_id: null,
      session_id: this.id,
    };
    this.proc.stdin.write(JSON.stringify(msg) + '\n');
  }

  interrupt(): void {
    if (process.platform === 'win32') {
      // Windows 没有 POSIX 信号，claude 进程没有可直接用的中断通道；
      // 发一个 ESC 的等价物不存在，只能由上层提示用户。
      this.log('[interrupt] unsupported on win32');
      return;
    }
    this.proc?.kill('SIGINT');
  }

  kill(): void {
    this.flushDelta();
    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
      this.flushTimer = undefined;
    }
    this.proc?.kill();
  }

  /**
   * 文本增量先攒进缓冲区，按固定窗口合并成一条 session.delta。
   * 逐 token 发送会让手机上每秒几十次重组，而且隧道里全是碎包。
   */
  private queueDelta(text: string): void {
    this.pendingDelta += text;
    if (this.flushTimer) return;
    this.flushTimer = setTimeout(() => {
      this.flushTimer = undefined;
      this.flushDelta();
    }, DELTA_FLUSH_MS);
  }

  /** 立即发出缓冲的文字；工具/结果事件之前必须调用，保证顺序不乱、内容不丢。 */
  private flushDelta(): void {
    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
      this.flushTimer = undefined;
    }
    if (!this.pendingDelta) return;
    const text = this.pendingDelta;
    this.pendingDelta = '';
    this.emit({ type: 'session.delta', session: this.id, text });
  }

  getHistory() {
    return this.history.slice();
  }

  private onStdout(chunk: string): void {
    this.buffer += chunk;
    let nl: number;
    while ((nl = this.buffer.indexOf('\n')) >= 0) {
      const line = this.buffer.slice(0, nl).trim();
      this.buffer = this.buffer.slice(nl + 1);
      if (!line) continue;
      let obj: Record<string, unknown>;
      try {
        obj = JSON.parse(line);
      } catch {
        this.log(`[bad-json] ${line.slice(0, 200)}`);
        continue;
      }
      this.handleMessage(obj);
    }
  }

  private handleMessage(obj: Record<string, unknown>): void {
    const type = obj.type as string;

    if (type === 'stream_event') {
      const ev = obj.event as Record<string, unknown> | undefined;
      if (!ev) return;
      if (ev.type === 'content_block_delta') {
        const delta = ev.delta as { type?: string; text?: string } | undefined;
        if (delta?.type === 'text_delta' && delta.text) {
          this.turn.text += delta.text;
          this.queueDelta(delta.text);
        }
      }
      return;
    }

    if (type === 'assistant') {
      const msg = obj.message as { content?: unknown[] } | undefined;
      for (const block of msg?.content ?? []) {
        const b = block as { type?: string; text?: string; name?: string; input?: unknown };
        if (b.type === 'text' && b.text && !this.turn.text) {
          // 没有 partial 流时，用完整消息兜底（例如某些模型配置不发送 delta）
          this.turn.text += b.text;
          this.queueDelta(b.text);
        }
        if (b.type === 'tool_use' && b.name) {
          const detail = summarizeToolInput(b.name, b.input);
          this.turn.tools.push(b.name);
          // 工具事件必须排在这之前的文字之后
          this.flushDelta();
          this.emit({ type: 'session.tool', session: this.id, name: b.name, detail });
        }
      }
      return;
    }

    if (type === 'result') {
      const resultText = typeof obj.result === 'string' ? obj.result : this.turn.text;
      this.busy = false;
      // 先补齐文本，再发 result（App 侧会用 result 兜底空文本）
      this.flushDelta();
      this.history.push({ role: 'assistant', text: resultText });
      this.emit({
        type: 'session.result',
        session: this.id,
        text: resultText,
        costUsd: typeof obj.total_cost_usd === 'number' ? obj.total_cost_usd : undefined,
        durationMs: typeof obj.duration_ms === 'number' ? obj.duration_ms : undefined,
      });
      this.turn = { text: '', tools: [] };
      return;
    }

    if (type === 'system' && obj.subtype === 'init') {
      this.log(`[init] model=${obj.model} cwd=${obj.cwd}`);
    }
  }
}

function summarizeToolInput(name: string, input: unknown): string {
  if (!input || typeof input !== 'object') return '';
  const i = input as Record<string, unknown>;
  const pick = (k: string) => (typeof i[k] === 'string' ? (i[k] as string) : '');
  switch (name) {
    case 'Bash':
      return pick('command').slice(0, 200);
    case 'Read':
    case 'Write':
    case 'Edit':
      return pick('file_path');
    case 'Grep':
    case 'Glob':
      return [pick('pattern'), pick('path')].filter(Boolean).join(' @ ');
    case 'WebFetch':
    case 'WebSearch':
      return (pick('url') || pick('query')).slice(0, 200);
    default: {
      const s = JSON.stringify(i);
      return s.length > 160 ? s.slice(0, 160) + '…' : s;
    }
  }
}
