package com.example.media

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersianSummaryVerificationTest {

    @Test
    fun `persian extract validator rejects disambiguation, stubs, wrong year, and non-media articles`() {
        assertFalse(
            ZeroKeyMetadataEnricher.isValidPersianMediaExtract(
                extract = "میان‌ستاره‌ای ممکن است به یکی از موارد زیر اشاره داشته باشد: میان‌ستاره‌ای (فیلم)، فضای میان‌ستاره‌ای.",
                releaseYear = 2014,
                requireMediaKeywords = false,
            )
        )
        assertFalse(
            ZeroKeyMetadataEnricher.isValidPersianMediaExtract(
                extract = "صفحه ابهام‌زدایی ویکی‌پدیا برای عنوان فیلم.",
                releaseYear = 2020,
                requireMediaKeywords = false,
            )
        )
        assertFalse(
            ZeroKeyMetadataEnricher.isValidPersianMediaExtract(
                extract = "کوتاه",
                releaseYear = null,
                requireMediaKeywords = false,
            )
        )
        assertFalse(
            ZeroKeyMetadataEnricher.isValidPersianMediaExtract(
                extract = "Interstellar is a 2014 science fiction film directed by Christopher Nolan.",
                releaseYear = 2014,
                requireMediaKeywords = false,
            )
        )
        // Wrong release year in lead text for a 2021 film (describing an unrelated 1984 work)
        assertFalse(
            ZeroKeyMetadataEnricher.isValidPersianMediaExtract(
                extract = "تلماسه (فیلم ۱۹۸۴) یک فیلم علمی تخیلی به کارگردانی دیوید لینچ است که در سال 1984 اکران شد.",
                releaseYear = 2021,
                requireMediaKeywords = true,
            )
        )
        // Completely unrelated physics / geography article lacking cinema/TV keywords
        assertFalse(
            ZeroKeyMetadataEnricher.isValidPersianMediaExtract(
                extract = "محیط میان‌ستاره‌ای به ماده و تابش موجود در فضای بین سامانه‌های ستاره‌ای در یک کهکشان گفته می‌شود.",
                releaseYear = null,
                requireMediaKeywords = true,
            )
        )
        // Genuine matching Persian film synopsis
        assertTrue(
            ZeroKeyMetadataEnricher.isValidPersianMediaExtract(
                extract = "میان‌ستاره‌ای یک فیلم علمی-تخیلی به کارگردانی کریستوفر نولان در سال 2014 است که داستان گروهی از فضانوردان را روایت می‌کند.",
                releaseYear = 2014,
                requireMediaKeywords = true,
            )
        )
    }

    @Test
    fun `wikidata entity parser rejects disambiguation, non-media entities, wrong type, and wrong year`() {
        val enricher = ZeroKeyMetadataEnricher(OkHttpClient())

        val disambigJson = """
            {
              "entities": {
                "Q999": {
                  "labels": {"fa": {"value": "ماتریکس"}},
                  "descriptions": {"en": {"value": "Wikimedia disambiguation page"}},
                  "sitelinks": {"fawiki": {"title": "ماتریکس (ابهام‌زدایی)"}},
                  "claims": {
                    "P31": [{"mainsnak": {"datavalue": {"value": {"id": "Q4167410"}}}}]
                  }
                }
              }
            }
        """.trimIndent()
        assertNull(enricher.parseVerifiedWikidataEntityJson(disambigJson, "Q999", null, MediaType.MOVIE, 1999, resolveClaimLabels = false))

        val cityJson = """
            {
              "entities": {
                "Q888": {
                  "labels": {"fa": {"value": "تهران"}},
                  "descriptions": {"en": {"value": "capital city of Iran"}},
                  "sitelinks": {"fawiki": {"title": "تهران"}},
                  "claims": {
                    "P31": [{"mainsnak": {"datavalue": {"value": {"id": "Q515"}}}}]
                  }
                }
              }
            }
        """.trimIndent()
        assertNull(enricher.parseVerifiedWikidataEntityJson(cityJson, "Q888", null, MediaType.TV, 2020, resolveClaimLabels = false))

        val tvEntityForMovieSearch = """
            {
              "entities": {
                "Q777": {
                  "labels": {"fa": {"value": "فارگو"}},
                  "descriptions": {"en": {"value": "American television series"}},
                  "sitelinks": {"fawiki": {"title": "فارگو (مجموعه تلویزیونی)"}},
                  "claims": {
                    "P31": [{"mainsnak": {"datavalue": {"value": {"id": "Q5398426"}}}}],
                    "P580": [{"mainsnak": {"datavalue": {"value": {"time": "+2014-04-15T00:00:00Z"}}}}]
                  }
                }
              }
            }
        """.trimIndent()
        assertNull(
            enricher.parseVerifiedWikidataEntityJson(tvEntityForMovieSearch, "Q777", null, MediaType.MOVIE, 1996, resolveClaimLabels = false)
        )
        val validTv = enricher.parseVerifiedWikidataEntityJson(tvEntityForMovieSearch, "Q777", null, MediaType.TV, 2014, resolveClaimLabels = false)
        assertNotNull(validTv)
        assertEquals("فارگو (مجموعه تلویزیونی)", validTv?.faWikiTitle)

        // Mismatched IMDb ID should always reject
        val wrongImdbJson = """
            {
              "entities": {
                "Q111": {
                  "labels": {"fa": {"value": "فیلم آزمایشی"}},
                  "descriptions": {"en": {"value": "2010 film"}},
                  "sitelinks": {"fawiki": {"title": "آزمایشی (فیلم ۲۰۱۰)"}},
                  "claims": {
                    "P31": [{"mainsnak": {"datavalue": {"value": {"id": "Q11424"}}}}],
                    "P345": [{"mainsnak": {"datavalue": {"value": "tt0000001"}}}]
                  }
                }
              }
            }
        """.trimIndent()
        assertNull(
            enricher.parseVerifiedWikidataEntityJson(wrongImdbJson, "Q111", expectedImdbId = "tt1375666", MediaType.MOVIE, 2010, resolveClaimLabels = false)
        )
    }

    @Test
    fun `imdb locked wikidata lookup never falls back to blind fa wikipedia search when fawiki sitelink is missing`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = mockHttpClient { url ->
            requestedUrls.add(url)
            when {
                url.contains("wikidata.org") && url.contains("haswbstatement:P345=tt1375666") -> """
                    {"query": {"search": [{"title": "Q25188"}]}}
                """.trimIndent()

                url.contains("wikidata.org") && url.contains("ids=Q25188") && url.contains("sitelinks") -> """
                    {
                      "entities": {
                        "Q25188": {
                          "labels": {"fa": {"value": "تلقین"}},
                          "descriptions": {"en": {"value": "2010 film by Christopher Nolan"}},
                          "sitelinks": {"enwiki": {"title": "Inception"}},
                          "claims": {
                            "P31": [{"mainsnak": {"datavalue": {"value": {"id": "Q11424"}}}}],
                            "P345": [{"mainsnak": {"datavalue": {"value": "tt1375666"}}}],
                            "P577": [{"mainsnak": {"datavalue": {"value": {"time": "+2010-07-16T00:00:00Z"}}}}]
                          }
                        }
                      }
                    }
                """.trimIndent()

                url.contains("en.wikipedia.org") -> """
                    {
                      "query": {
                        "pages": {
                          "1234": {
                            "title": "Inception",
                            "extract": "Inception is a 2010 science fiction action film written and directed by Christopher Nolan.",
                            "pageprops": {"wikibase_item": "Q25188"}
                          }
                        }
                      }
                    }
                """.trimIndent()

                else -> "{}"
            }
        }

        val enricher = ZeroKeyMetadataEnricher(client)
        val result = enricher.fetchWikidataAndWikipediaEnrichment(
            title = "Inception",
            imdbId = "tt1375666",
            mediaType = MediaType.MOVIE,
            releaseYear = 2010,
        )

        assertNotNull(result)
        assertEquals("Q25188", result?.wikidataId)
        assertNull("Should not fabricate/search unrelated Persian Wikipedia extract when fawiki sitelink is missing", result?.persianOverview)
        assertFalse(
            "Must never query fa.wikipedia.org text search when wikidata sitelink is absent",
            requestedUrls.any { it.contains("fa.wikipedia.org") && it.contains("list=search") },
        )
    }

    @Test
    fun `imdb locked wikidata lookup returns exact fawiki summary from validated entity sitelink`() = runTest {
        val client = mockHttpClient { url ->
            when {
                url.contains("wikidata.org") && url.contains("haswbstatement:P345=tt0816692") -> """
                    {"query": {"search": [{"title": "Q13417189"}]}}
                """.trimIndent()

                url.contains("wikidata.org") && url.contains("ids=Q13417189") && url.contains("sitelinks") -> """
                    {
                      "entities": {
                        "Q13417189": {
                          "labels": {"fa": {"value": "میان‌ستاره‌ای"}},
                          "descriptions": {"en": {"value": "2014 film by Christopher Nolan"}},
                          "sitelinks": {
                            "enwiki": {"title": "Interstellar (film)"},
                            "fawiki": {"title": "میان‌ستاره‌ای (فیلم)"}
                          },
                          "claims": {
                            "P31": [{"mainsnak": {"datavalue": {"value": {"id": "Q11424"}}}}],
                            "P345": [{"mainsnak": {"datavalue": {"value": "tt0816692"}}}],
                            "P577": [{"mainsnak": {"datavalue": {"value": {"time": "+2014-11-07T00:00:00Z"}}}}]
                          }
                        }
                      }
                    }
                """.trimIndent()

                url.contains("fa.wikipedia.org") && url.contains("titles=") -> """
                    {
                      "query": {
                        "pages": {
                          "555": {
                            "title": "میان‌ستاره‌ای (فیلم)",
                            "extract": "میان‌ستاره‌ای یک فیلم علمی-تخیلی حماسی به کارگردانی کریستوفر نولان محصول سال 2014 است.[۱]",
                            "pageprops": {"wikibase_item": "Q13417189"}
                          }
                        }
                      }
                    }
                """.trimIndent()

                url.contains("en.wikipedia.org") -> """
                    {
                      "query": {
                        "pages": {
                          "666": {
                            "title": "Interstellar (film)",
                            "extract": "Interstellar is a 2014 epic science fiction film directed by Christopher Nolan.",
                            "pageprops": {"wikibase_item": "Q13417189"}
                          }
                        }
                      }
                    }
                """.trimIndent()

                else -> "{}"
            }
        }

        val enricher = ZeroKeyMetadataEnricher(client)
        val result = enricher.fetchWikidataAndWikipediaEnrichment(
            title = "Interstellar",
            imdbId = "tt0816692",
            mediaType = MediaType.MOVIE,
            releaseYear = 2014,
        )

        assertNotNull(result)
        assertEquals("میان‌ستاره‌ای", result?.persianTitle)
        val faOverview = result?.persianOverview
        assertNotNull(faOverview)
        assertTrue(faOverview!!.contains("کریستوفر نولان"))
        assertFalse("Footnotes like [۱] must be stripped cleanly", faOverview.contains("[۱]"))
    }

    private fun mockHttpClient(responder: (String) -> String): OkHttpClient {
        val interceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            val bodyJson = responder(url)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(bodyJson.toResponseBody("application/json; charset=utf-8".toMediaType()))
                .build()
        }
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }
}
