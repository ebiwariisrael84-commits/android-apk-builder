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

    fun fetchPlans(accessToken: String): JSONArray =
        JSONArray(request("GET", "/rest/v1/investment_plans?select=id,slug,name,icon,minimum_amount,fixed_profit_rate,is_active&is_active=eq.true&order=minimum_amount.asc", null, accessToken).body)

    fun requestInvestment(accessToken: String, planSlug: String, amount: Double) {
        request("POST", "/rest/v1/rpc/request_investment",
            JSONObject().put("p_plan_slug", planSlug).put("p_amount", amount).toString(), accessToken)
    }

    fun requestWithdrawal(accessToken: String, amount: Double) {
        request("POST", "/rest/v1/rpc/request_withdrawal",
            JSONObject().put("p_amount", amount).toString(), accessToken)
    }

    fun fetchTransactions(accessToken: String, userId: String): JSONArray =
        JSONArray(request("GET",
            "/rest/v1/transactions?select=id,transaction_type,amount,status,description,created_at&user_id=eq.$userId&order=created_at.desc&limit=100",
            null, accessToken).body)

    fun fetchPendingInvestments(accessToken: String): JSONArray =
        JSONArray(request("GET", "/rest/v1/investments?select=id,user_id,principal_amount,fixed_profit_amount,status,created_at&status=eq.pending&order=created_at.asc&limit=100", null, accessToken).body)

    fun adminReviewInvestment(accessToken: String, investmentId: String, decision: String, reason: String) {
        request("POST", "/rest/v1/rpc/admin_review_investment",
            JSONObject().put("p_investment_id", investmentId).put("p_decision", decision)
                .put("p_reason", reason).toString(), accessToken)
    }

    fun searchUsers(accessToken: String, email: String): JSONArray {
        val filter = if (email.isBlank()) "" else "&email=ilike.*" + java.net.URLEncoder.encode(email.trim(), "UTF-8") + "*"
        return JSONArray(request("GET",
            "/rest/v1/profiles?select=id,email,full_name,role,account_status,created_at&order=created_at.desc&limit=100" + filter,
            null, accessToken).body)
    }

    fun adminAdjustWallet(accessToken: String, userId: String, field: String, amount: Double, reason: String) {
        request("POST", "/rest/v1/rpc/admin_adjust_wallet",
            JSONObject().put("p_user_id", userId).put("p_field", field)
                .put("p_new_amount", amount).put("p_reason", reason).toString(), accessToken)
    }

    fun adminSetProfileStatus(accessToken: String, userId: String, status: String, reason: String) {
        request("POST", "/rest/v1/rpc/admin_set_profile_status",
            JSONObject().put("p_user_id", userId).put("p_status", status).put("p_reason", reason).toString(), accessToken)
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
