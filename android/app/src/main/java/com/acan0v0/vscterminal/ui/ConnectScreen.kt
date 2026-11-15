package com.acan0v0.vscterminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ConnectScreen(
    phase: UiState.Phase,
    error: String?,
    onConnect: (host: String, port: Int, user: String, token: String, password: String) -> Unit,
) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var user by rememberSaveable { mutableStateOf("root") }
    var token by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val connecting = phase == UiState.Phase.CONNECTING
    val canSubmit = host.isNotBlank() && token.isNotBlank() && password.isNotBlank() && !connecting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("连接电脑", style = MaterialTheme.typography.headlineSmall)
        Text(
            "通过 SSH 隧道连到你的 VPS 中继，凭据加密保存在本机。",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("VPS 地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("SSH 端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("relay token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("SSH 密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = { onConnect(host, port.toIntOrNull() ?: 22, user, token, password) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (connecting) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(0.dp))
                Text("  连接中…")
            } else {
                Text("连接")
            }
        }
    }
}

/** 已保存凭据时的简化入口。 */
@Composable
fun ReconnectScreen(phase: UiState.Phase, error: String?, onConnect: () -> Unit, onForget: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("已保存连接信息", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
        }
        Button(onClick = onConnect, enabled = phase == UiState.Phase.DISCONNECTED, modifier = Modifier.fillMaxWidth()) {
            Text(if (phase == UiState.Phase.CONNECTING) "连接中…" else "连接")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) {
            Text("清除并重新填写")
        }
    }
}
