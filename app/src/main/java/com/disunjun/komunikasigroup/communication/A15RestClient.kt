package com.disunjun.komunikasigroup.communication

import com.disunjun.komunikasigroup.domain.AuthUser
import com.disunjun.komunikasigroup.domain.CommunicationError
import com.disunjun.komunikasigroup.domain.TurnCredentialParser
import com.disunjun.komunikasigroup.domain.TurnCredentials
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/** Authenticated A1.5 session: opaque bearer token plus the public user profile. */
data class A15Session(
    val token: String,
    val user: AuthUser
)

/**
 * A1.5 REST client. Implements the auth contract only:
 *   POST /api/auth/login
 *   GET  /api/auth/me
 *   POST /api/auth/logout
 *
 * Bearer token is only carried inside request headers. Tokens are never logged.
 */
class A15RestClient(
    private val baseUrl: String = A15Config.baseUrl
) {
    /** POST /api/auth/login -> {token, user, expiresAt}. */
    fun login(nama: String, sandi: String): Result<A15Session> {
        val body = JSONObject().put("nama", nama).put("sandi", sandi).toString()
        return request("/api/auth/login", "POST", body = body).map { json ->
            val token = json.optString("token").ifBlank {
                throw CommunicationError.Authentication("Login berhasil tetapi token tidak diterima server.")
            }
            A15Session(token, parseUser(json.optJSONObject("user")))
        }
    }

    /** GET /api/auth/me -> {user, expiresAt}. */
    fun me(token: String): Result<AuthUser> {
        return request("/api/auth/me", "GET", token = token).map { json ->
            val user = json.optJSONObject("user")
                ?: throw CommunicationError.Authentication("Sesi tidak valid.")
            parseUser(user)
        }
    }

    /** POST /api/auth/logout -> revokes the session server-side. */
    fun logout(token: String): Result<Unit> {
        return request("/api/auth/logout", "POST", token = token).map { Unit }
    }

    /**
     * GET /api/turn-credentials -> short-lived Cloudflare ICE servers.
     * Attaches the bearer token when present. Returns credentials in memory
     * only; callers must not persist or log them.
     */
    fun turnCredentials(token: String?): Result<TurnCredentials> {
        return request("/api/turn-credentials", "GET", token = token).mapCatching { json ->
            TurnCredentialParser.parse(json.toFlatMap())
                .getOrElse { throw it }
        }
    }

    private fun parseUser(user: JSONObject?): AuthUser {
        if (user == null) {
            throw CommunicationError.Authentication("Respons tidak memiliki data user.")
        }
        return AuthUser(
            id = if (user.has("id") && !user.isNull("id")) user.optLong("id") else null,
            nama = user.optString("nama", user.optString("username", "")),
            role = user.optString("role", "user"),
            status = user.optString("status", "aktif"),
            banned = user.optBoolean("banned", false),
            muted = user.optBoolean("muted", false)
        )
    }

    private fun request(path: String, method: String, body: String? = null, token: String? = null): Result<JSONObject> {
        return try {
            val connection = URI.create(baseUrl + path).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json")
                if (!token.isNullOrBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                }
                if (method == "POST") {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.orEmpty().toByteArray(Charsets.UTF_8)) }
                }

                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    JSONObject()
                }

                classifyResponse(status, json)
                    ?.let { throw it }

                Result.success(json)
            } finally {
                connection.disconnect()
            }
        } catch (e: CommunicationError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(CommunicationError.Network("Tidak dapat terhubung ke backend."))
        }
    }

    /** Pure HTTP-status + ok/message -> domain error mapping. Unit-tested without a network. */
    internal fun classifyResponse(status: Int, ok: Boolean, message: String?): CommunicationError? =
        when {
            status == 401 -> CommunicationError.Authentication(
                message?.ifBlank { null } ?: "Sesi tidak valid atau kedaluwarsa."
            )
            status == 403 -> CommunicationError.Authentication(
                message?.ifBlank { null } ?: "Akun tidak diizinkan masuk."
            )
            status !in 200..299 || !ok -> CommunicationError.Unknown(
                message?.ifBlank { null } ?: "Backend HTTP $status"
            )
            else -> null
        }

    private fun classifyResponse(status: Int, json: JSONObject): CommunicationError? =
        classifyResponse(status, json.optBoolean("ok", true), json.optString("message").ifBlank { null })
}