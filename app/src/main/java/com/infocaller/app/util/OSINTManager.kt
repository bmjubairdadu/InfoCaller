package com.infocaller.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object OSINTManager {

    data class DorkLink(
        val title: String,
        val description: String,
        val url: String,
        val icon: ImageVector? = null
    )

    fun getCommonUssdCodes(): List<DorkLink> {
        return listOf(
            DorkLink("Check Balance", "Universal balance check for most BD operators", "*566#", Icons.Default.AccountBalanceWallet),
            DorkLink("My Number", "Check your own SIM number", "*2#", Icons.Default.Smartphone),
            DorkLink("bKash Menu", "Mobile financial service menu", "*247#", Icons.Default.Payments),
            DorkLink("Nagad Menu", "Mobile financial service menu", "*167#", Icons.Default.Payments),
            DorkLink("Operator Menu", "General service menu (GP/Robi/BL)", "*121#", Icons.Default.Menu),
            DorkLink("Internet Balance", "Check remaining data volume", "*121*1*4#", Icons.Default.DataUsage)
        )
    }

    fun generateNidDorkLinks(nid: String, dob: String): List<DorkLink> {
        val links = mutableListOf<DorkLink>()
        
        links.add(DorkLink(
            "NID Identity Check",
            "Search for identity details using NID number",
            "https://www.google.com/search?q=${urlEncode("\"$nid\" identity OR verification OR profile")}",
            Icons.Default.Fingerprint
        ))

        links.add(DorkLink(
            "Gov Portal Discovery",
            "Search within government portals for this NID",
            "https://www.google.com/search?q=${urlEncode("site:gov.bd \"$nid\"")}",
            Icons.Default.AdminPanelSettings
        ))

        if (dob.isNotBlank()) {
            links.add(DorkLink(
                "Full Record Pivot",
                "Combined NID and DOB deep search",
                "https://www.google.com/search?q=${urlEncode("\"$nid\" \"$dob\"")}",
                Icons.Default.ManageSearch
            ))
        }

        return links
    }

    fun generateExtendedDorkLinks(phoneNumber: String): List<DorkLink> {
        val e164 = PhoneNumberUtils.normalize(phoneNumber)
        val clean = e164.replace("+", "")
        
        val links = mutableListOf<DorkLink>()
        
        links.add(DorkLink(
            "Burner Check (SMS Online)",
            "Detect if number is a public temporary VoIP",
            "https://www.google.com/search?q=${urlEncode("site:receive-sms-online.info OR site:sms-receive.net \"$clean\"")}",
            Icons.Default.VpnKey
        ))

        links.addAll(listOf(
            DorkLink(
                "Google Global Search",
                "Find public mentions across the web",
                "https://www.google.com/search?q=${urlEncode("\"$e164\" OR \"$phoneNumber\"")}",
                Icons.Default.Search
            ),
            DorkLink(
                "Facebook Search",
                "Search directly within Facebook platform",
                "https://www.facebook.com/search/top/?q=${urlEncode(e164)}",
                Icons.Default.Share
            ),
            DorkLink(
                "Instagram Lookup",
                "Verify if number is associated with IG",
                "https://www.google.com/search?q=${urlEncode("site:instagram.com \"$e164\"")}",
                Icons.Default.CameraAlt
            ),
            DorkLink(
                "Truecaller Web",
                "Official web-based identification",
                "https://www.truecaller.com/search/bd/$clean",
                Icons.Default.Person
            ),
            DorkLink(
                "IntelligenceX",
                "Deep search for leaks and documents",
                "https://intelx.io/?s=${urlEncode(e164)}",
                Icons.Default.ManageSearch
            ),
            DorkLink(
                "BD Public Database Dork",
                "Search in public BD government/voter dorks",
                "https://www.google.com/search?q=${urlEncode("site:services.nidw.gov.bd OR site:ec.org.bd \"$clean\"")}",
                Icons.Default.AdminPanelSettings
            ),
            DorkLink(
                "Google Maps Search",
                "Search for number in business listings",
                "https://www.google.com/maps/search/${urlEncode(e164)}",
                Icons.Default.Map
            ),
            DorkLink(
                "WhatsApp Link",
                "Directly message without saving contact",
                "https://wa.me/${clean}",
                Icons.AutoMirrored.Filled.Chat
            ),
            DorkLink(
                "Ahmia (Dark Web)",
                "Search clear-web index of Tor sites",
                "https://ahmia.fi/search/?q=${urlEncode(e164)}",
                Icons.Default.VisibilityOff
            )
        ))
        
        return links
    }

    fun openLink(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    }
}
