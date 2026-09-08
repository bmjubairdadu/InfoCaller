package com.infocaller.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.infocaller.app.ui.theme.Primary
import com.infocaller.app.ui.theme.contentPrimary
import com.infocaller.app.ui.viewmodel.NidPortalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NidPortalScreen(
    onBack: () -> Unit,
    vm: NidPortalViewModel = viewModel(),
) {
    val scroll = rememberScrollState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NID Portal", color = contentPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = contentPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize()
                .verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Guided access to services.nidw.gov.bd using your own details. The captcha is shown for you to read — nothing is bypassed and nothing is stored.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = vm.tab == NidPortalViewModel.Tab.CLAIM,
                    onClick = { vm.switchTab(NidPortalViewModel.Tab.CLAIM) },
                    shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                    label = { Text("Claim account") },
                )
                SegmentedButton(
                    selected = vm.tab == NidPortalViewModel.Tab.CARD,
                    onClick = { vm.switchTab(NidPortalViewModel.Tab.CARD) },
                    shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
                    label = { Text("Smart-card status") },
                )
            }

            IdentityCard(vm)

            when (vm.step) {
                NidPortalViewModel.Step.INPUT -> {
                    Button(
                        onClick = { vm.startSession() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !vm.busy && vm.nid.trim().length >= 7,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (vm.busy) { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text("OPEN SECURE SESSION")
                    }
                }
                NidPortalViewModel.Step.CAPTCHA -> CaptchaCard(vm)
                NidPortalViewModel.Step.ADDRESS -> AddressCard(vm)
                NidPortalViewModel.Step.ADDRESS_RESULT -> ResultCard(vm, showOtp = true)
                NidPortalViewModel.Step.OTP -> ResultCard(vm, showOtp = false)
                NidPortalViewModel.Step.DONE -> ResultCard(vm, showOtp = false)
            }

            vm.status?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = Primary)
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            OutlinedButton(
                onClick = { vm.resetFlow() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Start over") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun IdentityCard(vm: NidPortalViewModel) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Your NID details", style = MaterialTheme.typography.titleSmall, color = Primary, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = vm.nid, onValueChange = { if (it.length <= 17 && it.all { c -> c.isDigit() }) vm.nid = it },
                label = { Text("NID number") }, placeholder = { Text("10 / 13 / 17 digits") },
                leadingIcon = { Icon(Icons.Default.Fingerprint, null) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vm.day, onValueChange = { if (it.length <= 2 && it.all { c -> c.isDigit() }) vm.day = it },
                    label = { Text("Day") }, placeholder = { Text("19") },
                    modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                )
                OutlinedTextField(
                    value = vm.month, onValueChange = { if (it.length <= 2 && it.all { c -> c.isDigit() }) vm.month = it },
                    label = { Text("Month") }, placeholder = { Text("01") },
                    modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                )
                OutlinedTextField(
                    value = vm.year, onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) vm.year = it },
                    label = { Text("Year") }, placeholder = { Text("2007") },
                    modifier = Modifier.weight(1.2f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                )
            }
        }
    }
}

@Composable
private fun CaptchaCard(vm: NidPortalViewModel) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Prove you're human", style = MaterialTheme.typography.titleSmall, color = Primary, fontWeight = FontWeight.Bold)
            val bmp = remember(vm.captchaImageBytes) { vm.captchaBitmap() }
            if (bmp != null) {
                Image(bitmap = bmp.asImageBitmap(), contentDescription = "Portal captcha", modifier = Modifier.fillMaxWidth().height(72.dp))
            } else {
                Text("Captcha not loaded yet.", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vm.captchaText, onValueChange = { if (it.length <= 8) vm.captchaText = it },
                    label = { Text("Captcha text") },
                    modifier = Modifier.weight(1f), singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
                IconButton(onClick = { vm.refreshCaptcha() }, enabled = !vm.busy) {
                    Icon(Icons.Default.Refresh, null, tint = Primary)
                }
            }
            Button(
                onClick = { vm.submitIdentity() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !vm.busy && vm.captchaText.trim().length >= 4,
                shape = RoundedCornerShape(12.dp),
            ) {
                if (vm.busy) { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (vm.tab == NidPortalViewModel.Tab.CLAIM) "VERIFY IDENTITY" else "CHECK CARD STATUS")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddressCard(vm: NidPortalViewModel) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Present address", style = MaterialTheme.typography.titleSmall, color = Primary, fontWeight = FontWeight.Bold)
            AddressPickers(
                divisions = vm.divisions,
                division = vm.division,
                onDivision = { vm.division = it; vm.district = ""; vm.upozila = ""; vm.districts = emptyMap(); vm.upozilas = emptyMap(); vm.loadDistricts(false) },
                districts = vm.districts,
                district = vm.district,
                onDistrict = { vm.district = it; vm.upozila = ""; vm.upozilas = emptyMap(); vm.loadUpozilas(false) },
                upozilas = vm.upozilas,
                upozila = vm.upozila,
                onUpozila = { vm.upozila = it },
            )
            Divider()
            Text("Permanent address", style = MaterialTheme.typography.titleSmall, color = Primary, fontWeight = FontWeight.Bold)
            AddressPickers(
                divisions = vm.divisions,
                division = vm.perDivision,
                onDivision = { vm.perDivision = it; vm.perDistrict = ""; vm.perUpozila = ""; vm.perDistricts = emptyMap(); vm.perUpozilas = emptyMap(); vm.loadDistricts(true) },
                districts = vm.perDistricts,
                district = vm.perDistrict,
                onDistrict = { vm.perDistrict = it; vm.perUpozila = ""; vm.perUpozilas = emptyMap(); vm.loadUpozilas(true) },
                upozilas = vm.perUpozilas,
                upozila = vm.perUpozila,
                onUpozila = { vm.perUpozila = it },
            )
            Button(
                onClick = { vm.submitAddress() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !vm.busy,
                shape = RoundedCornerShape(12.dp),
            ) {
                if (vm.busy) { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text("SUBMIT ADDRESS")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddressPickers(
    divisions: List<Pair<String, String>>,
    division: String, onDivision: (String) -> Unit,
    districts: Map<String, String>,
    district: String, onDistrict: (String) -> Unit,
    upozilas: Map<String, String>,
    upozila: String, onUpozila: (String) -> Unit,
) {
    var divExpanded by remember { mutableStateOf(false) }
    var distExpanded by remember { mutableStateOf(false) }
    var upoExpanded by remember { mutableStateOf(false) }
    val divName = divisions.firstOrNull { it.first == division }?.second ?: "Select division"
    ExposedDropdownMenuBox(expanded = divExpanded, onExpandedChange = { divExpanded = it }) {
        OutlinedTextField(value = divName, onValueChange = {}, readOnly = true, label = { Text("Division") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(divExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(), shape = RoundedCornerShape(12.dp))
        ExposedDropdownMenu(expanded = divExpanded, onDismissRequest = { divExpanded = false }) {
            divisions.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onDivision(id); divExpanded = false })
            }
        }
    }
    ExposedDropdownMenuBox(expanded = distExpanded, onExpandedChange = { distExpanded = it }) {
        val name = districts[district] ?: "Select district"
        OutlinedTextField(value = name, onValueChange = {}, readOnly = true, label = { Text("District") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(distExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(), shape = RoundedCornerShape(12.dp), enabled = districts.isNotEmpty())
        ExposedDropdownMenu(expanded = distExpanded, onDismissRequest = { distExpanded = false }) {
            districts.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onDistrict(id); distExpanded = false })
            }
        }
    }
    ExposedDropdownMenuBox(expanded = upoExpanded, onExpandedChange = { upoExpanded = it }) {
        val name = upozilas[upozila] ?: "Select upazila"
        OutlinedTextField(value = name, onValueChange = {}, readOnly = true, label = { Text("Upazila") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(upoExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(), shape = RoundedCornerShape(12.dp), enabled = upozilas.isNotEmpty())
        ExposedDropdownMenu(expanded = upoExpanded, onDismissRequest = { upoExpanded = false }) {
            upozilas.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onUpozila(id); upoExpanded = false })
            }
        }
    }
}

@Composable
private fun ResultCard(vm: NidPortalViewModel, showOtp: Boolean) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VerifiedUser, null, tint = Primary)
                Spacer(Modifier.width(8.dp))
                Text("Portal result", style = MaterialTheme.typography.titleSmall, color = Primary, fontWeight = FontWeight.Bold)
            }
            Text(vm.resultText ?: "No result yet.", style = MaterialTheme.typography.bodyMedium)
            if (showOtp) {
                Text("The next step sends an OTP to the mobile already registered with this NID.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = { vm.sendOtp() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !vm.busy,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (vm.busy) { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Icon(Icons.Default.Sms, null)
                    Spacer(Modifier.width(8.dp))
                    Text("SEND OTP TO REGISTERED MOBILE")
                }
            }
        }
    }
}
