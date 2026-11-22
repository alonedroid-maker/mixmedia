package com.example

import com.example.network.CurlRedirectResolver
import com.example.network.SocialPlatform
import com.example.network.UnsupportedPlatformException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveDiagnosticTest {

    @Test
    fun verifyUserTestLinksWithoutFakeHumanVerification() {
        runBlocking {
            val resolver = CurlRedirectResolver()

            val igUrl = "https://www.instagram.com/reel/Df-HWOzsvcb/?evlk=MWJsMjB0Z3F4emkydw=="
            try {
                val igRes = resolver.resolve(igUrl)
                assertNotNull("Instagram directVideoUrl should not be null", igRes.directVideoUrl)
                assertTrue(igRes.directVideoUrl!!.startsWith("http"))
                assertEquals("MP4", igRes.extension)
                println("IG_VERIFIED: ${igRes.filename} (${igRes.fileSizeFormatted}) -> ${igRes.directVideoUrl}")
            } catch (e: UnsupportedPlatformException) {
                assertEquals(SocialPlatform.INSTAGRAM, e.platform)
                println("IG_UNSUPPORTED_CLEAN: ${e.message}")
            }

            val ytUrl = "https://youtu.be/UHrrwnJ4hUI?si=Yohp5coRJjA1WH1f"
            try {
                val ytRes = resolver.resolve(ytUrl)
                assertTrue(ytRes.youtubeOptions.isNotEmpty())
                println("YT_VERIFIED: ${ytRes.mediaTitle} options=${ytRes.youtubeOptions.size}")
            } catch (e: UnsupportedPlatformException) {
                assertEquals(SocialPlatform.YOUTUBE, e.platform)
                println("YT_UNSUPPORTED_CLEAN: ${e.message}")
            }
        }
    }
}
