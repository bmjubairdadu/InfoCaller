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
            ),
            DorkLink(
                "EPIOS (Google OSINT)",
                "Free Google-ads / Maps / review pivot for this number",
                "https://epios-app.netlify.app/?q=${urlEncode(e164)}",
                Icons.Default.TravelExplore
            ),
            DorkLink(
                "Dehashed (Preview)",
                "Preview breach exposure for this number",
                "https://dehashed.com/search?query=${urlEncode(e164)}",
                Icons.Default.Security
            ),
            DorkLink(
                "Grep.app Code Search",
                "Search public GitHub code mentioning this number",
                "https://grep.app/search?q=${urlEncode(e164)}",
                Icons.Default.Code
            ),
            DorkLink(
                "GitHub Code Search",
                "Native GitHub code search for this number",
                "https://github.com/search?q=${urlEncode("\"$clean\"")}&type=code",
                Icons.Default.Code
            ),
            DorkLink(
                "Mojeek Search",
                "Independent index, rarely CAPTCHAs on-device",
                "https://www.mojeek.com/search?q=${urlEncode("\"$e164\" OR \"$clean\"")}",
                Icons.Default.Public
            ),
            DorkLink(
                "Brave Search",
                "Independent index fallback for Google blocks",
                "https://search.brave.com/search?q=${urlEncode("\"$e164\" OR \"$clean\"")}",
                Icons.Default.Shield
            ),
            DorkLink(
                "Startpage Search",
                "Google results via privacy proxy",
                "https://www.startpage.com/sp/search?query=${urlEncode("\"$e164\" OR \"$clean\"")}",
                Icons.Default.Visibility
            ),
            DorkLink(
                "Bing Number Search",
                "Microsoft index fallback with quoted digits",
                "https://www.bing.com/search?q=${urlEncode("\"$clean\"")}",
                Icons.Default.Search
            ),
            DorkLink(
                "DuckDuckGo Social Pivot",
                "Social-site constrained number search",
                "https://html.duckduckgo.com/html/?q=${urlEncode("\"$clean\" site:facebook.com OR site:instagram.com OR site:linkedin.com OR site:tiktok.com")}",
                Icons.Default.Group
            ),
            DorkLink(
                "ShouldIAnswer Score",
                "Community spam score and complaints",
                "https://www.shouldianswer.net/phone/$clean",
                Icons.Default.Report
            ),
            DorkLink(
                "WhoCallsMe Reports",
                "Caller reports and complaint threads",
                "https://whocallsme.com/Phone-Number-$clean",
                Icons.Default.Forum
            ),
            DorkLink(
                "SpamCalls.net Lookup",
                "Spam-call complaints for this number",
                "https://spamcalls.net/en/phone/$clean",
                Icons.Default.Call
            ),
            DorkLink(
                "Tellows Score",
                "Tellows spam score (1=safe, 9=scam)",
                "https://www.tellows.com/num/$clean",
                Icons.Default.Speed
            ),
            DorkLink(
                "Sync.ME Lookup",
                "Crowdsourced name + social pivot",
                "https://sync.me/search/?number=${urlEncode(e164)}",
                Icons.Default.Sync
            ),
            DorkLink(
                "Pastebin / Paste Dork",
                "Find number in pastes and dumps",
                "https://www.google.com/search?q=${urlEncode("\"$clean\" site:pastebin.com OR site:paste.ee OR site:ghostbin.com OR site:github.com")}",
                Icons.Default.ContentPaste
            ),
            DorkLink(
                "BreachDirectory Dork",
                "Breach / leak mentions of this number",
                "https://www.google.com/search?q=${urlEncode("\"$clean\" site:breachdirectory.org OR site:intelx.io OR site:dehashed.com")}",
                Icons.Default.Warning
            ),
            DorkLink(
                "Telegram Deep Link",
                "Open chat if number is on Telegram",
                "https://t.me/+$clean",
                Icons.Default.Send
            ),
            DorkLink(
                "Viber / SMS Dork",
                "Check disposable-SMS inboxes for this number",
                "https://www.google.com/search?q=${urlEncode("\"$clean\" site:receive-smss.com OR site:quackr.io OR site:receive-sms-free.cc")}",
                Icons.Default.Sms
            )
        ))

        return links
    }

    fun generateEmailDorkLinks(email: String): List<DorkLink> {
        val e = email.trim()
        return listOf(
            DorkLink(
                "XposedOrNot Breach Check",
                "Free breach check, no key required",
                "https://xposedornot.com/?${urlEncode(e)}",
                Icons.Default.Security
            ),
            DorkLink(
                "Grep.app Email Search",
                "Find email in public GitHub code",
                "https://grep.app/search?q=${urlEncode("\"$e\"")}",
                Icons.Default.Code
            ),
            DorkLink(
                "GitHub User Pivot",
                "Username derived from email prefix",
                "https://github.com/${urlEncode(e.substringBefore("@"))}",
                Icons.Default.Person
            ),
            DorkLink(
                "Gravatar Lookup",
                "Photo / profile if Gravatar exists",
                "https://gravatar.com/${urlEncode(e.lowercase().trim())}",
                Icons.Default.Face
            ),
            DorkLink(
                "Google Email Dork",
                "Public mentions of this email",
                "https://www.google.com/search?q=${urlEncode("\"$e\"")}",
                Icons.Default.Search
            )
        )
    }

    fun generateUsernameDorkLinks(username: String): List<DorkLink> {
        val u = username.trim()
        return listOf(
            DorkLink(
                "Grep.app Username Search",
                "Find username in public code",
                "https://grep.app/search?q=${urlEncode(u)}",
                Icons.Default.Code
            ),
            DorkLink(
                "GitHub Profile",
                "Direct GitHub profile check",
                "https://github.com/${urlEncode(u)}",
                Icons.Default.Code
            ),
            DorkLink(
                "WhatsMyName DB",
                "Username DB covering ~600 sites",
                "https://raw.githubusercontent.com/WebBreacher/WhatsMyName/main/wmn-data.json",
                Icons.Default.List
            ),
            DorkLink(
                "Google Username Dork",
                "Public mentions of this handle",
                "https://www.google.com/search?q=${urlEncode("\"$u\" site:github.com OR site:reddit.com OR site:instagram.com OR site:tiktok.com")}",
                Icons.Default.Search
            )
        )
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
