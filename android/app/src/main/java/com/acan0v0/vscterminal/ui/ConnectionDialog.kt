package com.acan0v0.vscterminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.acan0v0.vscterminal.crypto.CredentialStore

/**
 * 连接设置面板。主页面始终可用，连接由用户在这里手动发起。
 *
 * @param initial 上次保存的信息，用于回填；没有则为空表单
 */
@Composable
fun ConnectionDialog(
    phase: UiState.Phase,
    error: String?,
    initial: CredentialStore.Endpoint?,
    onConnect: (host: String, port: Int, user: String, token: String, password: String) -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
) {
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember { mutableStateOf((initial?.sshPort ?: 22).toString()) }
    var user by remember { mutableStateOf(initial?.sshUser ?: "root") }
    var token by remember { mutableStateOf(initial?.token.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }

    val connecting = phase == UiState.Phase.CONNECTING
    val connected = phase == UiState.Phase.CONNECTED
    val canSubmit = host.isNotBlank() && user.isNotBlank() && token.isNotBlank() &&
        password.isNotBlank() && !connecting && !connected

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("连接电脑", style = MaterialTheme.typography.titleLarge)
                Text(
                    "通过 SSH 隧道连到 VPS 中继，凭据加密保存在本机。",
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
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
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

                Button(
                    onClick = { onConnect(host, port.toIntOrNull() ?: 22, user, token, password) },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (connecting) {
                        CircularProgressIndicator(modifier = Modifier.heightIn(max = 18.dp), strokeWidth = 2.dp)
                        Text("  连接中…")
                    } else if (connected) {
                        Text("已连接")
                    } else {
                        Text("连接")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = connected || connecting,
                        modifier = Modifier.weight(1f),
                    ) { Text("断开") }
                    OutlinedButton(
                        onClick = onForget,
                        modifier = Modifier.weight(1f),
                    ) { Text("清除凭据") }
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("关闭") }
                }
            }
        }
    }
}
