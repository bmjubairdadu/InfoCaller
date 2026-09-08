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
import com.infocaller.app.ui.theme.GradientEnd
import com.infocaller.app.ui.theme.GradientStart
import com.infocaller.app.ui.theme.contentPrimary
import com.infocaller.app.ui.theme.contentSecondary
import com.infocaller.app.ui.theme.faintTint
import com.infocaller.app.ui.theme.glassy
import com.infocaller.app.util.SimInfo
import com.infocaller.app.util.SimManager

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Shared SIM logo with guaranteed fallback chain: cached Brandfetch file ->
 * live Brandfetch URL -> system carrier icon -> brand-color initial.
 * Every call site (picker, dialer, recents, details) uses this so a missing
 * cache or failed download shows an initial instead of a blank gap.
 */
@Composable
fun SimLogo(
    sim: SimInfo,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fontSize: TextUnit = 14.sp
) {
    var useRemote by remember(sim.localLogoPath) { mutableStateOf(false) }
    var remoteFailed by remember(sim.carrierName) { mutableStateOf(false) }
    val localFile = remember(sim.localLogoPath) {
        sim.localLogoPath?.takeIf { it.isNotBlank() }?.let {
            try { java.io.File(it).takeIf { f -> f.exists() } } catch (_: Exception) { null }
        }
    }
    val remoteUrl = remember(sim.carrierName, sim.displayName, sim.mcc, sim.mnc) {
        try {
            com.infocaller.app.util.OperatorBrandResolver
                .resolveBrand(sim.carrierName, sim.displayName, sim.mcc, sim.mnc)
                .officialDomain?.let { com.infocaller.app.util.SimManager.buildBrandfetchLogoUrl(it) }
        } catch (_: Exception) { null }
    }
    when {
        localFile != null && !useRemote -> {
            AsyncImage(
                model = localFile,
                contentDescription = sim.carrierName,
                modifier = modifier.clip(CircleShape),
                contentScale = contentScale,
                onError = { useRemote = true }
            )
        }
        !remoteFailed && remoteUrl != null -> {
            AsyncImage(
                model = remoteUrl,
                contentDescription = sim.carrierName,
                modifier = modifier.clip(CircleShape),
                contentScale = contentScale,
                onError = { remoteFailed = true }
            )
        }
        sim.iconBitmap != null -> {
            Image(
                bitmap = sim.iconBitmap.asImageBitmap(),
                contentDescription = sim.carrierName,
                modifier = modifier.clip(CircleShape)
            )
        }
        else -> {
            Box(
                modifier = modifier
                    .clip(CircleShape)
                    .background(Color(sim.brandColor)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = sim.carrierName.firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSize
                )
            }
        }
    }
}

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
            // Operator Logo / Brand Circle (shared fallback chain: cached ->
            // remote -> system icon -> brand initial, never a blank gap).
            Box(
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.Center
            ) {
                SimLogo(sim = sim, modifier = Modifier.size(52.dp), fontSize = 22.sp)
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sim.carrierName,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${sim.displayName} (Slot ${sim.slotIndex + 1})",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentSecondary(0.6f)
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
        containerColor = MaterialTheme.colorScheme.surface,
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
                        .background(faintTint(0.3f))
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
                color = contentPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 24.dp)
            )

            HorizontalDivider(color = faintTint(0.1f))

            if (simInfos.isEmpty()) {
                Text(
                    text = "No active SIM cards detected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentSecondary(0.6f),
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
