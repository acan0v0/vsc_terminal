package com.acan0v0.vscterminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acan0v0.vscterminal.data.DirNode
import com.acan0v0.vscterminal.data.FileContent

@Composable
fun FileTree(
    nodes: List<DirNode>,
    currentPath: String,
    onDir: (DirNode) -> Unit,
    onFile: (DirNode) -> Unit,
    onUp: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // 路径导航条：根目录时禁用返回
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onUp, enabled = currentPath.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一级")
            }
            Text(
                text = "/" + currentPath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider()
        if (nodes.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("这个目录是空的，或还没有选择工作区。", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }
        FileList(nodes, onDir, onFile)
    }
}

@Composable
private fun FileList(nodes: List<DirNode>, onDir: (DirNode) -> Unit, onFile: (DirNode) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(nodes, key = { it.path }) { node ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (node.isDir) onDir(node) else onFile(node) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = if (node.isDir) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = if (node.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f)) {
                    Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    node.size?.let {
                        Text(formatSize(it), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

@Composable
fun FileViewer(file: FileContent, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "返回")
            }
            Text(
                text = file.path,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 12.dp),
            )
        }

        when {
            file.binary -> CenterText("二进制文件，不显示内容（${formatSize(file.size)}）")
            file.content.isEmpty() -> CenterText("空文件")
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState()),
            ) {
                if (file.truncated) {
                    Text(
                        "文件较大，仅显示前 256KB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Text(
                    text = file.content,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CenterText(text: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
