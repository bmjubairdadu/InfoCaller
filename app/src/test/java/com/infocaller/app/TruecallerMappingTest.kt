package com.infocaller.app

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.infocaller.app.data.remote.TruecallerParser
import com.infocaller.app.domain.engine.PartialResult
import org.junit.Assert.assertEquals
import org.junit.Test

class TruecallerMappingTest {

    @Test
    fun testMapping() {
        val json = """
        {
          "name": "John Doe",
          "altName": "Johnny",
          "image": "https://example.com/img.jpg",
          "about": "Bio info",
          "addresses": [
            {
              "city": "Dhaka",
              "countryCode": "BD",
              "timeZone": "Asia/Dhaka"
            }
          ],
          "internetAddresses": [
            {
              "id": "john@example.com",
              "service": "email"
            },
            {
              "id": "john_social",
              "service": "facebook",
              "caption": "https://facebook.com/john"
            }
          ],
          "spamInfo": {
            "spamScore": 15,
            "spamType": "Sales"
          }
        }
        """.trimIndent()

        val gson = Gson()
        val data = gson.fromJson(json, JsonObject::class.java)

        val result = TruecallerParser.mapResult(data, "test_id", "1.0.0")

        assertEquals("John Doe", result.name)
        assertEquals("Johnny", result.alternateName)
        assertEquals("https://example.com/img.jpg", result.imageUrl)
        assertEquals("Dhaka", result.city)
        assertEquals("BD", result.country)
        assertEquals("Asia/Dhaka", result.timezone)
        assertEquals("john@example.com", result.email)
        assertEquals(1, result.socialProfiles.size)
        assertEquals("Facebook", result.socialProfiles[0].platform)
        assertEquals("https://facebook.com/john", result.socialProfiles[0].profileUrl)
        assertEquals(15, result.spamScore)
    }
}
