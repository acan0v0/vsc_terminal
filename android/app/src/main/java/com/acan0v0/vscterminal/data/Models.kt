package com.acan0v0.vscterminal.data

/** 会话里的一条消息。 */
data class ChatMessage(
    val role: Role,
    val text: String,
    val tools: List<String> = emptyList(),
    val costUsd: Double? = null,
    val streaming: Boolean = false,
) {
    enum class Role { USER, ASSISTANT, TOOL, ERROR }
}

data class SessionState(
    val root: String,
    val messages: List<ChatMessage> = emptyList(),
    val running: Boolean = false,
    val id: String? = null,
)

data class DirNode(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long? = null,
    val expanded: Boolean = false,
    val children: List<DirNode>? = null,
    val loading: Boolean = false,
)
