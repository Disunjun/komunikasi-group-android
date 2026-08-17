package com.disunjun.komunikasigroup.communication

import com.disunjun.komunikasigroup.domain.CommunicationError
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * v3 media-signaling authentication via the v3 contract:
 *   POST /api/auth/login {name, password} -> {token, user:{id,name,role,sessionId}}
 *
 * The returned JWT is only carried into the media socket handshake; it is never
 * logged or persisted, and is discarded when the media session ends.
 */
class V3MediaAuthenticator(
    private val baseUrl: String = A15Config.signalingUrl
) {
    fun login(name: String, password: String): Result<V3MediaSession> {
        if (name.isBlank() || password.isBlank()) {
            return Result.failure(CommunicationError.Authentication("Nama dan password wajib diisi."))
        }
        val body = JSONObject()
            .put("name", name)
            .put("password", password)
            .toString()
        return request("/api/auth/login", body).map { json ->
            val token = json.optString("token").ifBlank {
                throw CommunicationError.Authentication("Login media gagal, server tidak mengirim token.")
            }
            val sessionId = json.optJSONObject("user")?.optString("sessionId").orEmpty()
            val userName = json.optJSONObject("user")?.optString("name").orEmpty()
            V3MediaSession(token = token, sessionId = sessionId, name = userName)
        }
    }

    private fun request(path: String, body: String): Result<JSONObject> {
        return try {
            val connection = URI.create(baseUrl + path).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    JSONObject()
                }

                classifyResponse(status, json)?.let { throw it }
                Result.success(json)
            } finally {
                connection.disconnect()
            }
        } catch (e: CommunicationError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(CommunicationError.Network("Tidak dapat terhubung ke backend media."))
        }
    }

    private fun classifyResponse(status: Int, json: JSONObject): CommunicationError? =
        when {
            status == 401 -> CommunicationError.Authentication(
                json.optString("error").ifBlank { "Login media gagal (kredensial salah)." }
            )
            status == 403 -> CommunicationError.Authentication(
                json.optString("error").ifBlank { "Akun media tidak diizinkan." }
            )
            status !in 200..299 -> CommunicationError.Unknown(
                json.optString("error").ifBlank { "Backend media HTTP $status" }
            )
            else -> null
        }
}