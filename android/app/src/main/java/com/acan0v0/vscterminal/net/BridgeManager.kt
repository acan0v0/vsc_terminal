package com.acan0v0.vscterminal.net

import android.content.Context
import android.util.Log
import com.acan0v0.vscterminal.crypto.CredentialStore
import com.acan0v0.vscterminal.data.BridgeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class LinkPhase { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * 进程级连接管理器：隧道和 WebSocket 由它持有，而不是由 Activity 的 ViewModel 持有。
 *
 * 这样界面被销毁（切后台后系统回收 Activity、甚至从最近任务划掉）时连接不会中断，
 * 配合 [LinkService] 前台服务让进程存活；断线后按退避策略自动重连。
 */
class BridgeManager(context: Context) {

    private val appContext = context.applicationContext
    private val store = CredentialStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var tunnel: SshTunnel? = null
    private var client: BridgeClient? = null
    private var gen = 0
    private var retryJob: Job? = null
    private var retryAttempt = 0

    private val _phase = MutableStateFlow(LinkPhase.DISCONNECTED)
    val phase: StateFlow<LinkPhase> = _phase.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _events = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<BridgeEvent> = _events.asSharedFlow()

    /** 当前可用的业务客户端，没连上时为 null。 */
    @Volatile
    var currentClient: BridgeClient? = null
        private set

    val hasCredentials: Boolean get() = store.load() != null

    fun savedEndpoint(): CredentialStore.Endpoint? = store.load()

    /** 用户主动发起连接。 */
    fun connect(host: String, sshPort: Int, user: String, token: String, password: String) {
        val ep = CredentialStore.Endpoint(host.trim(), sshPort, user.trim(), token.trim(), password)
        when {
            ep.host.isBlank() -> return fail("请填写 VPS 地址")
            ep.sshUser.isBlank() -> return fail("请填写 SSH 用户名")
            ep.token.isBlank() -> return fail("请填写 relay token")
            ep.password.isBlank() -> return fail("请填写 SSH 密码")
        }
        store.save(ep)
        store.setWanted(true)
        retryAttempt = 0
        connectInternal(ep)
    }

    /**
     * App 回到前台时调用：用户没有主动断开过，就立即恢复连接。
     * 后台自动重连也走同一条路径。
     */
    fun onForeground() {
        if (_phase.value != LinkPhase.DISCONNECTED) return
        if (!store.isWanted()) return
        val ep = store.load() ?: return
        retryJob?.cancel()
        retryJob = null
        connectInternal(ep)
    }

    /** 用户主动断开：不再自动重连。 */
    fun disconnect() {
        retryJob?.cancel()
        retryJob = null
        store.setWanted(false)
        gen++
        teardown()
        LinkService.stop(appContext)
        _phase.value = LinkPhase.DISCONNECTED
    }

    fun forget() {
        disconnect()
        store.clear()
        _error.value = null
    }

    private fun connectInternal(ep: CredentialStore.Endpoint) {
        // 每次连接递增代号，晚到的旧连接结果直接丢弃，避免状态互相覆盖
        val myGen = ++gen
        teardown()
        _phase.value = LinkPhase.CONNECTING
        _error.value = null
        // 一发起连接就进入前台服务，这样即使这次失败、随后在后台重试，进程也不会被回收
        startServiceSafely("正在连接电脑…")

        scope.launch {
            try {
                val t = withContext(Dispatchers.IO) {
                    SshTunnel(
                        host = ep.host,
                        sshPort = ep.sshPort,
                        user = ep.sshUser,
                        password = ep.password,
                        remotePort = 8443,
                    ).also { it.start() }
                }
                if (myGen != gen) {
                    t.stop()
                    return@launch
                }
                tunnel = t
                val c = BridgeClient(scope, "ws://127.0.0.1:${t.tunnelPort}/ws?room=default&role=app", ep.token)
                client = c
                currentClient = c
                scope.launch { c.events.collect { _events.emit(it) } }
                c.connect()

                // SSH 通了不代表中继可用（token 错、插件未启动等），等握手结果；
                // 握手失败时立即返回，不必干等满超时时间
                var handshook = false
                val settled = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                    combine(c.connected, c.lastError) { conn, err -> conn to err }
                        .first { (conn, err) ->
                            handshook = conn
                            conn || err != null
                        }
                    true
                }
                if (myGen != gen) return@launch
                if (settled == null) {
                    throw IllegalStateException(
                        c.lastError.value ?: "SSH 已连上，但中继没有响应（token 错误或 VSCode 插件未启动？）",
                    )
                }
                if (!handshook) {
                    throw IllegalStateException(c.lastError.value ?: "中继握手失败")
                }
                retryAttempt = 0
                _phase.value = LinkPhase.CONNECTED
                _error.value = null
                startServiceSafely("已连接到电脑")

                // 连上之后再断开：回到未连接状态，并在后台自动重试
                scope.launch {
                    c.connected.collect { connected ->
                        if (!connected && currentClient === c && _phase.value == LinkPhase.CONNECTED) {
                            _error.value = c.lastError.value ?: "与电脑的连接已断开"
                            _phase.value = LinkPhase.DISCONNECTED
                            startServiceSafely("连接已断开，正在重试…")
                            scheduleRetry()
                        }
                    }
                }
            } catch (e: Exception) {
                if (myGen != gen) return@launch
                Log.w(TAG, "connect failed", e)
                teardown()
                _phase.value = LinkPhase.DISCONNECTED
                _error.value = describe(e)
                if (isFatal(e)) {
                    // 密码错误之类的问题重试也没用，还可能触发 VPS 上 fail2ban 封 IP
                    store.setWanted(false)
                    LinkService.stop(appContext)
                } else {
                    scheduleRetry()
                }
            }
        }
    }

    /** 断线后自动重试：2s、4s、8s、16s、30s…（用户主动断开则不重试）。 */
    private fun scheduleRetry() {
        if (!store.isWanted()) return
        retryJob?.cancel()
        val delayMs = minOf(30_000L, 2_000L shl minOf(retryAttempt, 4))
        retryAttempt++
        Log.i(TAG, "reconnect in ${delayMs}ms")
        retryJob = scope.launch {
            delay(delayMs)
            if (_phase.value == LinkPhase.DISCONNECTED && store.isWanted()) {
                store.load()?.let { connectInternal(it) }
            }
        }
    }

    /** 只关连接，不停前台服务：重连期间服务要继续保住进程。 */
    private fun teardown() {
        currentClient = null
        client?.disconnect()
        client = null
        tunnel?.stop()
        tunnel = null
    }

    private fun startServiceSafely(text: String) {
        try {
            LinkService.start(appContext, text)
        } catch (t: Throwable) {
            // Android 12+ 在后台启动前台服务会被拒绝；连接本身不受影响
            Log.w(TAG, "foreground service start failed", t)
        }
    }

    private fun fail(message: String) {
        _error.value = message
    }

    /** 认证失败不应自动重试。 */
    private fun isFatal(e: Throwable): Boolean =
        e.message?.contains("Auth fail", ignoreCase = true) == true

    private fun describe(e: Throwable): String {
        val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return when {
            msg.contains("Auth fail", ignoreCase = true) -> "SSH 认证失败：用户名或密码不正确"
            msg.contains("refused", ignoreCase = true) -> "SSH 端口拒绝连接，检查地址与端口是否正确"
            else -> msg
        }
    }

    private companion object {
        const val TAG = "BridgeManager"
        const val HANDSHAKE_TIMEOUT_MS = 20_000L
    }
}
