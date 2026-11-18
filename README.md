# VSC Terminal Bridge

用手机远程控制电脑上的 Claude Code：浏览工作区文件、在任意工作区里开新会话并查看流式输出。

整个方案不依赖第三方中转服务——消息经过你自己部署的 VPS 上的一台中继，而中继只监听回环地址，
两端都通过 SSH 端口转发接入。

```
┌───────── ───┐   SSH 隧道   ┌──────────────────┐   SSH 隧道     ┌──────────────────┐
│  Android    │────────────▶│  VPS relay (Go)  │◀─────────────│ VSCode 插件 (TS)  │
│   App       │              │ 127.0.0.1:8443   │               │                  │
└────────── ──┘              └──────────────────┘               └────────┬─────────┘
                                                                         │ spawn
                                                                         ▼
                                                                  claude (stream-json)
```

## 目录

| 目录 | 内容 |
|---|---|
| [`relay/`](relay/) | VPS 中继，Go 单二进制，静态链接，常驻内存约 2MB |
| [`bridge/`](bridge/) | VSCode 插件（TypeScript），扮演协议里的 agent 端 |

## 为什么用 SSH 隧道而不是直接开端口

- relay 只绑定 `127.0.0.1`，公网完全看不到，不需要改防火墙、不需要证书
- 复用已有的 SSH 登录方式（密码或密钥），不额外暴露服务
- 流量在 SSH 加密通道内传输

## 快速开始

**1. 部署中继**（见 [relay/README.md](relay/README.md)）

```bash
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o relay .
# 上传到 VPS，然后
sh install.sh
```

**2. 安装插件**

```bash
cd bridge
npm install && npm run compile
npx vsce package --allow-missing-repository
code --install-extension vsc-terminal-bridge-0.2.0.vsix
```

**3. 连接**

命令面板执行 `Bridge: 连接中继`，依次输入 VPS 地址、relay token、SSH 密码。
凭据保存在 VSCode 的 `SecretStorage` 里，不会写进设置文件。

## 协议

relay 只在两端之间转发字节，不解释业务内容。统一信封：

```json
{"kind":"request|response|event|ping","id":"请求id","seq":123,"payload":{...}}
```

| 方向 | kind | 行为 |
|---|---|---|
| agent → app | `event` | 分配递增 `seq`，写入环形缓冲（最近 200 条），转发 |
| app → agent | `request` | 原样转发；30 秒无响应回 `timeout`；agent 离线回 `agent offline` |
| agent → app | `response` | 按 `id` 配对回给发起方 |

新 app 连上时按 `seq` 回放缓冲里的历史事件。心跳为 15 秒 Ping / 45 秒超时。

## 安全边界

- **路径沙箱**：插件的 `resolveInside()` 拒绝绝对路径、盘符路径和 `..` 越界
- **工作区白名单**：手机只能从 `allowedRoots` 里选，不能自行扩大范围
- **只读**：没有写文件、执行任意命令的接口
- **凭据**：地址、token、密码存于系统密钥链，不进配置文件、不进仓库

## 状态

- ✅ VPS 中继（含测试、systemd 部署）
- ✅ VSCode 插件（已实测连通 VPS 并完成双向消息）
- ⬜ 安卓 App

## 已知限制

- Windows 上 `session.interrupt` 是空操作（没有 POSIX 信号）
- 每个会话是一个常驻 `claude` 进程，VSCode 退出时全部终止
- 文件读取上限 256KB，二进制文件不返回内容
