package com.infocaller.app.ui.viewmodel

import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.infocaller.app.data.remote.nidportal.NidDivisions
import com.infocaller.app.data.remote.nidportal.NidPortalService
import kotlinx.coroutines.launch

/**
 * User-driven NID portal assistant. Every step uses the exact request shapes
 * from the user's own Reqable captures; only the captcha image is shown and
 * the user types what they read — no automation, no stored credentials.
 */
class NidPortalViewModel : ViewModel() {
    enum class Tab { CLAIM, CARD }
    enum class Step { INPUT, CAPTCHA, ADDRESS, ADDRESS_RESULT, OTP, DONE }

    var tab by mutableStateOf(Tab.CLAIM)
        private set
    var step by mutableStateOf(Step.INPUT)
        private set
    var nid by mutableStateOf("")
    var day by mutableStateOf("")
    var month by mutableStateOf("")
    var year by mutableStateOf("")
    var captchaText by mutableStateOf("")
    var captchaImageBytes by mutableStateOf<ByteArray?>(null)
    var status by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    var opened by mutableStateOf(false)
        private set

    // Address step (claim flow only)
    var division by mutableStateOf("")
    var district by mutableStateOf("")
    var upozila by mutableStateOf("")
    var perDivision by mutableStateOf("")
    var perDistrict by mutableStateOf("")
    var perUpozila by mutableStateOf("")
    var districts by mutableStateOf(mapOf<String, String>())
    var upozilas by mutableStateOf(mapOf<String, String>())
    var perDistricts by mutableStateOf(mapOf<String, String>())
    var perUpozilas by mutableStateOf(mapOf<String, String>())
    var resultText by mutableStateOf<String?>(null)

    val divisions = NidDivisions.all
    private val service = NidPortalService()

    fun switchTab(t: Tab) {
        if (tab == t) return
        tab = t
        resetFlow(keepInput = true)
    }

    fun resetFlow(keepInput: Boolean = false) {
        if (!keepInput) { nid = ""; day = ""; month = ""; year = "" }
        captchaText = ""; captchaImageBytes = null
        division = ""; district = ""; upozila = ""
        perDivision = ""; perDistrict = ""; perUpozila = ""
        districts = emptyMap(); upozilas = emptyMap()
        perDistricts = emptyMap(); perUpozilas = emptyMap()
        resultText = null; status = null; opened = false
        step = Step.INPUT
    }

    fun captchaBitmap(): android.graphics.Bitmap? {
        val b = captchaImageBytes ?: return null
        return try { BitmapFactory.decodeByteArray(b, 0, b.size) } catch (_: Exception) { null }
    }

    private fun referer() = if (tab == Tab.CLAIM)
        "https://services.nidw.gov.bd/nid-pub/claim-account"
    else
        "https://services.nidw.gov.bd/nid-pub/card-status"

    fun startSession() {
        if (busy) return
        if (nid.trim().length < 7 || day.isBlank() || month.isBlank() || year.isBlank()) {
            status = "Enter NID number and full date of birth first."
            return
        }
        busy = true; status = "Opening secure portal session…"
        viewModelScope.launch {
            val ok = if (tab == Tab.CLAIM) service.openClaimAccount() else service.openCardStatus()
            // Clear busy BEFORE chaining into refreshCaptcha(): it early-outs
            // while busy, so chaining with the flag still set would silently
            // skip loading the captcha after a successful session open.
            busy = false
            if (!ok) { status = "Portal unreachable. Check connection and retry."; return@launch }
            opened = true
            refreshCaptcha()
        }
    }

    fun refreshCaptcha() {
        if (busy) return
        busy = true; status = "Loading captcha…"
        viewModelScope.launch {
            try {
                val png = service.captchaPng(referer())
                captchaImageBytes = png
                status = if (png == null) "Captcha failed to load — retry." else "Type the letters/numbers you see."
                if (png != null && step == Step.INPUT) step = Step.CAPTCHA
            } finally { busy = false }
        }
    }

    fun submitIdentity() {
        if (busy || !opened) return
        if (captchaText.trim().length < 4) { status = "Type the captcha first."; return }
        busy = true; status = "Verifying with NID portal…"
        viewModelScope.launch {
            // No try/finally here on purpose: the failure branches clear busy
            // themselves BEFORE chaining into refreshCaptcha() (which
            // early-outs while busy). A finally would race the chained call.
            try {
                val r = if (tab == Tab.CLAIM)
                    service.validateClaim(nid.trim(), day.trim(), month.trim(), year.trim(), captchaText.trim())
                else
                    service.validateCard(nid.trim(), day.trim(), month.trim(), year.trim(), captchaText.trim())
                if (r == null) { status = "No response from portal."; busy = false; return@launch }
                when {
                    r.status == "SUCCESS" && tab == Tab.CARD -> {
                        val view = service.smartCardStatusView()
                        resultText = view ?: "Verified, but the status page was empty."
                        status = "Smart-card status loaded."
                        step = Step.DONE
                        busy = false
                    }
                    r.status == "SUCCESS" && r.dataRaw == "true" -> {
                        status = "Identity accepted — pick present + permanent address."
                        step = Step.ADDRESS
                        busy = false
                    }
                    r.status == "SUCCESS" -> {
                        status = "Portal answered but did not accept the details. Check NID/DOB/captcha."
                        busy = false
                        refreshCaptcha()
                    }
                    else -> {
                        status = "Portal: ${r.status}${r.errorRaw?.let { " — ${it.take(160)}" } ?: ""}"
                        busy = false
                        refreshCaptcha()
                    }
                }
            } catch (_: Exception) {
                status = "Unexpected error — retry."
                busy = false
            }
        }
    }

    fun loadDistricts(forPermanent: Boolean) {
        val div = if (forPermanent) perDivision else division
        if (div.isBlank() || busy) return
        busy = true
        viewModelScope.launch {
            try {
                val m = service.districts(div)
                if (forPermanent) perDistricts = m else districts = m
                status = if (m.isEmpty()) "District list failed — retry." else null
            } finally { busy = false }
        }
    }

    fun loadUpozilas(forPermanent: Boolean) {
        val dist = if (forPermanent) perDistrict else district
        if (dist.isBlank() || busy) return
        busy = true
        viewModelScope.launch {
            try {
                val m = service.upozilas(dist)
                if (forPermanent) perUpozilas = m else upozilas = m
                status = if (m.isEmpty()) "Upazila list failed — retry." else null
            } finally { busy = false }
        }
    }

    fun submitAddress() {
        if (busy) return
        if (division.isBlank() || district.isBlank() || upozila.isBlank() ||
            perDivision.isBlank() || perDistrict.isBlank() || perUpozila.isBlank()
        ) { status = "Select present + permanent division, district and upazila."; return }
        busy = true; status = "Submitting address…"
        viewModelScope.launch {
            try {
                val r = service.validateAddress(division, district, upozila, perDivision, perDistrict, perUpozila)
                if (r?.status == "SUCCESS") {
                    val view = service.oldMobileEmailView()
                    resultText = view ?: "Address accepted — registered mobile shown next on the portal."
                    status = "Address accepted."
                    step = Step.ADDRESS_RESULT
                } else {
                    status = "Portal: ${r?.status ?: "no response"}${r?.errorRaw?.let { " — ${it.take(160)}" } ?: ""}"
                }
            } finally { busy = false }
        }
    }

    fun sendOtp() {
        if (busy) return
        busy = true; status = "Requesting OTP…"
        viewModelScope.launch {
            try {
                val r = service.sendOtpSms()
                if (r?.status == "PENDING") {
                    status = "OTP sent to the registered mobile. Enter it on the portal to finish."
                    step = Step.OTP
                } else {
                    status = "Portal: ${r?.status ?: "no response"}${r?.errorRaw?.let { " — ${it.take(160)}" } ?: ""}"
                }
            } finally { busy = false }
        }
    }
}
