#!/bin/sh
# 在 VPS 上以 root 运行：sh install.sh
# 前提：relay 二进制已放到 /opt/relay/relay（或同目录下的 relay）
set -eu

BIN_SRC="$(dirname "$0")/relay"
[ -f "$BIN_SRC" ] || BIN_SRC="$(pwd)/relay"
[ -f "$BIN_SRC" ] || { echo "找不到 relay 二进制，请先上传到 /opt/relay/relay"; exit 1; }

# 1. 运行用户（无登录 shell，不能登录系统）
if ! id relay >/dev/null 2>&1; then
    useradd --system --no-create-home --shell /usr/sbin/nologin relay
fi

# 2. 目录
install -d -m 0755 -o root -g root /opt/relay
install -d -m 0750 -o relay -g relay /etc/relay
install -m 0755 "$BIN_SRC" /opt/relay/relay

# 3. 共享 token：已存在则不覆盖（避免重装时两端 token 不一致）
if [ ! -s /etc/relay/token ]; then
    openssl rand -hex 32 > /etc/relay/token
    echo "已生成新 token，请记录到两端配置中："
    cat /etc/relay/token
fi
chown relay:relay /etc/relay/token
chmod 0600 /etc/relay/token

# 4. systemd
install -m 0644 "$(dirname "$0")/relay.service" /etc/systemd/system/relay.service
systemctl daemon-reload
systemctl enable relay
systemctl restart relay

# 5. 本机验证（只监听回环，不经过防火墙）
sleep 1
if curl -fsS http://127.0.0.1:8443/healthz; then
    echo
    echo "relay 已启动。查看日志：journalctl -u relay -f"
else
    echo "healthz 失败，请查看：journalctl -u relay -n 50 --no-pager"
    exit 1
fi
