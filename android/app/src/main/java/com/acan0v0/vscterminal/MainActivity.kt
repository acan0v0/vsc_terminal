package com.acan0v0.vscterminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.acan0v0.vscterminal.ui.AppViewModel
import com.acan0v0.vscterminal.ui.ConnectScreen
import com.acan0v0.vscterminal.ui.FileTree
import com.acan0v0.vscterminal.ui.FileViewer
import com.acan0v0.vscterminal.ui.NoSessionPlaceholder
import com.acan0v0.vscterminal.ui.ReconnectScreen
import com.acan0v0.vscterminal.ui.SessionScreen
import com.acan0v0.vscterminal.ui.UiState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppRoot()
            }
        }
    }
}

@Composable
private fun WorkspacePicker(roots: List<String>, current: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(current.substringAfterLast('\\').substringAfterLast('/'), maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "切换工作区")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            roots.forEach { r ->
                DropdownMenuItem(
                    text = { Text(r, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        if (r != current) onSelect(r)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(vm: AppViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }

    // 首次进入且已保存凭据时自动连接
    var autoTried by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoTried && vm.hasCredentials && ui.phase == UiState.Phase.DISCONNECTED) {
            autoTried = true
            vm.connect()
        }
    }

    when (ui.phase) {
        UiState.Phase.DISCONNECTED -> {
            if (vm.hasCredentials) {
                ReconnectScreen(ui.phase, ui.error, onConnect = vm::connect, onForget = vm::forget)
            } else {
                ConnectScreen(ui.phase, ui.error) { host, port, user, token, password ->
                    vm.saveCredentials(host, port, user, token, password)
                    vm.connect()
                }
            }
        }

        else -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            val root = ui.currentRoot
                            if (ui.phase == UiState.Phase.CONNECTED && root != null && ui.roots.size > 1) {
                                WorkspacePicker(roots = ui.roots, current = root, onSelect = vm::selectRoot)
                            } else {
                                Text(root ?: if (ui.phase == UiState.Phase.CONNECTED) "VSC Terminal" else "连接中…")
                            }
                        },
                    )
                },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                            label = { Text("文件") },
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                            label = { Text("会话") },
                        )
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    ui.error?.let { err ->
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    when (tab) {
                        0 -> {
                            val file = ui.openFile
                            if (file != null) {
                                FileViewer(file, onBack = vm::closeFile)
                            } else {
                                FileTree(
                                    nodes = ui.tree,
                                    currentPath = ui.currentPath,
                                    onDir = { vm.enterDir(it.path) },
                                    onFile = { vm.openFile(it.path) },
                                    onUp = vm::goUp,
                                )
                            }
                        }
                        1 -> {
                            val session = ui.session
                            if (session == null) {
                                NoSessionPlaceholder(onNewSession = vm::startSession, root = ui.currentRoot)
                            } else {
                                SessionScreen(session, onSend = vm::sendPrompt, onClose = vm::closeSession)
                            }
                        }
                    }
                }
            }
        }
    }
}
