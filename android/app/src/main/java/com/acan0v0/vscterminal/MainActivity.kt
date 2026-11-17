package com.acan0v0.vscterminal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.acan0v0.vscterminal.ui.AppViewModel
import com.acan0v0.vscterminal.ui.ConnectionDialog
import com.acan0v0.vscterminal.ui.FileTree
import com.acan0v0.vscterminal.ui.FileViewer
import com.acan0v0.vscterminal.ui.NoSessionPlaceholder
import com.acan0v0.vscterminal.ui.SessionScreen
import com.acan0v0.vscterminal.net.LinkPhase

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
        androidx.compose.material3.TextButton(onClick = { expanded = true }) {
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
    // 连接面板只在用户主动打开时出现，App 启动后直接停在主页面
    var showConnection by rememberSaveable { mutableStateOf(false) }
    val connected = ui.phase == LinkPhase.CONNECTED

    // 每次回到前台都尝试恢复连接（用户主动断开过则跳过）
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onForeground() }

    // 前台服务需要通知权限才能在状态栏保持可见（Android 13+）
    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val root = ui.currentRoot
                    if (connected && root != null && ui.roots.size > 1) {
                        WorkspacePicker(roots = ui.roots, current = root, onSelect = vm::selectRoot)
                    } else if (connected && root != null) {
                        Text(root, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text("VSC Terminal")
                    }
                },
                actions = {
                    when (ui.phase) {
                        LinkPhase.CONNECTING -> IconButton(onClick = { showConnection = true }) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }

                        LinkPhase.CONNECTED -> IconButton(onClick = { showConnection = true }) {
                            Icon(Icons.Filled.Link, contentDescription = "连接设置")
                        }

                        LinkPhase.DISCONNECTED -> IconButton(onClick = { showConnection = true }) {
                            Icon(
                                Icons.Filled.LinkOff,
                                contentDescription = "连接设置",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
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
            if (!connected) {
                NotConnected(phase = ui.phase, error = ui.error, onOpenSettings = { showConnection = true })
                return@Column
            }
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

    if (showConnection) {
        ConnectionDialog(
            phase = ui.phase,
            error = ui.error,
            initial = remember { vm.savedEndpoint() },
            onConnect = { host, port, user, token, password ->
                vm.connect(host, port, user, token, password)
            },
            onDisconnect = vm::disconnect,
            onForget = {
                vm.forget()
            },
            onDismiss = { showConnection = false },
        )
    }
}

@Composable
private fun NotConnected(phase: LinkPhase, error: String?, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (phase == LinkPhase.CONNECTING) "正在连接电脑…" else "尚未连接电脑",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "填好 VPS 地址、relay token 和 SSH 密码后即可浏览工作区、给 agent 下命令。",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        Button(onClick = onOpenSettings) { Text("连接设置") }
    }
}
