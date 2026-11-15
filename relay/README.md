# relay —— VPS 中继

手机 App 与电脑 agent 之间的消息中继。只监听 `127.0.0.1:8443`，**不开放任何公网端口**，
两端都通过你已经开着的 SSH（端口 22）做本地端口转发接入。

```
手机 App ──SSH -L──┐
                   ├─▶ VPS 127.0.0.1:8443
PC agent ──SSH -L──┘
```

## 消息格式

WebSocket `/ws?room=<房间名>&role=agent|app`，请求头 `Authorization: Bearer <token>`。

统一信封：

```json
{"kind":"request|response|event|ping","id":"请求id","seq":123,"payload":{...}}
```

| 方向 | kind | relay 行为 |
|---|---|---|
| agent → app | `event` | 分配递增 `seq`，写入环形缓冲（最近 200 条），转发给 app |
| app → agent | `request` | 原样转发；30 秒无响应则回 `{"error":"timeout"}`；agent 不在线回 `{"error":"agent offline"}` |
| agent → app | `response` | 按 `id` 配对回给对应 app |
| 任意 | `ping` | 原样回给发送方 |

- 同一房间同一角色先到先得，新连接会踢掉旧连接。
- 新 app 连上时，relay 会先把环形缓冲里的事件按 `seq` 顺序回放（在锁内完成，保证顺序）。
- 心跳：relay 每 15 秒发 Ping，45 秒收不到任何数据判定断线。

## 构建（Windows 本机）

```bash
cd relay
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 \
  GOFLAGS=-mod=mod GOPROXY=direct GOSUMDB=off \
  go build -trimpath -ldflags="-s -w" -o dist/relay-linux-amd64 .
```

产物是静态链接 ELF，Debian 上无需安装任何运行库。
（本机拉依赖时 `proxy.golang.org` 和 `sum.golang.org` 直连不通，所以用了 `GOPROXY=direct GOSUMDB=off`；
校验值仍由 `go.sum` 保证。）

跑测试：`GOFLAGS=-mod=mod GOPROXY=off go test -race ./...`

## 部署（Xshell 操作）

1. **Xshell 用现有会话连上 VPS（端口 22，密钥登录）。**

2. **上传**：Xshell 工具栏点 **Xftp** 图标（或按 `Alt+P`）打开文件管理器，
   把 `dist/relay-linux-amd64` 和 `deploy/` 下的 `relay.service`、`install.sh` 拖到 `/root/relay-upload/`，
   并在 Xftp 里把 `relay-linux-amd64` 改名为 `relay`。

3. **安装**，在 Xshell 里执行：

   ```bash
   cd /root/relay-upload
   sh install.sh
   ```

   脚本会：创建系统用户 `relay`、装二进制到 `/opt/relay/relay`、生成 `/etc/relay/token`、
   装 systemd 服务并启动、最后用 `curl` 自检。**第一次运行会打印 token，请复制保存**
   （PC agent 和手机 App 都要用同一个）。

   如果 Xftp 里改名不方便，上传后用这行代替：

   ```bash
   mv relay-linux-amd64 relay && sh install.sh
   ```

4. **验证**：

   ```bash
   systemctl status relay --no-pager
   journalctl -u relay -n 30 --no-pager
   curl -s http://127.0.0.1:8443/healthz    # 期望 {"rooms":0}
   ss -lntp | grep 8443                     # 期望地址是 127.0.0.1:8443
   free -m                                  # 看一下启动后的可用内存
   ```

5. **后续更新**：重复第 2 步上传新的 `relay`，然后：

   ```bash
   install -m 0755 /root/relay-upload/relay /opt/relay/relay
   systemctl restart relay
   ```

6. **查看 token**：`cat /etc/relay/token`。

## 为什么不用开端口 / 防火墙

relay 绑定在 `127.0.0.1`，这个地址只在 VPS 内部可见，数据包根本不进入网卡的对外入口，
所以 `ufw`/云厂商安全组都不需要放行 8443。SSH 隧道把远端 `127.0.0.1:8443` 映射到本地，
走的仍然是已经开着的 22 端口。

## 资源占用

- 二进制 6.9MB，常驻内存约 10–20MB（`GOMEMLIMIT=120MiB`、`MemoryMax=160M` 兜底）
- 不依赖数据库；回放缓冲是内存里的 200 条事件，连接断开后不会无限增长
