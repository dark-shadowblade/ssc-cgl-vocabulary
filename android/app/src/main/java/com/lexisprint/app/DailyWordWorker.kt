package com.lexisprint.app

import android.content.Context
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId

class DailyWordWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val date = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
            val endpoint = "https://lexisprint-ai.abhi25122005.workers.dev/daily?date=$date"
            var response = request(endpoint, "GET")
            if (response.first !in 200..299) {
                response = request("https://lexisprint-ai.abhi25122005.workers.dev/daily", "POST",
                    JSONObject().put("date", date).toString())
            }
            if (response.first !in 200..299) return@withContext Result.retry()
            val data = JSONObject(response.second)
            val words = data.optJSONArray("words") ?: return@withContext Result.retry()
            if (words.length() != 2) return@withContext Result.retry()
            val prefs = applicationContext.getSharedPreferences("daily_words", Context.MODE_PRIVATE)
            prefs.edit().putString("date", date)
                .putString("words", words.toString()).apply()
            val manager = AppWidgetManager.getInstance(applicationContext)
            manager.updateAppWidget(ComponentName(applicationContext, FirstWordWidget::class.java),
                FirstWordWidget.views(applicationContext, words.getJSONObject(0), date))
            manager.updateAppWidget(ComponentName(applicationContext, SecondWordWidget::class.java),
                SecondWordWidget.views(applicationContext, words.getJSONObject(1), date))
            Result.success()
        } catch (e: Exception) {
            Log.e("LexiSprint", "Daily refresh failed", e)
            Result.retry()
        }
    }

    private fun request(url: String, method: String, body: String? = null): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 20000
        connection.readTimeout = 35000
        connection.setRequestProperty("Accept", "application/json")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        return code to text
    }

    companion object {
        fun enqueueOneTime(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<DailyWordWorker>().build())
        }
    }
}
