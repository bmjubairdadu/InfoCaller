package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets


class NidIdentityLookupProviderImpl : LookupProvider {
    override val id: String = "nid_identity_pivot"
    override val name: String = "NID Intelligence"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.PUBLIC_SEARCH, Capability.ALTERNATE_NAME)
    override val priority: Int = 30
    override val costClass: CostClass = CostClass.LOW

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.NID) return@withContext null
        
        try {
            val query = "site:github.com OR site:gov.bd OR site:drive.google.com OR site:pastebin.com OR site:trello.com \"$identifier\""
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val url = "https://www.google.com/search?q=$encoded"
            
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.5")
                .timeout(12000)
                .get()

            val snippets = doc.select("div.VwiC3b, span.st, div.MUY17B").text() 
            
            val extractedName = extractNameFromSnippet(snippets, identifier)
            val extractedPhoto = extractPhotoCandidate(doc, identifier)

            if (extractedName != null || extractedPhoto != null) {
                return@withContext PartialResult(
                    name = extractedName,
                    imageUrl = extractedPhoto,
                    confidence = 0.5f,
                    source = "Deep Identity Recon",
                    providerId = id,
                    providerVersion = version
                )
            }
        } catch (_: Exception) {
            null
        }
        
        return@withContext null
    }

    private fun extractNameFromSnippet(snippet: String, nid: String): String? {
        val words = snippet.split(" ", ",", ":", "-").map { it.trim() }
        val nidIndex = words.indexOf(nid)
        if (nidIndex != -1) {
            val nameParts = mutableListOf<String>()
            for (i in (nidIndex - 3) until nidIndex) {
                if (i >= 0 && words[i].getOrNull(0)?.isUpperCase() == true) {
                    nameParts.add(words[i])
                }
            }
            if (nameParts.isNotEmpty()) return nameParts.joinToString(" ")
        }
        return null
    }

    private fun extractPhotoCandidate(doc: org.jsoup.nodes.Document, nid: String): String? {
        doc.select("div.g").forEach { result ->
            if (result.text().contains(nid)) {
                val img = result.select("img").attr("src")
                if (img.isNotBlank() && !img.contains("google") && (img.startsWith("http") || img.startsWith("https"))) {
                    return img
                }
            }
        }
        return null
    }
}
