package com.infocaller.app.ui.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infocaller.app.ui.theme.Background
import com.infocaller.app.ui.theme.GradientEnd
import com.infocaller.app.ui.theme.GradientStart
import com.infocaller.app.ui.theme.glassy
import com.infocaller.app.util.SimInfo
import com.infocaller.app.util.SimManager

import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

@Composable
private fun SimRow(
    sim: SimInfo,
    phoneNumber: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .glassy(radius = 16.dp, blur = 10.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Operator Logo / Brand Circle
            Box(
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.Center
            ) {
                // Priority 1: Local cached Brandfetch logo (MOST PREFERRED)
                if (sim.localLogoPath != null) {
                    AsyncImage(
                        model = sim.localLogoPath,
                        contentDescription = sim.carrierName,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } 
                // Priority 2: System carrier icon (FALLBACK)
                else if (sim.iconBitmap != null) {
                    Image(
                        bitmap = sim.iconBitmap.asImageBitmap(),
                        contentDescription = sim.carrierName,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                    )
                } 
                // Priority 3: Initials + Brand Color
                else {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color(sim.brandColor)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = sim.carrierName.firstOrNull()?.uppercase() ?: "?",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sim.carrierName,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${sim.displayName} (Slot ${sim.slotIndex + 1})",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            
            // CIRCULAR CALL BUTTON (48dp target)
            Surface(
                onClick = onClick,
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = Color.Transparent
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(GradientStart, GradientEnd)
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Call, 
                        contentDescription = "Call with ${sim.carrierName}", 
                        tint = Color.White, 
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimSelectionBottomSheet(
    phoneNumber: String,
    onSimSelected: (SimInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var simInfos by remember { mutableStateOf<List<SimInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        simInfos = SimManager.getSimInfos(context)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.3f))
                        .clip(RoundedCornerShape(2.dp))
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Choose SIM for $phoneNumber",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 24.dp)
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            if (simInfos.isEmpty()) {
                Text(
                    text = "No active SIM cards detected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(32.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    simInfos.forEach { sim ->
                        SimRow(
                            sim = sim,
                            phoneNumber = phoneNumber,
                            onClick = {
                                onSimSelected(sim)
                            }
                        )
                    }
                }
            }
        }
    }
}
