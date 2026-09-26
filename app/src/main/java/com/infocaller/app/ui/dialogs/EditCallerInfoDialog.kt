package com.infocaller.app.ui.dialogs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.infocaller.app.data.repository.ManualCorrections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun EditCallerInfoDialog(
    number: String,
    initialName: String?,
    initialCity: String?,
    initialCarrier: String?,
    initialPhoto: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, city: String, carrier: String, photo: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stored = remember(number) { ManualCorrections.read(context, number) }

    var name by remember(number) { mutableStateOf(initialName ?: stored["name"] ?: "") }
    var city by remember(number) { mutableStateOf(initialCity ?: stored["city"] ?: "") }
    var carrier by remember(number) { mutableStateOf(initialCarrier ?: stored["carrier"] ?: "") }
    var photo by remember(number) { mutableStateOf(initialPhoto ?: stored["photo"] ?: "") }
    var picking by remember { mutableStateOf(false) }

    fun cachePickedImage(uri: android.net.Uri, onDone: (String?) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val digits = number.filter { it.isDigit() }.takeLast(15).ifBlank { "manual" }
                val dir = File(context.cacheDir, "manual_photos").apply { if (!exists()) mkdirs() }
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@launch withContext(Dispatchers.Main) { onDone(null) }
                if (bytes.size < 200 || bytes.size > 12_000_000) {
                    return@launch withContext(Dispatchers.Main) { onDone(null) }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return@launch withContext(Dispatchers.Main) { onDone(null) }
                }
                var sample = 1
                while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    ?: return@launch withContext(Dispatchers.Main) { onDone(null) }
                // Center-square crop so the circle preview matches exactly what is saved.
                val side = minOf(bmp.width, bmp.height)
                val x = (bmp.width - side) / 2
                val y = (bmp.height - side) / 2
                val cropped = try {
                    Bitmap.createBitmap(bmp, x, y, side, side)
                } catch (_: Exception) { bmp } catch (_: Error) { bmp }
                val finalBmp = if (side > 1024) {
                    try { Bitmap.createScaledBitmap(cropped, 1024, 1024, true) } catch (_: Exception) { cropped } catch (_: OutOfMemoryError) { cropped }
                } else cropped
                val file = File(dir, "manual_$digits.jpg")
                try {
                    FileOutputStream(file).use { out ->
                        finalBmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                } catch (_: Exception) {
                    return@launch withContext(Dispatchers.Main) { onDone(null) }
                } finally {
                    try { if (finalBmp !== bmp) finalBmp.recycle() } catch (_: Exception) { }
                    try { bmp.recycle() } catch (_: Exception) { }
                }
                withContext(Dispatchers.Main) { onDone(file.toURI().toString()) }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { onDone(null) }
            } catch (_: Error) {
                withContext(Dispatchers.Main) { onDone(null) }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        picking = false
        if (uri == null) return@rememberLauncherForActivityResult
        picking = true
        cachePickedImage(uri) { saved ->
            picking = false
            if (!saved.isNullOrBlank()) photo = saved
        }
    }

    val photoLooksValid = photo.isBlank() || ManualCorrections.isValidUrl(photo) != null
    val canSave = name.isNotBlank() || city.isNotBlank() || carrier.isNotBlank() || photo.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit info for $number") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Your edit is saved on this device and shared with other InfoCaller users straight away.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Circle-cropped preview of the current photo.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (photo.isNotBlank() && photoLooksValid) {
                        AsyncImage(
                            model = photo,
                            contentDescription = "Profile photo preview",
                            modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(64.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No photo", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        TextButton(onClick = { picker.launch("image/*") }, enabled = !picking) {
                            Text(if (picking) "Loading…" else "Choose photo")
                        }
                        if (photo.isNotBlank()) {
                            TextButton(onClick = { photo = "" }) { Text("Remove") }
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = city,
                    onValueChange = { city = it.take(120) },
                    label = { Text("City or area") },
                    singleLine = true,
                    supportingText = { Text("Short is fine — e.g. Dhaka, Uttara. Full postcode & city corporation are added automatically.") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = carrier,
                    onValueChange = { carrier = it.take(60) },
                    label = { Text("Operator") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = photo,
                    onValueChange = { photo = it.take(2000) },
                    label = { Text("Photo link") },
                    singleLine = true,
                    isError = !photoLooksValid,
                    supportingText = if (!photoLooksValid) {
                        { Text("Must start with http://, https:// or be a picked photo") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave && photoLooksValid && !picking,
                onClick = { onSave(name.trim(), city.trim(), carrier.trim(), photo.trim()) }
            ) { Text("Save and share") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
