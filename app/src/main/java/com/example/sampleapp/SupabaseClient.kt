package com.example.sampleapp

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Supabase REST/Auth client. Retries transient failures, not rejected credentials. */
class SupabaseClient(private val projectUrl: String, private val publishableKey: String) {
    data class Session(val accessToken: String, val refreshToken: String, val userId: String, val email: String)
    data class Response(val status: Int, val body: String) {
        fun json(): JSONObject = if (body.isBlank()) JSONObject() else JSONObject(body)
    }
    class ApiException(val status: Int, message: String) : Exception(message)
    private val baseUrl = projectUrl.trimEnd('/')

    fun isConfigured() = baseUrl.startsWith("https://") && publishableKey.isNotBlank()

    fun signUp(email: String, password: String, fullName: String) = request(
        "POST", "/auth/v1/signup",
        JSONObject().put("email", email).put("password", password)
            .put("data", JSONObject().put("full_name", fullName)).toString()
    )

    fun signIn(email: String, password: String): Session {
        val response = request("POST", "/auth/v1/token?grant_type=password",
            JSONObject().put("email", email).put("password", password).toString())
        val obj = response.json()
        val user = obj.optJSONObject("user")
        val access = obj.optString("access_token")
        if (access.isBlank() || user == null) {
            throw ApiException(response.status, "Sign-in did not return a session. Check email confirmation and try again.")
        }
        return Session(access, obj.optString("refresh_token"), user.optString("id"), user.optString("email", email))
    }

    fun refresh(refreshToken: String): Session {
        val response = request("POST", "/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", refreshToken).toString())
        val obj = response.json()
        val user = obj.optJSONObject("user")
        val access = obj.optString("access_token")
        if (access.isBlank() || user == null) throw ApiException(response.status, "Session expired. Please sign in again.")
        return Session(access, obj.optString("refresh_token", refreshToken), user.optString("id"), user.optString("email"))
    }

    fun signOut(accessToken: String) { request("POST", "/auth/v1/logout", null, accessToken) }

    fun fetchProfile(accessToken: String, userId: String): JSONObject {
        val response = request("GET",
            "/rest/v1/profiles?select=id,email,full_name,role,account_status&id=eq.$userId", null, accessToken)
        val rows = JSONArray(response.body)
        if (rows.length() == 0) throw ApiException(404, "Your cloud profile is missing. Check that the database schema was installed.")
        return rows.getJSONObject(0)
    }

    fun fetchWallet(accessToken: String, userId: String): JSONObject {
        val response = request("GET",
            "/rest/v1/wallet_accounts?select=deposited_amount,capital_amount,profit_amount,withdrawals_amount&user_id=eq.$userId",
            null, accessToken)
        val rows = JSONArray(response.body)
        return if (rows.length() > 0) rows.getJSONObject(0) else JSONObject()
    }

    private fun request(method: String, path: String, body: String? = null, accessToken: String? = null): Response {
        if (!isConfigured()) throw ApiException(0, "Cloud connection is not configured. Check GitHub Actions secrets.")
        var lastError: Exception? = null
        for (attempt in 0..2) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 10000
                    readTimeout = 12000
                    setRequestProperty("apikey", publishableKey)
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "MY-INVEST-Android")
                    setRequestProperty("Authorization", "Bearer " + (accessToken ?: publishableKey))
                    if (body != null) doOutput = true
                }
                if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (status in 200..299) return Response(status, text)
                if ((status == 408 || status == 429 || status >= 500) && attempt < 2) {
                    Thread.sleep(500L * (1L shl attempt))
                    continue
                }
                val message = try {
                    val error = JSONObject(text)
                    error.optString("msg").ifBlank { error.optString("message").ifBlank { "Cloud request failed (HTTP $status)." } }
                } catch (_: Exception) { "Cloud request failed (HTTP $status)." }
                throw ApiException(status, message)
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) {
                    try { Thread.sleep(500L * (1L shl attempt)) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Connection retry interrupted.")
                    }
                }
            } finally { connection?.disconnect() }
        }
        throw IOException("Unable to reach MY INVEST cloud service after 3 attempts. Check your internet connection and try again.", lastError)
    }
}
