package com.hatake.cookiechecker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hatake.cookiechecker.CookieItem
import com.hatake.cookiechecker.CookieStatus

@Composable
fun StatusDashboard(total: Int, checked: Int, live: Int, dead: Int, errors: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatChip("Checked", "$checked/$total", MaterialTheme.colorScheme.primaryContainer)
        StatChip("Live", "$live", androidx.compose.ui.graphics.Color(0xFFDFF5E1))
        StatChip("Dead", "$dead", androidx.compose.ui.graphics.Color(0xFFFFE3E3))
        if (errors > 0) StatChip("Err", "$errors", androidx.compose.ui.graphics.Color(0xFFFFF3CD))
    }
}

@Composable
private fun StatChip(label: String, value: String, bg: androidx.compose.ui.graphics.Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun CheckProgress(progress: Float, checked: Int, total: Int) {
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "${(progress * 100).toInt()}%  ($checked/$total)",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun CookieRow(item: CookieItem, onOpen: (() -> Unit)? = null) {
    val (label, color) = when (item.status) {
        CookieStatus.LIVE -> "LIVE" to androidx.compose.ui.graphics.Color(0xFF1B7A2E)
        CookieStatus.DEAD -> "DEAD" to androidx.compose.ui.graphics.Color(0xFFB3261E)
        CookieStatus.ERROR -> "ERROR" to androidx.compose.ui.graphics.Color(0xFF8A6D00)
        CookieStatus.CHECKING -> "..." to MaterialTheme.colorScheme.primary
        CookieStatus.PENDING -> "WAIT" to MaterialTheme.colorScheme.outline
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = {},
                label = { Text(label) },
                colors = AssistChipDefaults.assistChipColors(labelColor = color)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "#${item.id}  ${item.raw.take(90)}${if (item.raw.length > 90) "…" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.detail.isNotBlank() || item.latencyMs > 0) {
                    Text(
                        "${item.detail}  •  ${item.latencyMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            if (item.status == CookieStatus.LIVE && onOpen != null) {
                TextButton(onClick = onOpen) { Text("Open") }
            }
        }
    }
}
