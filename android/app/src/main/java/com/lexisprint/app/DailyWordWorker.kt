package com.lexisprint.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class DailyWordWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = LocalDate.now(INDIA_ZONE).toString()

        // Keep the widget quiet: the 15-minute safety poll only contacts the API once per new day.
        if (prefs.getString(KEY_DATE, null) == today && validCachedWords(prefs.getString(KEY_WORDS, null))) {
            return@withContext Result.success()
        }

        try {
            val endpoint = "$API_URL?date=$today"
            var response = request(endpoint, "GET")

            // A missing daily entry means the scheduled Cloudflare generation has not run yet.
            // POST without force asks the Worker to generate only when no cached entry exists.
            if (response.first == HttpURLConnection.HTTP_NOT_FOUND) {
                response = request(API_URL, "POST", JSONObject().put("date", today).toString())
            }

            if (response.first !in 200..299) {
                Log.w(TAG, "Daily API returned HTTP ${response.first}")
                return@withContext if (response.first >= 500 || response.first == 429) Result.retry() else Result.failure()
            }

            val data = JSONObject(response.second)
            val returnedDate = data.optString("date", today)
            val words = data.optJSONArray("words") ?: return@withContext Result.retry()
            if (returnedDate != today || !validWords(words)) {
                Log.w(TAG, "Daily API returned invalid or out-of-date vocabulary")
                return@withContext Result.retry()
            }

            // Persist before updating widgets so Android can redraw them from cache while offline.
            prefs.edit()
                .putString(KEY_DATE, today)
                .putString(KEY_WORDS, words.toString())
                .commit()

            updateWidgets(applicationContext, words, today)
            Result.success()
        } catch (error: Exception) {
            Log.e(TAG, "Daily refresh failed; keeping the last saved words", error)
            Result.retry()
        }
    }

    private fun validCachedWords(raw: String?): Boolean = try {
        raw != null && validWords(JSONArray(raw))
    } catch (_: Exception) {
        false
    }

    private fun validWords(words: JSONArray): Boolean {
        if (words.length() != 2) return false
        return (0 until words.length()).all { index ->
            val word = words.optJSONObject(index) ?: return@all false
            word.optString("word").isNotBlank() &&
                word.optString("meaning").isNotBlank() &&
                word.optString("hindi").isNotBlank()
        } && words.getJSONObject(0).optString("word").trim()
            .equals(words.getJSONObject(1).optString("word").trim(), ignoreCase = true).not()
    }

    private fun updateWidgets(context: Context, words: JSONArray, date: String) {
        val manager = AppWidgetManager.getInstance(context)
        val first = ComponentName(context, FirstWordWidget::class.java)
        val second = ComponentName(context, SecondWordWidget::class.java)

        manager.updateAppWidget(
            first,
            FirstWordWidget.views(context, words.getJSONObject(0), date)
        )
        manager.updateAppWidget(
            second,
            SecondWordWidget.views(context, words.getJSONObject(1), date)
        )
    }

    private fun request(url: String, method: String, body: String? = null): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 25000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

        return try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "LexiSprint"
        private const val API_URL = "https://lexisprint-ai.abhi25122005.workers.dev/daily"
        private const val PREFS_NAME = "daily_words"
        private const val KEY_DATE = "date"
        private const val KEY_WORDS = "words"
        private val INDIA_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

        private fun networkConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun scheduleDailyRefresh(context: Context) {
            val periodic = PeriodicWorkRequestBuilder<DailyWordWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "lexisprint_daily_refresh",
                ExistingPeriodicWorkPolicy.KEEP,
                periodic
            )
        }

        fun enqueueOneTime(context: Context) {
            val today = LocalDate.now(INDIA_ZONE).toString()
            val request = OneTimeWorkRequestBuilder<DailyWordWorker>()
                .setConstraints(networkConstraints())
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "lexisprint_daily_initial_refresh_$today",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
