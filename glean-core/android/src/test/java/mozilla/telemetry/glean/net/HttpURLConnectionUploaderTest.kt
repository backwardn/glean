/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.telemetry.glean.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import mozilla.telemetry.glean.config.Configuration
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.io.OutputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URI

@RunWith(AndroidJUnit4::class)
class HttpURLConnectionUploaderTest {
    private val testPath: String = "/some/random/path/not/important"
    private val testPing: String = "{ 'ping': 'test' }"
    private val testDefaultConfig = Configuration().copy(
        userAgent = "Glean/Test 25.0.2"
    )

    @Test
    fun `connection timeouts must be properly set`() {
        val connection = mock<HttpURLConnection>(HttpURLConnection::class.java)

        val client = spy<HttpURLConnectionUploader>(HttpURLConnectionUploader())

        doReturn(connection).`when`(client).openConnection(anyString())
        doReturn(200).`when`(client).doUpload(connection, testPing)

        client.upload(testPath, testPing, emptyList())

        verify<HttpURLConnection>(connection).readTimeout = HttpURLConnectionUploader.DEFAULT_READ_TIMEOUT
        verify<HttpURLConnection>(connection).connectTimeout = HttpURLConnectionUploader.DEFAULT_CONNECTION_TIMEOUT
        verify<HttpURLConnection>(connection, times(1)).disconnect()
    }

    @Test
    fun `upload() returns the status code for successful requests`() {
        val connection = mock(HttpURLConnection::class.java)

        doReturn(200).`when`(connection).responseCode
        doReturn(mock(OutputStream::class.java)).`when`(connection).outputStream

        val client = spy<HttpURLConnectionUploader>(HttpURLConnectionUploader())
        doReturn(connection).`when`(client).openConnection(anyString())

        assertEquals(client.upload(testPath, testPing, emptyList())!!, 200)
        verify<HttpURLConnection>(connection, times(1)).disconnect()
    }

    @Test
    fun `upload() correctly uploads the ping data`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("OK"))

        val testConfig = testDefaultConfig.copy(
            userAgent = "Telemetry/42.23",
            serverEndpoint = "http://" + server.hostName + ":" + server.port
        )

        val client = HttpURLConnectionUploader()
        val url = testConfig.serverEndpoint + testPath
        assertNotNull(client.upload(url, testPing, emptyList()))

        val request = server.takeRequest()
        assertEquals(testPath, request.path)
        assertEquals("POST", request.method)
        assertEquals(testPing, request.body.readUtf8())

        server.shutdown()
    }

    @Test
    fun `removeCookies() must not throw for malformed URLs`() {
        val client = HttpURLConnectionUploader()
        CookieHandler.setDefault(CookieManager())
        client.removeCookies("lolprotocol://definitely-not-valid,")
    }

    @Test
    fun `upload() must not transmit any cookie`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("OK"))

        val testConfig = testDefaultConfig.copy(
            userAgent = "Telemetry/42.23",
            serverEndpoint = "http://localhost:" + server.port
        )

        // Set the default cookie manager/handler to be used for the http upload.
        val cookieManager = CookieManager()
        CookieHandler.setDefault(cookieManager)

        // Store a sample cookie.
        val cookie = HttpCookie("cookie-time", "yes")
        cookie.domain = testConfig.serverEndpoint
        cookie.path = testPath
        cookie.version = 0
        cookieManager.cookieStore.add(URI(testConfig.serverEndpoint), cookie)

        // Store a cookie for a subdomain of the same domain's as the server endpoint,
        // to make sure we don't accidentally remove it.
        val cookie2 = HttpCookie("cookie-time2", "yes")
        cookie2.domain = "sub.localhost"
        cookie2.path = testPath
        cookie2.version = 0
        cookieManager.cookieStore.add(URI("http://sub.localhost:${server.port}/test"), cookie2)

        // Add another cookie for the same domain. This one should be removed as well.
        val cookie3 = HttpCookie("cookie-time3", "yes")
        cookie3.domain = "localhost"
        cookie3.path = testPath
        cookie3.version = 0
        cookieManager.cookieStore.add(URI("http://localhost:${server.port}/test"), cookie3)

        // Trigger the connection.
        val url = testConfig.serverEndpoint + testPath
        val client = HttpURLConnectionUploader()
        assertNotNull(client.upload(url, testPing, emptyList()))

        val request = server.takeRequest()
        assertEquals(testPath, request.path)
        assertEquals("POST", request.method)
        assertEquals(testPing, request.body.readUtf8())
        assertTrue(request.headers.values("Cookie").isEmpty())

        // Check that we still have a cookie.
        assertEquals(1, cookieManager.cookieStore.cookies.size)
        assertEquals("cookie-time2", cookieManager.cookieStore.cookies[0].name)

        server.shutdown()
    }

    @Test
    fun `upload() returns null on malformed URLs`() {
        val client = spy<HttpURLConnectionUploader>(HttpURLConnectionUploader())
        doThrow(MalformedURLException()).`when`(client).openConnection(anyString())
        assertNull(client.upload("path", "ping", emptyList()))
    }
}
