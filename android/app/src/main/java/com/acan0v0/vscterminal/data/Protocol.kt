package com.acan0v0.vscterminal.data

import org.json.JSONArray
import org.json.JSONObject

/** 与 relay / 插件约定的信封格式。 */
data class Envelope(
    val kind: String,
    val id: String? = null,
    val seq: Long? = null,
    val payload: JSONObject? = null,
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("kind", kind)
        id?.let { o.put("id", it) }
        payload?.let { o.put("payload", it) }
        return o.toString()
    }

    companion object {
        fun parse(raw: String): Envelope {
            val o = JSONObject(raw)
            return Envelope(
                kind = o.optString("kind"),
                id = o.optString("id").takeIf { it.isNotEmpty() && it != "null" },
                seq = if (o.has("seq")) o.optLong("seq") else null,
                payload = o.optJSONObject("payload"),
            )
        }
    }
}

/** App → 插件 的请求负载。 */
sealed interface BridgeRequest {
    data object Workspaces : BridgeRequest
    data class List(val root: String, val path: String) : BridgeRequest
    data class Read(val root: String, val path: String) : BridgeRequest
    data class StartSession(val root: String, val prompt: String, val model: String? = null) : BridgeRequest
    data class Prompt(val session: String, val text: String) : BridgeRequest
    data class Interrupt(val session: String) : BridgeRequest
    data class KillSession(val session: String) : BridgeRequest
    data class SessionHistory(val session: String) : BridgeRequest

    fun toJson(): JSONObject = when (this) {
        Workspaces -> JSONObject().put("op", "workspaces")
        is List -> JSONObject().put("op", "list").put("root", root).put("path", path)
        is Read -> JSONObject().put("op", "read").put("root", root).put("path", path)
        is StartSession -> JSONObject().put("op", "session.start").put("root", root).put("prompt", prompt)
            .also { model?.let { m -> it.put("model", m) } }
        is Prompt -> JSONObject().put("op", "session.prompt").put("session", session).put("text", text)
        is Interrupt -> JSONObject().put("op", "session.interrupt").put("session", session)
        is KillSession -> JSONObject().put("op", "session.kill").put("session", session)
        is SessionHistory -> JSONObject().put("op", "session.history").put("session", session)
    }
}

data class Entry(val name: String, val type: String, val size: Long?)

data class FileContent(
    val path: String,
    val content: String,
    val truncated: Boolean,
    val binary: Boolean,
    val size: Long,
)

/** 插件 → App 的事件负载。 */
sealed interface BridgeEvent {
    data class Status(val connected: Boolean, val detail: String?) : BridgeEvent
    data class SessionStarted(val session: String, val root: String) : BridgeEvent
    data class Delta(val session: String, val text: String) : BridgeEvent
    data class Tool(val session: String, val name: String, val detail: String) : BridgeEvent
    data class Result(val session: String, val text: String, val costUsd: Double?, val durationMs: Long?) : BridgeEvent
    data class ErrorEvent(val session: String, val message: String) : BridgeEvent
    data class Ended(val session: String, val code: Int?) : BridgeEvent

    companion object {
        fun parse(o: JSONObject?): BridgeEvent? {
            if (o == null) return null
            return when (val type = o.optString("type")) {
                "status" -> Status(o.optBoolean("connected"), o.optString("detail").ifEmpty { null })
                "session.started" -> SessionStarted(o.optString("session"), o.optString("root"))
                "session.delta" -> Delta(o.optString("session"), o.optString("text"))
                "session.tool" -> Tool(o.optString("session"), o.optString("name"), o.optString("detail"))
                "session.result" -> Result(
                    o.optString("session"), o.optString("text"),
                    if (o.has("costUsd")) o.optDouble("costUsd") else null,
                    if (o.has("durationMs")) o.optLong("durationMs") else null,
                )
                "session.error" -> ErrorEvent(o.optString("session"), o.optString("message"))
                "session.ended" -> Ended(o.optString("session"), if (o.has("code")) o.optInt("code") else null)
                else -> null.also { android.util.Log.w("Proto", "unknown event type: $type") }
            }
        }
    }
}

fun JSONArray.toEntries(): List<Entry> = buildList {
    for (i in 0 until length()) {
        val e = optJSONObject(i) ?: continue
        add(Entry(e.optString("name"), e.optString("type"), if (e.has("size")) e.optLong("size") else null))
    }
}
