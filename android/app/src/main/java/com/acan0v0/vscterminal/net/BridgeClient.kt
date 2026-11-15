package com.acan0v0.vscterminal.net

import android.util.Log
import com.acan0v0.vscterminal.data.BridgeEvent
import com.acan0v0.vscterminal.data.BridgeRequest
import com.acan0v0.vscterminal.data.Envelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 业务侧客户端：连到 SSH 隧道暴露的本地端口上的 relay，扮演 role=app。
 *
 * 请求-响应配对由 relay 完成（按 id 路由），这里只负责发送和等待。
 */
class BridgeClient(
    private val scope: CoroutineScope,
    private val wsUrl: String,
    private val token: String,
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _events = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<BridgeEvent> = _events.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun connect() {
        val req = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $token")
            .build()
        ws = http.newWebSocket(req, listener)
    }

    fun disconnect() {
        ws?.close(1000, "bye")
        ws = null
        _connected.value = false
        pending.values.forEach { it.completeExceptionally(IllegalStateException("已断开")) }
        pending.clear()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "ws open")
            _lastError.value = null
            _connected.value = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val env = runCatching { Envelope.parse(text) }.getOrNull() ?: return
            when (env.kind) {
                "response" -> {
                    val id = env.id ?: return
                    pending.remove(id)?.complete(env.payload ?: JSONObject())
                }
                "event" -> BridgeEvent.parse(env.payload)?.let { scope.launch { _events.emit(it) } }
                "ping" -> Unit
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "ws failure: ${t.message} status=${response?.code}")
            _lastError.value = buildString {
                append(t.message ?: t.javaClass.simpleName)
                response?.let { append(" (HTTP ${it.code})") }
            }
            _connected.value = false
            pending.values.forEach { it.completeExceptionally(t) }
            pending.clear()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "ws closed: $code $reason")
            _connected.value = false
        }
    }

    /** 发送请求并等待响应；relay 在 30 秒无响应时会回 timeout，这里多留一点余量。 */
    suspend fun <T> request(req: BridgeRequest, parse: (JSONObject) -> T, timeoutMs: Long = 35_000): T {
        val socket = ws ?: throw IllegalStateException("未连接")
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val env = Envelope(kind = "request", id = id, payload = req.toJson())
        if (!socket.send(env.toJson())) {
            pending.remove(id)
            throw IllegalStateException("发送失败")
        }
        val resp = try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
        resp.optString("error").takeIf { it.isNotEmpty() }?.let { throw IllegalStateException(it) }
        @Suppress("UNCHECKED_CAST")
        return parse(resp.optJSONObject("data") ?: JSONObject())
    }

    fun sendEventless(req: BridgeRequest) {
        ws?.send(Envelope(kind = "request", id = UUID.randomUUID().toString(), payload = req.toJson()).toJson())
    }

    private companion object {
        const val TAG = "BridgeClient"
    }
}
