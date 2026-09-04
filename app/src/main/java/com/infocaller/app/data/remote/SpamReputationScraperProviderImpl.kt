package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Free community spam-reputation scraper — no key.
 * Fans out (IO) over three clearnet complaint boards that render server-side:
 *  - shouldianswer.net  -> https://www.shouldianswer.net/phone/<digits>
 *  - whocallsme.com     -> https://whocallsme.com/Phone-Number-<digits>
 *  - spamcalls.net      -> https://spamcalls.net/en/phone/<digits>
 * Parses score / report counts from title + body text with lightweight regexes.
 * Never uploads anything; pure GET lookups.
 */
class SpamReputationScraperProviderImpl : LookupProvider {
    override val id = "spam_reputation_scraper"
    override val name = "Spam Reputation Scraper"
    override val version = "1.0.0"
    override val capabilities = setOf(
        Capability.PUBLIC_SEARCH, Capability.PUBLIC_PROFILE, Capability.LINE_TYPE
    )
    override val priority = 52
    override val costClass = CostClass.FREE

    private fun ua() =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? =
        withContext(Dispatchers.IO) {
            if (type != IdentifierType.PHONE) return@withContext null
            val normalized = PhoneNumberUtils.normalize(identifier)
            val digits = normalized.filter { it.isDigit() }
            if (digits.length < 7) return@withContext null
            // shouldianswer/whocallsme index by national significant number; try full + last-10
            val candidates = listOf(digits, digits.takeLast(10)).distinct().filter { it.length >= 7 }

            val hits: List<BoardHit> = try {
                coroutineScope {
                    val a = async { checkShouldIAnswer(candidates) }
                    val b = async { checkWhoCallsMe(candidates) }
                    val c = async { checkSpamCalls(candidates) }
                    awaitAll(a, b, c).filterNotNull()
                }
            } catch (_: Exception) { emptyList() }
            if (hits.isEmpty()) return@withContext null

            val worst = hits.maxByOrNull { it.reports } ?: hits.first()
            val totalReports = hits.sumOf { it.reports }
            val about = buildString {
                append("Spam reputation: ${hits.size} board(s) mention this number")
                if (totalReports > 0) append(" • ~$totalReports complaint(s)")
                if (worst.score != null) append(" • top score: ${worst.score}")
                append(". [")
                append(hits.joinToString(" | ") { "${it.board}${if (it.score != null) " ${it.score}" else ""}" })
                append("]")
            }
            PartialResult(
                about = about.take(700),
                confidence = when {
                    totalReports >= 10 -> 0.75f
                    hits.size >= 2 -> 0.65f
                    else -> 0.55f
                },
                source = "Spam Boards (ShouldIAnswer/WhoCallsMe/SpamCalls)",
                providerId = id, providerVersion = version
            )
        }

    private data class BoardHit(val board: String, val reports: Int, val score: String?)

    private fun fetchText(url: String): String? {
        return try {
            Jsoup.connect(url).userAgent(ua()).timeout(7000)
                .ignoreHttpErrors(true).followRedirects(true).maxBodySize(500_000)
                .get().text().take(6000)
        } catch (_: Exception) { null }
    }

    private fun extractReports(text: String): Int {
        val patterns = listOf(
            Regex("(\\d+)\\s*(complaints?|reports?|reviews?|comments?)", RegexOption.IGNORE_CASE),
            Regex("rated\\s*(\\d+)\\s*times?", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(text)
            if (m != null) return m.groupValues[1].toIntOrNull()?.coerceAtMost(9999) ?: 0
        }
        return 0
    }

    private fun extractScore(text: String): String? {
        // e.g. "Negative 4.2", "Score: 8/10", "Dangerous", "Spam"
        val m = Regex(
            "(negative|positive|neutral|dangerous|harassing|spam|telemarketer|debt collector|score\\s*[:\\-]?\\s*\\d[\\d./]*)",
            RegexOption.IGNORE_CASE
        ).find(text)
        return m?.value?.trim()?.take(40)
    }

    private fun checkShouldIAnswer(candidates: List<String>): BoardHit? {
        for (c in candidates) {
            val t = fetchText("https://www.shouldianswer.net/phone/$c") ?: continue
            if (t.length < 300) continue
            if (t.contains("not found", true) && t.length < 1200) continue
            return BoardHit("ShouldIAnswer", extractReports(t), extractScore(t))
        }
        return null
    }

    private fun checkWhoCallsMe(candidates: List<String>): BoardHit? {
        for (c in candidates) {
            val t = fetchText("https://whocallsme.com/Phone-Number-$c") ?: continue
            if (t.length < 300) continue
            return BoardHit("WhoCallsMe", extractReports(t), extractScore(t))
        }
        return null
    }

    private fun checkSpamCalls(candidates: List<String>): BoardHit? {
        for (c in candidates) {
            val t = fetchText("https://spamcalls.net/en/phone/$c") ?: continue
            if (t.length < 300) continue
            if (t.contains("no complaints", true) && extractReports(t) == 0) continue
            return BoardHit("SpamCalls.net", extractReports(t), extractScore(t))
        }
        return null
    }
}
