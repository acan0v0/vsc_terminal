package com.acan0v0.vscterminal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.acan0v0.vscterminal.VscTerminalApp
import com.acan0v0.vscterminal.crypto.CredentialStore
import com.acan0v0.vscterminal.data.BridgeEvent
import com.acan0v0.vscterminal.data.BridgeRequest
import com.acan0v0.vscterminal.data.ChatMessage
import com.acan0v0.vscterminal.data.DirNode
import com.acan0v0.vscterminal.data.Entry
import com.acan0v0.vscterminal.data.FileContent
import com.acan0v0.vscterminal.data.SessionState
import com.acan0v0.vscterminal.data.toEntries
import com.acan0v0.vscterminal.net.BridgeManager
import com.acan0v0.vscterminal.net.LinkPhase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class UiState(
    val phase: LinkPhase = LinkPhase.DISCONNECTED,
    val error: String? = null,
    val roots: List<String> = emptyList(),
    val currentRoot: String? = null,
    val tree: List<DirNode> = emptyList(),
    val openFile: FileContent? = null,
    val session: SessionState? = null,
    /** 当前浏览的子目录（相对 root，"" 表示根） */
    val currentPath: String = "",
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val bridge: BridgeManager = (app as VscTerminalApp).bridge

    private val _ui = MutableStateFlow(UiState(phase = bridge.phase.value))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // 文本增量按固定窗口合并：逐 token 更新会让整页每秒重组几十次
    private val pendingDelta = StringBuilder()
    private var deltaFlushJob: Job? = null

    init {
        // 连接由 BridgeManager 持有，界面只是观察者：Activity 重建后能直接接上正在运行的连接
        viewModelScope.launch {
            bridge.phase.collect { phase ->
                _ui.update { s ->
                    s.copy(
                        phase = phase,
                        error = when {
                            phase == LinkPhase.CONNECTED -> null
                            phase == LinkPhase.CONNECTING -> s.error
                            else -> bridge.error.value ?: s.error
                        },
                    )
                }
                if (phase == LinkPhase.CONNECTED) loadRoots()
            }
        }
        viewModelScope.launch {
            bridge.error.collect { e -> if (e != null) _ui.update { it.copy(error = e) } }
        }
        viewModelScope.launch {
            bridge.events.collect { onEvent(it) }
        }
    }

    val hasCredentials: Boolean get() = bridge.hasCredentials

    /** 上次保存的连接信息，用于回填输入框（密码同样来自加密存储）。 */
    fun savedEndpoint(): CredentialStore.Endpoint? = bridge.savedEndpoint()

    fun connect(host: String, sshPort: Int, user: String, token: String, password: String) {
        bridge.connect(host, sshPort, user, token, password)
    }

    /** App 回到前台时调用，未主动断开过就自动重连。 */
    fun onForeground() = bridge.onForeground()

    fun disconnect() {
        bridge.disconnect()
        clearPendingDelta()
        _ui.update { it.copy(roots = emptyList(), currentRoot = null, tree = emptyList(), openFile = null, currentPath = "") }
    }

    fun forget() {
        bridge.forget()
        clearPendingDelta()
        _ui.value = UiState()
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    // ---- 事件 ----

    private fun onEvent(ev: BridgeEvent) {
        when (ev) {
            is BridgeEvent.Delta -> {
                pendingDelta.append(ev.text)
                scheduleDeltaFlush()
            }
            // 工具链只保留最新一条，避免刷屏
            is BridgeEvent.Tool -> {
                flushPendingDelta()  // 先落字，保证工具行出现在它之前的文字之后
                updateAssistant(appendTool = "${ev.name} ${ev.detail}".trim())
            }
            // 一轮回答以 result 结束；busy 只在插件侧复位，这里必须同步清掉 running，
            // 否则输入框会一直处于“回答中”而无法继续下一轮（看起来就像对话被中断）
            is BridgeEvent.Result -> {
                flushPendingDelta()
                updateAssistant {
                    it.copy(text = it.text.ifEmpty { ev.text }, streaming = false, costUsd = ev.costUsd)
                }
                _ui.update { s -> s.copy(session = s.session?.copy(running = false)) }
            }
            is BridgeEvent.SessionStarted -> _ui.update { s ->
                s.copy(session = s.session?.copy(id = ev.session, running = true))
            }
            is BridgeEvent.ErrorEvent -> {
                flushPendingDelta()
                _ui.update { s ->
                    val sess = s.session ?: return@update s
                    s.copy(
                        session = sess.copy(
                            running = false,
                            messages = sess.messages
                                .map { if (it.streaming) it.copy(streaming = false) else it } +
                                ChatMessage(ChatMessage.Role.ERROR, ev.message),
                        ),
                    )
                }
            }
            // 进程退出时可能没有 result（崩溃/被杀），要把流式气泡和 running 一起收尾
            is BridgeEvent.Ended -> {
                flushPendingDelta()
                _ui.update { s ->
                    val sess = s.session ?: return@update s
                    s.copy(
                        session = sess.copy(
                            running = false,
                            messages = sess.messages.map { if (it.streaming) it.copy(streaming = false) else it },
                        ),
                    )
                }
            }
            else -> Unit
        }
    }

    /** 第一个 delta 到来后开启一个 100ms 窗口，窗口内到达的文字一起提交。 */
    private fun scheduleDeltaFlush() {
        if (deltaFlushJob?.isActive == true) return
        deltaFlushJob = viewModelScope.launch {
            delay(DELTA_FLUSH_MS)
            deltaFlushJob = null
            emitPendingDelta()
        }
    }

    private fun flushPendingDelta() {
        deltaFlushJob?.cancel()
        deltaFlushJob = null
        emitPendingDelta()
    }

    private fun emitPendingDelta() {
        if (pendingDelta.isEmpty()) return
        val text = pendingDelta.toString()
        pendingDelta.setLength(0)
        updateAssistant { it.copy(text = it.text + text) }
    }

    private fun clearPendingDelta() {
        deltaFlushJob?.cancel()
        deltaFlushJob = null
        pendingDelta.setLength(0)
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
                msgs[idx] = if (appendTool != null) m.copy(tools = listOf(appendTool)) else m
            }
            s.copy(session = sess.copy(messages = msgs))
        }
    }

    // ---- 工作区与文件 ----

    private fun loadRoots() {
        val c = bridge.currentClient ?: return
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
        val c = bridge.currentClient ?: return
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
        val c = bridge.currentClient ?: return
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
        val c = bridge.currentClient ?: return
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
        val c = bridge.currentClient ?: return
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
        clearPendingDelta()
        val id = _ui.value.session?.id
        val c = bridge.currentClient
        if (id != null && c != null) {
            val parseUnit: (JSONObject) -> Unit = { }
            viewModelScope.launch { runCatching { c.request(BridgeRequest.KillSession(id), parseUnit) } }
        }
        _ui.update { it.copy(session = null) }
    }

    private companion object {
        /** 文本增量合并窗口；10Hz 的刷新肉眼已经连续，再快只是白烧主线程 */
        const val DELTA_FLUSH_MS = 100L
    }
}
