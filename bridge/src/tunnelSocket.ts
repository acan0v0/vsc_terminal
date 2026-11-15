/**
 * 把一个已连通的 ssh2 通道包装成标准 Duplex，并在其上完成 WebSocket 的 HTTP Upgrade 握手。
 *
 * 为什么不用 http.request({ createConnection })：
 * 该选项依赖 Node 的 http.Agent 行为，在 VSCode 扩展宿主（Electron 内置 Node）里会绕过
 * 自定义连接函数，直接去连 URL 上的地址，报 AggregateError ECONNREFUSED。
 * 这里自己拼请求、自己解析响应头，不依赖任何网络模块的分支行为。
 */
import { createHash, randomBytes } from 'crypto';
import { Duplex } from 'stream';
import type { ClientChannel } from 'ssh2';

const ACCEPT_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

export type TunnelDuplex = Duplex & {
  remoteAddress?: string;
  setNoDelay?: (v?: boolean) => void;
  setKeepAlive?: (v?: boolean) => void;
};

export interface UpgradeResult {
  stream: TunnelDuplex;
  /** 握手后紧随响应头到达的数据（可能为空） */
  head: Buffer;
}

/**
 * 在 SSH 通道上发起 HTTP Upgrade。成功后返回可交给 ws 的流和残留数据。
 * 失败（非 101 或头部非法）时抛错，并携带服务器返回的文本便于排查。
 */
export function upgradeOnChannel(
  channel: ClientChannel,
  opts: { path: string; hostHeader: string; token: string; timeoutMs?: number },
): Promise<UpgradeResult> {
  return new Promise((resolve, reject) => {
    const stream = Duplex.from({ readable: channel, writable: channel }) as TunnelDuplex;
    // 通道是 Duplex.from 的源流，需尽早监听 error，否则通道断开时会抛出未处理的 'error'
    channel.on('error', (e: Error) => {
      if (stream.listenerCount('error') > 0) stream.emit('error', e);
    });
    channel.on('close', () => {
      if (!stream.destroyed) stream.destroy();
    });
    stream.remoteAddress = '127.0.0.1';
    stream.setNoDelay = () => undefined;
    stream.setKeepAlive = () => undefined;

    const key = randomBytes(16).toString('base64');
    const request =
      `GET ${opts.path} HTTP/1.1\r\n` +
      `Host: ${opts.hostHeader}\r\n` +
      `Upgrade: websocket\r\n` +
      `Connection: Upgrade\r\n` +
      `Sec-WebSocket-Version: 13\r\n` +
      `Sec-WebSocket-Key: ${key}\r\n` +
      `Authorization: Bearer ${opts.token}\r\n` +
      `\r\n`;

    let buffer = Buffer.alloc(0);
    let settled = false;

    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      cleanup();
      stream.destroy();
      reject(new Error(`upgrade timeout after ${opts.timeoutMs ?? 10000}ms`));
    }, opts.timeoutMs ?? 10000);

    const cleanup = () => {
      clearTimeout(timer);
      stream.removeListener('data', onData);
      stream.removeListener('error', onError);
      stream.removeListener('close', onClose);
    };

    const onData = (chunk: Buffer) => {
      if (settled) return;
      buffer = Buffer.concat([buffer, chunk]);
      const end = buffer.indexOf('\r\n\r\n');
      if (end < 0) {
        if (buffer.length > 64 * 1024) {
          settled = true;
          cleanup();
          stream.destroy();
          reject(new Error('upgrade response headers too large'));
        }
        return;
      }

      settled = true;
      cleanup();
      // 先不 unshift：ws 的 setSocket 会把外层 head 参数写入接收器，
      // 若这里再放回流中会导致同一批字节被解析两次。

      const headerText = buffer.subarray(0, end).toString('latin1');
      const lines = headerText.split('\r\n');
      const statusLine = lines[0] ?? '';
      const match = /^HTTP\/1\.1 (\d{3})/i.exec(statusLine);
      const status = match ? Number(match[1]) : 0;
      const headers = new Map<string, string>();
      for (const line of lines.slice(1)) {
        const i = line.indexOf(':');
        if (i > 0) headers.set(line.slice(0, i).trim().toLowerCase(), line.slice(i + 1).trim());
      }

      if (status !== 101) {
        const body = buffer.subarray(end + 4).toString('utf8').slice(0, 300);
        stream.destroy();
        reject(new Error(`relay rejected upgrade: HTTP ${status} ${body}`));
        return;
      }

      const expected = createHash('sha1').update(key + ACCEPT_GUID).digest('base64');
      if (headers.get('sec-websocket-accept') !== expected) {
        stream.destroy();
        reject(new Error('invalid Sec-WebSocket-Accept'));
        return;
      }

      resolve({ stream, head: buffer.subarray(end + 4) });
    };

    const onError = (e: Error) => {
      if (settled) return;
      settled = true;
      cleanup();
      reject(e);
    };
    const onClose = () => {
      if (settled) return;
      settled = true;
      cleanup();
      reject(new Error('ssh channel closed during upgrade'));
    };

    // 永久监听：握手结束后由调用方接手，但流上的错误不能变成未处理异常
    stream.on('error', () => undefined);
    stream.on('data', onData);
    stream.on('error', onError);
    stream.on('close', onClose);
    stream.write(request);
  });
}

