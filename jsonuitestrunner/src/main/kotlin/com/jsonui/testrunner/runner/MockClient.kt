package com.jsonui.testrunner.runner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the local mock server's admin API (/__jsonui__/).
 *
 * Switches API mock scenarios during a run: a screen test's root `mocks`
 * (set before the app relaunches) and `setMocks` steps in flows. Uses
 * HttpURLConnection (no extra deps). On the emulator the host machine is
 * reachable at 10.0.2.2, and the test build must allow cleartext traffic to it
 * (usesCleartextTraffic / network-security-config) — see the driver README.
 */
class MockClient(
    private val baseUrl: String,
    private val token: String
) {
    /** Switch a set of endpoints to the given scenarios. Throws on unknown refs. */
    fun scenarioSet(mocks: Map<String, String>) {
        val payload = buildJsonObject {
            put("mocks", JsonObject(mocks.mapValues { JsonPrimitive(it.value) }))
        }
        val response = post("/__jsonui__/scenario-set", payload.toString())
        val unknown = (response?.get("unknown") as? JsonArray)?.map { it.jsonPrimitive.content }
        if (!unknown.isNullOrEmpty()) {
            throw MockClientException("mock scenario-set: unknown operationId(s): ${unknown.joinToString(", ")}")
        }
    }

    /** Reset every endpoint back to its default scenario. */
    fun reset() {
        post("/__jsonui__/reset", null)
    }

    private fun post(path: String, body: String?): JsonObject? {
        val url = URL(baseUrl.trimEnd('/') + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("X-JsonUI-Token", token)
            setRequestProperty("Content-Type", "application/json")
            doInput = true
        }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw MockClientException("mock server returned HTTP $code")
            }
            val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            if (text.isBlank()) return null
            return Json.parseToJsonElement(text) as? JsonObject
        } catch (e: MockClientException) {
            throw e
        } catch (e: Exception) {
            throw MockClientException("mock server request failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }
}

class MockClientException(message: String) : Exception(message)
