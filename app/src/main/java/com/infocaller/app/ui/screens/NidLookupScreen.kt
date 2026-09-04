package com.infocaller.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.infocaller.app.domain.engine.IdentifierType
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.ui.viewmodel.SearchUiState

@Composable
fun NidLookupScreen(viewModel: CallerViewModel) {
    var nid by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val searchResult by viewModel.searchResult.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("NID Lookup — Bangladesh", style = MaterialTheme.typography.headlineSmall)
        Text("115k+ local database + OSINT. Enter NID and DOB to get full identity: name, father/mother, address, photo.", style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(value = nid, onValueChange = { nid = it.filter { c -> c.isDigit() } }, label = { Text("NID (10/13/17 digits)") }, leadingIcon = { Icon(Icons.Default.Fingerprint, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = dob, onValueChange = { dob = it }, label = { Text("Date of Birth (YYYY-MM-DD)") }, leadingIcon = { Icon(Icons.Default.Cake, null) }, placeholder = { Text("1992-10-11") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (nid.length < 7) { error = "Enter valid NID (min 7 digits)"; return@Button }
                if (dob.isNotBlank() && !Regex("\\d{4}-\\d{2}-\\d{2}").matches(dob)) { error = "DOB must be YYYY-MM-DD (e.g. 1992-10-11)"; return@Button }
                error = null
                val identifier = if (dob.isNotBlank()) "$nid|$dob" else nid
                viewModel.searchByIdentifier(identifier, IdentifierType.NID)
            }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text("Search NID")
            }
            OutlinedButton(onClick = { nid = ""; dob = ""; error = null; viewModel.clearSearch() }) { Text("Clear") }
        }

        when (val s = searchResult) {
            is SearchUiState.Loading -> { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()); Text("Searching offline DB + OSINT...", style = MaterialTheme.typography.bodySmall) }
            is SearchUiState.Success -> {
                val r = s.caller
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Result", style = MaterialTheme.typography.titleMedium)
                        Text("Name: ${r.displayName ?: "-"}")
                        Text("Phone: ${r.phoneNumber ?: "-"}")
                        Text("NID: $nid")
                        if (dob.isNotBlank()) Text("DOB: $dob")
                        r.photoUrl?.let { Text("Photo: $it", maxLines = 2) }
                        r.organization?.let { Text("Carrier/Org: $it") }
                        if (s.isLive) Text("Live updating...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            is SearchUiState.Error -> { Text(s.message, color = MaterialTheme.colorScheme.error) }
            is SearchUiState.Idle -> { Text("Enter an NID above to search the offline database.", style = MaterialTheme.typography.bodySmall) }
            is SearchUiState.NotFound -> { Text("No record found for this NID.", style = MaterialTheme.typography.bodySmall) }
        }
        HorizontalDivider()
        Text("Tip: Search by phone number also auto-resolves NID/DOB from the same database.", style = MaterialTheme.typography.bodySmall)
    }
}
