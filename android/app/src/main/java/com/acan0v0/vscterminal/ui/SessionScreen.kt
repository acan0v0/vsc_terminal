package com.acan0v0.vscterminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acan0v0.vscterminal.data.ChatMessage
import com.acan0v0.vscterminal.data.SessionState

@Composable
fun SessionScreen(
    session: SessionState,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(session.messages.size, session.messages.lastOrNull()?.text?.length) {
        if (session.messages.isNotEmpty()) listState.animateScrollToItem(session.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("会话", style = MaterialTheme.typography.titleSmall)
                Text(session.root, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            if (session.running) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "结束会话")
            }
        }
        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(session.messages.size) { i -> MessageBubble(session.messages[i]) }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("给 agent 下命令…") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
            )
            FilledIconButton(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty() && session.running.not()) {
                        onSend(text)
                        input = ""
                    }
                },
                enabled = input.isNotBlank() && !session.running,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == ChatMessage.Role.USER
    val bg = when (msg.role) {
        ChatMessage.Role.USER -> MaterialTheme.colorScheme.primaryContainer
        ChatMessage.Role.ERROR -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val align = if (isUser) Alignment.End else Alignment.Start

    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Surface(
            color = bg,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (msg.text.isNotBlank()) {
                    Text(
                        text = msg.text,
                        fontFamily = if (msg.role == ChatMessage.Role.ERROR) FontFamily.Default else FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
                if (msg.streaming && msg.text.isBlank()) {
                    Text("…", style = MaterialTheme.typography.bodySmall)
                }
                msg.tools.forEach { tool ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(12.dp))
                        Text(tool, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                }
                msg.costUsd?.let {
                    Text("$%.4f".format(it), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun NoSessionPlaceholder(onNewSession: (String) -> Unit, root: String?) {
    var prompt by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("在这个工作区开一个新会话", style = MaterialTheme.typography.titleMedium)
            Text(
                root ?: "请先在「文件」页选择一个工作区",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = { Text("描述你想让 agent 做的事…") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 6,
            )
            FilledIconButton(
                onClick = {
                    if (prompt.isNotBlank() && root != null) {
                        onNewSession(prompt.trim())
                        prompt = ""
                    }
                },
                enabled = prompt.isNotBlank() && root != null,
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "开始")
            }
        }
    }
}
