package com.infocaller.app.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.infocaller.app.data.repository.ManualCorrections

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
    val stored = remember(number) { ManualCorrections.read(context, number) }

    var name by remember(number) { mutableStateOf(initialName ?: stored["name"] ?: "") }
    var city by remember(number) { mutableStateOf(initialCity ?: stored["city"] ?: "") }
    var carrier by remember(number) { mutableStateOf(initialCarrier ?: stored["carrier"] ?: "") }
    var photo by remember(number) { mutableStateOf(initialPhoto ?: stored["photo"] ?: "") }

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
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = city,
                    onValueChange = { city = it.take(60) },
                    label = { Text("City or area") },
                    singleLine = true,
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
                        { Text("Must start with http:// or https://") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave && photoLooksValid,
                onClick = { onSave(name.trim(), city.trim(), carrier.trim(), photo.trim()) }
            ) { Text("Save and share") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
