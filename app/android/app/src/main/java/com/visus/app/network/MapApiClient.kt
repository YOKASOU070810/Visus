package com.visus.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

// HTTP client for Amap (高德地图) navigation services.
// Calls the Visus server's /api/maps/ endpoints.
object MapApiClient {
    private var baseUrl: String = "http://10.0.2.2:8081"

    fun setServer(url: String) { baseUrl = url.trimEnd('/') }

    data class GeoResult(val name: String, val address: String, val location: String, val city: String?, val district: String?)
    data class RouteStep(val instruction: String, val road: String, val distanceMeters: Int, val durationSeconds: Int)
    data class RouteResult(val distanceMeters: Int, val durationMinutes: Double, val steps: List<RouteStep>, val stepCount: Int)
    data class NavigateResult(
        val destination: String, val destinationLocation: String, val originLocation: String,
        val distanceMeters: Int, val durationMinutes: Double, val stepCount: Int,
        val steps: List<RouteStep>, val voiceSummary: String
    )
    data class SearchResult(val name: String, val address: String, val location: String, val distance: String, val type: String)
    data class ReverseResult(val address: String, val city: String, val nearby: List<GeoResult>)

    private suspend fun post(path: String, body: JSONObject, token: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val url = URL("$baseUrl$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
        val text = if (conn.responseCode in 200..299)
            BufferedReader(InputStreamReader(conn.inputStream)).readText()
        else
            BufferedReader(InputStreamReader(conn.errorStream)).readText()
        conn.disconnect()
        JSONObject(text)
    }

    suspend fun geocode(address: String, city: String = "", token: String? = null): List<GeoResult> {
        val resp = post("/api/maps/geocode", JSONObject().apply {
            put("address", address)
            if (city.isNotBlank()) put("city", city)
        }, token)
        val arr = resp.optJSONObject("data")?.optJSONArray("results") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            GeoResult(o.optString("name"), o.optString("address"), o.optString("location"),
                o.optString("city", null), o.optString("district", null))
        }
    }

    suspend fun reverseGeocode(lat: Double, lng: Double, token: String? = null): ReverseResult {
        val resp = post("/api/maps/reverse", JSONObject().apply {
            put("latitude", lat); put("longitude", lng)
        }, token)
        val data = resp.optJSONObject("data") ?: return ReverseResult("", "", emptyList())
        val nearbyArr = data.optJSONArray("nearby") ?: JSONArray()
        val nearby = (0 until nearbyArr.length()).map {
            val o = nearbyArr.getJSONObject(it)
            GeoResult(o.optString("name"), o.optString("address"), "",
                city = null, district = null)
        }
        return ReverseResult(data.optString("address"), data.optString("city"), nearby)
    }

    suspend fun route(origin: String, dest: String, token: String? = null): RouteResult {
        val resp = post("/api/maps/route", JSONObject().apply {
            put("origin", origin); put("destination", dest)
        }, token)
        val data = resp.optJSONObject("data") ?: return RouteResult(0, 0.0, emptyList(), 0)
        val stepsArr = data.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until stepsArr.length()).map {
            val s = stepsArr.getJSONObject(it)
            RouteStep(s.optString("instruction"), s.optString("road"),
                s.optInt("distance_meters"), s.optInt("duration_seconds"))
        }
        return RouteResult(data.optInt("distance_meters"), data.optDouble("duration_minutes"), steps, data.optInt("step_count"))
    }

    suspend fun navigate(dest: String, lat: Double, lng: Double, city: String = "", token: String? = null): NavigateResult? {
        val resp = post("/api/maps/navigate", JSONObject().apply {
            put("destination", dest)
            put("current_lat", lat)
            put("current_lng", lng)
            if (city.isNotBlank()) put("city", city)
        }, token)
        if (!resp.optBoolean("success")) return null
        val data = resp.optJSONObject("data") ?: return null
        val stepsArr = data.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until stepsArr.length()).map {
            val s = stepsArr.getJSONObject(it)
            RouteStep(s.optString("instruction"), s.optString("road"),
                s.optInt("distance_meters"), s.optInt("duration_seconds"))
        }
        return NavigateResult(
            data.optString("destination"), data.optString("destination_location"),
            data.optString("origin_location"), data.optInt("distance_meters"),
            data.optDouble("duration_minutes"), data.optInt("step_count"),
            steps, data.optString("voice_summary")
        )
    }

    suspend fun search(keywords: String, lat: Double = 0.0, lng: Double = 0.0, city: String = "", token: String? = null): List<SearchResult> {
        val resp = post("/api/maps/search", JSONObject().apply {
            put("keywords", keywords)
            put("latitude", lat); put("longitude", lng)
            if (city.isNotBlank()) put("city", city)
        }, token)
        val arr = resp.optJSONObject("data")?.optJSONArray("results") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SearchResult(o.optString("name"), o.optString("address"), o.optString("location"),
                o.optString("distance"), o.optString("type"))
        }
    }
}
