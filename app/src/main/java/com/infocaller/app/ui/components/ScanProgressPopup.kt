package com.infocaller.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.infocaller.app.ui.theme.Primary
import com.infocaller.app.ui.theme.contentPrimary
import com.infocaller.app.ui.theme.contentSecondary
import com.infocaller.app.ui.theme.faintTint
import com.infocaller.app.ui.viewmodel.ScanStepUi

@Composable
fun ScanProgressPopup(
    identifier: String,
    steps: List<ScanStepUi>,
    scanActive: Boolean,
    onDismiss: () -> Unit,
) {
    if (!scanActive && steps.isEmpty()) return
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // App loading animation on top.
                InfoCallerLoading(size = 56.dp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Scanning",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = contentPrimary
                )
                Text(
                    identifier,
                    style = MaterialTheme.typography.bodySmall,
                    color = Primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                val done = steps.count { it.status == "SUCCESS" || it.status == "FAILED" || it.status == "SKIPPED" }
                val total = steps.maxOfOrNull { it.stepTotal } ?: 0
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (done.coerceAtMost(total)).toFloat() / total.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Primary,
                        trackColor = faintTint(0.12f),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "$done of $total tools",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentSecondary(0.6f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (steps.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Preparing lookup tools…",
                            style = MaterialTheme.typography.bodySmall,
                            color = contentSecondary(0.75f)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        steps.take(8).forEach { step ->
                            ScanStepRow(step = step)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (scanActive) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Hide") }
                } else {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("View result") }
                }
            }
        }
    }
}

@Composable
private fun ScanStepRow(step: ScanStepUi) {
    val (icon, tint) = when (step.status) {
        "RUNNING" -> Icons.Default.Sync to Primary
        "SUCCESS" -> Icons.Default.CheckCircle to Color(0xFF22C55E)
        "FAILED" -> Icons.Default.Error to Color(0xFFEF4444)
        else -> Icons.Default.RemoveCircle to faintTint(0.35f)
    }
    val statusText = when (step.status) {
        "RUNNING" -> "Scanning…"
        "SUCCESS" -> "Done"
        "FAILED" -> "No match"
        else -> "Skipped"
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(faintTint(0.05f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (step.status == "RUNNING") {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Primary
            )
        } else {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                step.providerName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentPrimary
            )
            Text(
                "Tool ${step.stepIndex} of ${step.stepTotal} · $statusText",
                style = MaterialTheme.typography.labelSmall,
                color = contentSecondary(0.55f)
            )
        }
        if (step.status == "RUNNING") {
            val infinite = rememberInfiniteTransition(label = "StepPulse")
            val alpha by infinite.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                label = "StepAlpha"
            )
            Box(
                modifier = Modifier.size(8.dp)
                    .background(Primary.copy(alpha = alpha), CircleShape)
            )
        }
    }
}
