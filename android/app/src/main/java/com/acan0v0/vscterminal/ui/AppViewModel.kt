package com.acan0v0.vscterminal.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.acan0v0.vscterminal.crypto.CredentialStore
import com.acan0v0.vscterminal.data.BridgeEvent
import com.acan0v0.vscterminal.data.BridgeRequest
import com.acan0v0.vscterminal.data.ChatMessage
import com.acan0v0.vscterminal.data.DirNode
import com.acan0v0.vscterminal.data.Entry
import com.acan0v0.vscterminal.data.FileContent
import com.acan0v0.vscterminal.data.SessionState
import com.acan0v0.vscterminal.data.toEntries
import com.acan0v0.vscterminal.net.BridgeClient
import com.acan0v0.vscterminal.net.LinkService
import com.acan0v0.vscterminal.net.SshTunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UiState(
    val phase: Phase = Phase.DISCONNECTED,
    val error: String? = null,
    val roots: List<String> = emptyList(),
    val currentRoot: String? = null,
    val tree: List<DirNode> = emptyList(),
    val openFile: FileContent? = null,
    val session: SessionState? = null,
    /** 当前浏览的子目录（相对 root，"" 表示根） */
    val currentPath: String = "",
) {
    enum class Phase { DISCONNECTED, CONNECTING, CONNECTED }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = CredentialStore(app)
    private var tunnel: SshTunnel? = null
    private var client: BridgeClient? = null

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val hasCredentials: Boolean get() = store.load() != null

    fun saveCredentials(host: String, sshPort: Int, user: String, token: String, password: String) {
        store.save(CredentialStore.Endpoint(host.trim(), sshPort, user.trim(), token.trim(), password))
    }

    fun forget() {
        disconnect()
        store.clear()
        _ui.value = UiState()
    }

    fun connect() {
        val ep = store.load()
        if (ep == null) {
            _ui.update { it.copy(error = "请先填写连接信息") }
            return
        }
        if (_ui.value.phase != UiState.Phase.DISCONNECTED) return
        _ui.update { it.copy(phase = UiState.Phase.CONNECTING, error = null) }

        viewModelScope.launch {
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
                tunnel = t
                val c = BridgeClient(viewModelScope, "ws://127.0.0.1:${t.tunnelPort}/ws?room=default&role=app", ep.token)
                client = c
                viewModelScope.launch {
                    c.connected.collect { connected ->
                        if (connected) {
                            _ui.update { it.copy(phase = UiState.Phase.CONNECTED, error = null) }
                            LinkService.start(getApplication())
                            loadRoots()
                        } else if (_ui.value.phase == UiState.Phase.CONNECTED) {
                            _ui.update {
                                it.copy(
                                    phase = UiState.Phase.CONNECTING,
                                    error = c.lastError.value ?: "与电脑的连接已断开",
                                )
                            }
                        }
                    }
                }
                viewModelScope.launch { c.events.collect { onEvent(it) } }
                c.connect()
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                _ui.update { it.copy(phase = UiState.Phase.DISCONNECTED, error = e.message ?: "连接失败") }
                disconnect()
            }
        }
    }

    fun disconnect() {
        client?.disconnect()
        client = null
        tunnel?.stop()
        tunnel = null
        LinkService.stop(getApplication())
        _ui.update { it.copy(phase = UiState.Phase.DISCONNECTED) }
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    // ---- 事件 ----

    private fun onEvent(ev: BridgeEvent) {
        when (ev) {
            is BridgeEvent.Delta -> updateAssistant { it.copy(text = it.text + ev.text) }
            is BridgeEvent.Tool -> updateAssistant(appendTool = "${ev.name} ${ev.detail}".trim())
            is BridgeEvent.Result -> updateAssistant {
                it.copy(text = it.text.ifEmpty { ev.text }, streaming = false, costUsd = ev.costUsd)
            }
            is BridgeEvent.SessionStarted -> _ui.update { s ->
                s.copy(session = s.session?.copy(id = ev.session, running = true))
            }
            is BridgeEvent.ErrorEvent -> _ui.update { s ->
                val sess = s.session ?: return@update s
                s.copy(session = sess.copy(running = false, messages = sess.messages + ChatMessage(ChatMessage.Role.ERROR, ev.message)))
            }
            is BridgeEvent.Ended -> _ui.update { s -> s.copy(session = s.session?.copy(running = false)) }
            else -> Unit
        }
    }

    private fun updateAssistant(
        appendTool: String? = null,
        transform: (ChatMessage) -> ChatMessage = { it },
    ) {
        _ui.update { s ->
            val sess = s.session ?: return@update s
            val msgs = sess.messages.toMutableList()
            val idx = msgs.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
            if (idx >= 0) {
                val m = transform(msgs[idx])
                msgs[idx] = if (appendTool != null) m.copy(tools = m.tools + appendTool) else m
            }
            s.copy(session = sess.copy(messages = msgs))
        }
    }

    // ---- 工作区与文件 ----

    private fun loadRoots() {
        val c = client ?: return
        val parseRoots: (JSONObject) -> List<String> = { data ->
            val arr = data.optJSONArray("roots")
            if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }
        }
        viewModelScope.launch {
            runCatching {
                c.request(BridgeRequest.Workspaces, parseRoots)
            }.onSuccess { roots ->
                _ui.update {
                    it.copy(
                        roots = roots,
                        currentRoot = roots.firstOrNull(),   // 重连后总是回到第一个工作区的根
                        currentPath = "",
                        tree = emptyList(),
                    )
                }
                _ui.value.currentRoot?.let { loadTree(it, "") }
            }.onFailure { e -> _ui.update { it.copy(error = e.message) } }
        }
    }

    fun selectRoot(root: String) {
        _ui.update { it.copy(currentRoot = root, tree = emptyList(), openFile = null, currentPath = "") }
        loadTree(root, "")
    }

    /** 进入子目录 */
    fun enterDir(path: String) {
        val root = _ui.value.currentRoot ?: return
        _ui.update { it.copy(currentPath = path, openFile = null) }
        loadTree(root, path)
    }

    /** 返回上一级；已在根目录时不做任何事 */
    fun goUp() {
        val path = _ui.value.currentPath
        if (path.isEmpty()) return
        val parent = path.substringBeforeLast('/', "")
        enterDir(parent)
    }

    private fun loadTree(root: String, path: String) {
        val c = client ?: return
        val parseEntries: (JSONObject) -> List<Entry> = { data ->
            data.optJSONArray("entries")?.toEntries().orEmpty()
        }
        viewModelScope.launch {
            runCatching {
                c.request(BridgeRequest.List(root, path), parseEntries)
            }.onSuccess { entries -> _ui.update { s -> s.copy(tree = buildTree(entries, path)) } }
                .onFailure { e -> _ui.update { it.copy(error = e.message) } }
        }
    }

    fun openFile(path: String) {
        val root = _ui.value.currentRoot ?: return
        val c = client ?: return
        val parseFile: (JSONObject) -> FileContent = { data ->
            FileContent(
                path = data.optString("path"),
                content = data.optString("content"),
                truncated = data.optBoolean("truncated"),
                binary = data.optBoolean("binary"),
                size = data.optLong("size"),
            )
        }
        viewModelScope.launch {
            runCatching {
                c.request(BridgeRequest.Read(root, path), parseFile)
            }.onSuccess { f -> _ui.update { it.copy(openFile = f) } }
                .onFailure { e -> _ui.update { it.copy(error = e.message) } }
        }
    }

    fun closeFile() = _ui.update { it.copy(openFile = null) }

    private fun buildTree(entries: List<Entry>, base: String): List<DirNode> = entries.map { e ->
        DirNode(
            name = e.name,
            path = if (base.isEmpty()) e.name else "$base/${e.name}",
            isDir = e.type == "dir",
            size = e.size,
        )
    }

    // ---- 会话 ----

    fun startSession(prompt: String) {
        val root = _ui.value.currentRoot ?: return
        val c = client ?: return
        val parseSession: (JSONObject) -> String = { d -> d.optString("session") }
        _ui.update {
            it.copy(
                session = SessionState(
                    root = root,
                    messages = listOf(
                        ChatMessage(ChatMessage.Role.USER, prompt),
                        ChatMessage(ChatMessage.Role.ASSISTANT, "", streaming = true),
                    ),
                    running = true,
                ),
            )
        }
        viewModelScope.launch {
            runCatching { c.request(BridgeRequest.StartSession(root, prompt), parseSession) }
                .onSuccess { id -> _ui.update { s -> s.copy(session = s.session?.copy(id = id)) } }
                .onFailure { e ->
                    _ui.update { it.copy(error = e.message, session = it.session?.copy(running = false)) }
                }
        }
    }

    fun sendPrompt(text: String) {
        val c = client ?: return
        val sess = _ui.value.session ?: return
        val id = sess.id ?: return
        val parseUnit: (JSONObject) -> Unit = { }
        _ui.update {
            it.copy(
                session = sess.copy(
                    running = true,
                    messages = sess.messages +
                        ChatMessage(ChatMessage.Role.USER, text) +
                        ChatMessage(ChatMessage.Role.ASSISTANT, "", streaming = true),
                ),
            )
        }
        viewModelScope.launch {
            runCatching { c.request(BridgeRequest.Prompt(id, text), parseUnit) }
                .onFailure { e -> _ui.update { it.copy(error = e.message, session = it.session?.copy(running = false)) } }
        }
    }

    fun closeSession() {
        val id = _ui.value.session?.id
        val c = client
        if (id != null && c != null) {
            val parseUnit: (JSONObject) -> Unit = { }
            viewModelScope.launch { runCatching { c.request(BridgeRequest.KillSession(id), parseUnit) } }
        }
        _ui.update { it.copy(session = null) }
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    private companion object {
        const val TAG = "AppVM"
    }
}
