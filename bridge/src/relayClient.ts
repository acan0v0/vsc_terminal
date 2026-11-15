import { randomBytes } from 'crypto';
import type { Duplex } from 'stream';
import type { ClientChannel } from 'ssh2';
import { Client } from 'ssh2';
import * as vscode from 'vscode';
import WebSocket from 'ws';
import { upgradeOnChannel } from './tunnelSocket';

/** 生成 4 字节随机掩码（等价于 ws 客户端内置实现） */
function randomMask(): Buffer {
  return randomBytes(4);
}

export interface TunnelOptions {
  host: string;
  port: number;
  user: string;
  password: string;
  relayPort: number;
  room: string;
  token: string;
}

export type ConnEvent = { type: 'status'; connected: boolean; detail?: string } | { type: 'message'; data: string };

const RECONNECT_MAX = 30_000;
const MAX_PAYLOAD = 16 * 1024 * 1024;

/**
 * 通过 SSH 隧道连到 VPS 上的 relay：
 *   ssh2.forwardOut → 手动 HTTP Upgrade 握手 → ws.setSocket() 接管通道
 *
 * 不读取 ~/.ssh/config：地址/端口/账号都来自设置，密码来自 SecretStorage。
 */
export class RelayClient implements vscode.Disposable {
  private ssh?: Client;
  private ws?: WebSocket;
  private reconnectTimer?: NodeJS.Timeout;
  private attempt = 0;
  private disposed = false;
  /** 每次新建连接递增；旧连接的回调通过它判断自己是否已过期 */
  private generation = 0;

  constructor(
    private readonly log: vscode.OutputChannel,
    private readonly opts: TunnelOptions,
    private readonly onEvent: (e: ConnEvent) => void,
  ) {}

  start(): void {
    this.connect();
  }

  private connect(): void {
    if (this.disposed) return;
    const gen = ++this.generation;
    const isCurrent = () => gen === this.generation && !this.disposed;

    this.log.appendLine(`[ssh] connecting ${this.opts.user}@${this.opts.host}:${this.opts.port}`);
    const ssh = new Client();
    this.ssh = ssh;

    ssh.on('ready', () => {
      if (!isCurrent()) {
        ssh.end();
        return;
      }
      this.log.appendLine('[ssh] ready, opening tunnel');
      ssh.forwardOut('127.0.0.1', 0, '127.0.0.1', this.opts.relayPort, (err, channel) => {
        if (!isCurrent()) {
          channel?.close();
          return;
        }
        if (err) {
          this.log.appendLine(`[ssh] forwardOut failed: ${err.message}`);
          this.onEvent({ type: 'status', connected: false, detail: `forwardOut: ${err.message}` });
          ssh.end();
          return;
        }
        void this.openWs(channel, isCurrent);
      });
    });

    ssh.on('error', (err) => {
      if (!isCurrent()) return;
      this.log.appendLine(`[ssh] error: ${err.message}`);
      this.onEvent({ type: 'status', connected: false, detail: `ssh: ${err.message}` });
    });

    ssh.on('close', () => {
      if (!isCurrent()) {
        this.log.appendLine('[ssh] stale connection closed');
        return;
      }
      this.log.appendLine('[ssh] closed');
      this.scheduleReconnect();
    });

    ssh.connect({
      host: this.opts.host,
      port: this.opts.port,
      username: this.opts.user,
      password: this.opts.password,
      readyTimeout: 15000,
      keepaliveInterval: 15000,
      keepaliveCountMax: 3,
      algorithms: {
        kex: [
          'curve25519-sha256',
          'curve25519-sha256@libssh.org',
          'ecdh-sha2-nistp256',
          'ecdh-sha2-nistp384',
          'ecdh-sha2-nistp521',
          'diffie-hellman-group-exchange-sha256',
          'diffie-hellman-group14-sha256',
        ],
        serverHostKey: ['ssh-ed25519', 'ecdsa-sha2-nistp256', 'rsa-sha2-512', 'rsa-sha2-256'],
      },
    });
  }

  private async openWs(channel: ClientChannel, isCurrent: () => boolean): Promise<void> {
    channel.on('close', () => this.log.appendLine('[ssh] channel closed'));
    channel.on('error', (e: Error) => this.log.appendLine(`[ssh] channel error: ${e.message}`));

    const requestPath = `/ws?room=${encodeURIComponent(this.opts.room)}&role=agent`;
    try {
      const { stream, head } = await upgradeOnChannel(channel, {
        path: requestPath,
        hostHeader: `127.0.0.1:${this.opts.relayPort}`,
        token: this.opts.token,
      });
      if (!isCurrent()) {
        stream.destroy();
        return;
      }
      this.log.appendLine('[ws] handshake ok 101');
      stream.on('error', (e: Error) => this.log.appendLine(`[tunnel] stream error: ${e.message}`));
      this.adoptSocket(stream, head, isCurrent);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      this.log.appendLine(`[ws] upgrade failed: ${msg}`);
      if (!isCurrent()) return;
      this.onEvent({ type: 'status', connected: false, detail: msg.slice(0, 120) });
      this.scheduleReconnect();
    }
  }

  /** 握手已完成：用一个不自行连接的 ws 实例接管该 socket。 */
  private adoptSocket(socket: Duplex, head: Buffer, isCurrent: () => boolean): void {
    // address=null → 不发起连接；options 必须放在第三个参数（ws 8.x 的构造签名）
    const ws = new WebSocket(null as unknown as string, [], {
      allowSynchronousEvents: true,
      autoPong: true,
      maxPayload: MAX_PAYLOAD,
      perMessageDeflate: false,
    });

    ws.on('open', () => {
      if (!isCurrent()) {
        ws.terminate();
        return;
      }
      this.log.appendLine('[ws] connected to relay');
      this.attempt = 0;
      this.onEvent({ type: 'status', connected: true });
    });
    ws.on('message', (data) => this.onEvent({ type: 'message', data: data.toString() }));
    ws.on('error', (err) => this.log.appendLine(`[ws] error: ${err.message}`));
    ws.on('close', () => {
      if (!isCurrent()) return;
      this.log.appendLine('[ws] closed');
      this.onEvent({ type: 'status', connected: false });
      this.scheduleReconnect();
    });
    this.ws = ws;
    // address=null 构造出的实例带服务端角色，会导致收发规则错位（要求对端加掩码/自己不掩码）
    (ws as unknown as { _isServer: boolean })._isServer = false;

    (ws as unknown as { setSocket(s: unknown, h: Buffer, o: Record<string, unknown>): void }).setSocket(socket, head, {
      allowSynchronousEvents: true,
      // 客户端必须对帧加掩码
      generateMask: randomMask,
      maxBufferedChunks: 256 * 1024,
      maxFragments: 16 * 1024,
      maxPayload: MAX_PAYLOAD,
      skipUTF8Validation: false,
    });
  }

  private scheduleReconnect(): void {
    if (this.disposed || this.reconnectTimer) return;
    // 立即让当前连接失效，后续的 error/close 回调因代次不匹配而不再排队重连
    this.generation++;
    this.attempt++;
    const delay = Math.min(RECONNECT_MAX, 1000 * 2 ** Math.min(this.attempt, 5));
    this.log.appendLine(`[conn] retry in ${delay}ms (attempt ${this.attempt})`);
    this.teardown();
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined;
      this.connect();
    }, delay);
  }

  send(kind: 'event' | 'response', id: string | undefined, payload: unknown): void {
    if (this.ws?.readyState !== WebSocket.OPEN) return;
    this.ws.send(JSON.stringify({ kind, id, payload }));
  }

  private teardown(): void {
    try {
      this.ws?.terminate();
    } catch {
      /* ignore */
    }
    try {
      this.ssh?.end();
    } catch {
      /* ignore */
    }
    this.ws = undefined;
    this.ssh = undefined;
  }

  dispose(): void {
    this.disposed = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.teardown();
  }
}
