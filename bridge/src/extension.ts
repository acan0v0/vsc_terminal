import * as vscode from 'vscode';
import { ClaudeSession, resolveClaudePath } from './claudeSession';
import { Envelope, RequestPayload } from './protocol';
import { ConnEvent, RelayClient } from './relayClient';
import { assertRoot, listDir, readFile } from './workspaces';

// 敏感信息全部放在 SecretStorage（VSCode 用系统密钥链加密），不进 settings.json
const SECRET = {
  host: 'bridge.host',
  token: 'bridge.token',
  password: 'bridge.password',
} as const;

interface Endpoint {
  host: string;
  token: string;
  password: string;
}

let statusItem: vscode.StatusBarItem;
let log: vscode.OutputChannel;
let client: RelayClient | undefined;
let running = false;
const sessions = new Map<string, ClaudeSession>();

export function activate(ctx: vscode.ExtensionContext) {
  log = vscode.window.createOutputChannel('VSC Terminal Bridge');
  statusItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 50);
  statusItem.command = 'bridge.start';
  setStatus('未连接');
  statusItem.show();
  ctx.subscriptions.push(log, statusItem);

  ctx.subscriptions.push(
    vscode.commands.registerCommand('bridge.start', () => start(ctx)),
    vscode.commands.registerCommand('bridge.stop', () => stop()),
    vscode.commands.registerCommand('bridge.forget', () => forget(ctx)),
  );

  ctx.subscriptions.push({ dispose: () => stop() });
}

export function deactivate() {
  stop();
}

function setStatus(text: string, connected = false) {
  statusItem.text = `$(radio-tower) Bridge: ${text}`;
  statusItem.tooltip = connected ? '已连接中继' : '点击连接';
  statusItem.color = connected ? undefined : new vscode.ThemeColor('statusBarItem.warningForeground');
}

/** 从 SecretStorage 读取端点信息，缺哪项就弹框补哪项。 */
async function askEndpoint(ctx: vscode.ExtensionContext, forceAll: boolean): Promise<Endpoint | undefined> {
  const saved = {
    host: forceAll ? undefined : await ctx.secrets.get(SECRET.host),
    token: forceAll ? undefined : await ctx.secrets.get(SECRET.token),
    password: forceAll ? undefined : await ctx.secrets.get(SECRET.password),
  };

  const host = saved.host ?? (await vscode.window.showInputBox({
    prompt: 'VPS 地址（公网 IP）',
    placeHolder: '例如 203.0.113.10',
    ignoreFocusOut: true,
    validateInput: (v) => (/^\S+$/.test(v.trim()) ? undefined : '不能为空且不能含空格'),
  }));
  if (!host) return undefined;

  const token = saved.token ?? (await vscode.window.showInputBox({
    prompt: 'relay 共享 token（VPS 上的 /etc/relay/token）',
    password: true,
    ignoreFocusOut: true,
    validateInput: (v) => (v.trim().length >= 16 ? undefined : 'token 过短'),
  }));
  if (!token) return undefined;

  const password = saved.password ?? (await vscode.window.showInputBox({
    prompt: `VPS 登录密码（${host}）`,
    password: true,
    ignoreFocusOut: true,
  }));
  if (!password) return undefined;

  const ep: Endpoint = { host: host.trim(), token: token.trim(), password };
  await ctx.secrets.store(SECRET.host, ep.host);
  await ctx.secrets.store(SECRET.token, ep.token);
  await ctx.secrets.store(SECRET.password, ep.password);
  return ep;
}

async function start(ctx: vscode.ExtensionContext) {
  if (running) {
    vscode.window.showInformationMessage('Bridge 已在运行');
    return;
  }
  const ep = await askEndpoint(ctx, false);
  if (!ep) return;

  const cfg = vscode.workspace.getConfiguration('bridge');
  setStatus('连接中…');
  running = true;
  client = new RelayClient(
    log,
    {
      host: ep.host,
      port: cfg.get<number>('sshPort', 22),
      user: cfg.get<string>('sshUser', 'root'),
      password: ep.password,
      relayPort: cfg.get<number>('relayPort', 8443),
      room: cfg.get<string>('room', 'default'),
      token: ep.token,
    },
    (e) => handleConnEvent(e),
  );
  client.start();
}

function stop() {
  for (const s of sessions.values()) s.kill();
  sessions.clear();
  client?.dispose();
  client = undefined;
  running = false;
  setStatus('未连接');
}

async function forget(ctx: vscode.ExtensionContext) {
  stop();
  await Promise.all(Object.values(SECRET).map((k) => ctx.secrets.delete(k)));
  vscode.window.showInformationMessage('已清除保存的地址、token 和密码');
}

function handleConnEvent(e: ConnEvent) {
  if (e.type === 'status') {
    setStatus(e.connected ? '已连接' : e.detail ?? '重连中…', e.connected);
    if (e.connected) broadcast({ type: 'status', connected: true });
    return;
  }
  let env: Envelope;
  try {
    env = JSON.parse(e.data);
  } catch {
    return;
  }
  if (env.kind === 'request') {
    void handleRequest(env);
  }
}

function broadcast(ev: Parameters<RelayClient['send']>[2]) {
  client?.send('event', undefined, ev);
}

async function handleRequest(env: Envelope) {
  const req = env.payload as RequestPayload | undefined;
  const id = env.id;
  if (!req || !id) return;
  try {
    const result = await dispatch(req);
    client?.send('response', id, { ok: true, data: result });
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    log.appendLine(`[req ${req.op}] ${msg}`);
    client?.send('response', id, { ok: false, error: msg });
  }
}

function allowedRoots(): string[] {
  const cfg = vscode.workspace.getConfiguration('bridge');
  const configured = cfg.get<string[]>('allowedRoots', []);
  const roots = configured.length
    ? configured
    : (vscode.workspace.workspaceFolders ?? []).map((f) => f.uri.fsPath);
  return roots.map((r) => r.replace(/[\\/]+$/, ''));
}

async function dispatch(req: RequestPayload): Promise<unknown> {
  switch (req.op) {
    case 'workspaces':
      return { roots: allowedRoots() };

    case 'list': {
      await checkRoot(req.root);
      return { root: req.root, path: req.path, entries: await listDir(req.root, req.path) };
    }

    case 'read': {
      await checkRoot(req.root);
      return await readFile(req.root, req.path);
    }

    case 'session.start': {
      await checkRoot(req.root);
      const cfg = vscode.workspace.getConfiguration('bridge');
      const claudePath = resolveClaudePath(cfg.get<string>('claudePath', ''));
      const s = new ClaudeSession(
        req.root,
        claudePath,
        req.model,
        (ev) => broadcast(ev),
        (m) => log.appendLine(m),
      );
      sessions.set(s.id, s);
      s.start(req.prompt);
      return { session: s.id };
    }

    case 'session.prompt': {
      getSession(req.session).sendPrompt(req.text);
      return { ok: true };
    }

    case 'session.interrupt': {
      getSession(req.session).interrupt();
      return { ok: true };
    }

    case 'session.history': {
      return { messages: getSession(req.session).getHistory() };
    }

    case 'session.kill': {
      getSession(req.session).kill();
      sessions.delete(req.session);
      return { ok: true };
    }

    case 'session.list':
      return {
        sessions: [...sessions.values()].map((s) => ({ id: s.id, root: s.root, running: s.isRunning })),
      };

    case 'setRoots':
      // 白名单由电脑端决定，手机端只能读取，不能修改
      throw new Error('roots are configured on the PC side');
  }
}

async function checkRoot(root: string) {
  const roots = allowedRoots();
  const isAllowed = roots.some((r) => root === r || root.startsWith(r + '\\') || root.startsWith(r + '/'));
  if (!isAllowed) throw new Error(`root not allowed: ${root}`);
  await assertRoot(root);
}

function getSession(id: string): ClaudeSession {
  const s = sessions.get(id);
  if (!s) throw new Error(`unknown session: ${id}`);
  return s;
}
