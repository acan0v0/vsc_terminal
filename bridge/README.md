# VSC Terminal Bridge

VSCode 插件。作为 relay 协议里的 **agent** 端：通过 SSH 隧道连到 VPS 上的 relay，
把“工作区文件浏览 + claude 会话”暴露给手机端。

## 组件关系

```
安卓 App ──SSH -L──▶ VPS 127.0.0.1:8443 (relay)
VSCode 插件 ──ssh2──▶ 同上（插件内嵌 SSH，不读 ~/.ssh/config）
```

插件内部：

| 模块 | 职责 |
|---|---|
| `relayClient.ts` | ssh2 建隧道 → 在通道上手动完成 WebSocket 握手 → 自动重连（指数退避，上限 30s） |
| `tunnelSocket.ts` | 把 ssh2 通道包成 Duplex，并手拼 HTTP Upgrade 请求、解析 101 响应 |
| `extension.ts` | 命令、状态栏、请求分发、工作区白名单校验 |
| `workspaces.ts` | 文件树列举、只读读取、**路径沙箱** |
| `claudeSession.ts` | 定位并 spawn `claude.exe`，解析 stream-json 事件 |
| `protocol.ts` | 与 relay / App 约定的信封和 payload 类型 |

## 配置（设置里搜索 `bridge.`）

**地址、token、密码都不写进设置文件**，首次连接时弹框输入，保存在 VSCode 的
`SecretStorage`（用系统密钥链加密），命令 `Bridge: 清除已保存的连接信息` 可全部删除。

| 设置 | 说明 |
|---|---|
| `bridge.sshPort` | 默认 22 |
| `bridge.sshUser` | 默认 root |
| `bridge.relayPort` | relay 在 VPS 回环上的端口，默认 8443 |
| `bridge.claudePath` | 留空自动查找扩展内置 claude，扩展升级后仍能定位 |
| `bridge.allowedRoots` | 手机可选择的工作区根目录白名单；留空则用当前 VSCode 工作区 |

首次执行 `Bridge: 连接中继` 时会依次询问 VPS 地址、relay token、SSH 密码。

## 使用

1. 按需在设置里填 `bridge.allowedRoots`（手机能访问的工作区根目录）
2. 命令面板执行 **Bridge: 连接中继**，依次输入 VPS 地址、token、密码
3. 状态栏显示 `Bridge: 已连接`

## 安全边界

- **路径沙箱**：`resolveInside()` 拒绝绝对路径、盘符路径和任何 `..` 越界，文件读取限制在 root 之内
- **白名单**：手机只能从 `allowedRoots` 里选工作区；手机的 `setRoots` 请求会被拒绝
- **只读**：目前没有写文件、执行任意命令的接口；能触发动作的只有 claude 会话本身（受 Claude Code 权限设置约束）
- **单文件上限** 256KB，二进制文件不返回内容

## 已知限制

- **中断**：Windows 上没有 POSIX 信号，`session.interrupt` 目前是空操作（仅记录日志）
- **会话**：每个会话是一个常驻 `claude` 进程；插件卸载或 VSCode 退出时全部终止
- 手机端请求的超时由 relay 控制（30 秒），长时间无响应的操作要拆成事件流而不是请求
- 插件不读取 `~/.ssh/config`，用 `ssh2` 自行建立 SSH 连接（避免本机全局配置影响）

## 构建

```bash
npm install
npm run compile     # 输出到 out/
```

调试：在 VSCode 中用 **Run Extension** 启动扩展开发宿主。
