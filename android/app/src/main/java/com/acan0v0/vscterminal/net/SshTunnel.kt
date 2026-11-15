package com.acan0v0.vscterminal.net

import android.util.Log
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 用 sshj 在本地监听一个端口，把流量转发到 VPS 回环上的 relay。
 *
 * 等价于命令行 `ssh -N -L <localPort>:127.0.0.1:<remotePort> user@host`。
 * App 侧随后连 ws://127.0.0.1:<localPort>，走的是 SSH 加密通道。
 */
class SshTunnel(
    private val host: String,
    private val sshPort: Int,
    private val user: String,
    private val password: String,
    private val remotePort: Int,
    private val localPort: Int = 18443,
) {
    private var client: SSHClient? = null
    private var serverSocket: ServerSocket? = null
    private var forwarderThread: Thread? = null
    private val running = AtomicReference(false)

    val tunnelPort: Int get() = localPort

    fun start(timeoutMs: Long = 20_000) {
        if (running.get()) return
        val ssh = SSHClient()
        // 首次连接不校验主机指纹；如需更严格，可换成 KnownHosts 并预置公钥
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connect(host, sshPort)
        ssh.authPassword(user, password)
        client = ssh

        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("127.0.0.1", localPort))
        serverSocket = ss

        val params = Parameters("127.0.0.1", localPort, "127.0.0.1", remotePort)
        val forwarder = ssh.newLocalPortForwarder(params, ss)

        running.set(true)
        val started = CountDownLatch(1)
        forwarderThread = Thread({
            started.countDown()
            try {
                forwarder.listen() // 阻塞，直到 serverSocket 被关闭
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "port forwarder stopped: ${e.message}")
            }
        }, "ssh-tunnel").apply {
            isDaemon = true
            start()
        }
        if (!started.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            stop()
            throw IllegalStateException("SSH 隧道启动超时")
        }
        Log.i(TAG, "tunnel 127.0.0.1:$localPort -> $host:$remotePort")
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        runCatching { client?.disconnect() }
        runCatching { forwarderThread?.interrupt() }
        serverSocket = null
        client = null
        forwarderThread = null
    }

    private companion object {
        const val TAG = "SshTunnel"
    }
}
