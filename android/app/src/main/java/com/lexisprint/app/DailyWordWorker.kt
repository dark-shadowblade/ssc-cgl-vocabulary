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
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Fetches two fresh dictionary entries directly from the public, no-key dictionary API.
 * No Cloudflare Worker, generated content, API key, or bundled word rotation is used.
 */
class DailyWordWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = LocalDate.now(INDIA_ZONE).toString()

        if (prefs.getString(KEY_DATE, null) == today && validCachedWords(prefs.getString(KEY_WORDS, null))) {
            return@withContext Result.success()
        }

        try {
            val seenWords = readWordHistory(prefs.getString(KEY_HISTORY, null))
            val words = fetchTwoDictionaryWords(seenWords)
            if (!validWords(words)) {
                Log.w(TAG, "Dictionary did not return two distinct usable entries")
                return@withContext Result.retry()
            }

            // Save before rendering so the last successful entries remain available offline.
            val saved = prefs.edit()
                .putString(KEY_DATE, today)
                .putString(KEY_WORDS, words.toString())
                .putString(KEY_HISTORY, updatedWordHistory(seenWords, words).toString())
                .commit()
            if (!saved) return@withContext Result.retry()

            updateWidgets(applicationContext, words, today)
            Result.success()
        } catch (error: Exception) {
            Log.e(TAG, "Dictionary refresh failed; keeping the last saved words", error)
            Result.retry()
        }
    }

    private fun fetchTwoDictionaryWords(seenWords: Set<String>): JSONArray {
        val selected = LinkedHashMap<String, JSONObject>()

        // This dictionary endpoint supplies a random entry from its dictionary word list.
        // Ask again when it returns a duplicate; cap retries to avoid a tight request loop.
        repeat(MAX_WORD_ATTEMPTS) {
            if (selected.size < 2) {
                val entry = requestJson("$DICTIONARY_BASE/wordoftheday")
                val word = parseDictionaryEntry(entry)
                if (word != null) {
                    val key = word.optString("word").trim().lowercase()
                    if (key !in seenWords) selected.putIfAbsent(key, word)
                }
            }
        }

        // If the random endpoint repeats, use the same dictionary's indexed word list
        // to obtain a different candidate, then resolve its full definition there.
        if (selected.size < 2) {
            val first = selected.values.firstOrNull()?.optString("word").orEmpty()
            val prefix = first.take(1).ifBlank { "a" }
            val candidates = requestJsonArray(
                "$DICTIONARY_BASE/words/en/?filter=${encode(prefix)}&page=0&size=100"
            )
            for (index in 0 until candidates.length()) {
                if (selected.size >= 2) break
                val candidate = candidates.optString(index).trim()
                if (candidate.isBlank() || candidate.equals(first, ignoreCase = true) || candidate.lowercase() in seenWords) continue
                try {
                    val definition = requestJson(
                        "$DICTIONARY_BASE/definitions/en/${encode(candidate)}?compact=true"
                    )
                    val parsed = parseDictionaryEntry(definition)
                    if (parsed != null) {
                        val key = parsed.optString("word").trim().lowercase()
                        if (key !in seenWords) selected.putIfAbsent(key, parsed)
                    }
                } catch (error: Exception) {
                    Log.d(TAG, "Skipping dictionary candidate that has no usable definition", error)
                }
            }
        }

        if (selected.size < 2) throw IllegalStateException("Could not find two distinct dictionary words")

        val result = JSONArray()
        selected.values.take(2).forEach { entry ->
            val word = entry.optString("word")
            val hindi = fetchHindiTranslation(word)
            result.put(
                JSONObject()
                    .put("word", word)
                    .put("meaning", entry.optString("meaning"))
                    .put("hindi", hindi)
                    .put("pronunciation", entry.optString("pronunciation"))
                    .put("partOfSpeech", entry.optString("partOfSpeech"))
            )
        }
        return result
    }

    private fun parseDictionaryEntry(data: JSONObject): JSONObject? {
        val word = data.optString("word").trim()
        val meanings = data.optJSONArray("meanings") ?: return null
        if (word.isBlank()) return null

        for (i in 0 until meanings.length()) {
            val meaning = meanings.optJSONObject(i) ?: continue
            val partOfSpeech = meaning.optString("partOfSpeech")
            val senses = meaning.optJSONArray("senses") ?: continue
            for (j in 0 until senses.length()) {
                val sense = senses.optJSONObject(j) ?: continue
                val glosses = sense.optJSONArray("glosses") ?: continue
                val definition = glosses.optString(0).trim()
                if (definition.isNotBlank()) {
                    return JSONObject()
                        .put("word", word)
                        .put("meaning", definition)
                        .put("partOfSpeech", partOfSpeech)
                        .put("pronunciation", data.optString("ipa"))
                }
            }
        }
        return null
    }

    private fun fetchHindiTranslation(word: String): String {
        return try {
            val translations = requestJsonArray(
                "$DICTIONARY_BASE/translations/en/?word=${encode(word)}"
            )
            for (i in 0 until translations.length()) {
                val item = translations.optJSONObject(i) ?: continue
                val language = item.optString("lang")
                val code = item.optString("code")
                if (!code.equals("hi", true) && !language.equals("Hindi", true)) continue
                val senses = item.optJSONArray("translationSenses") ?: continue
                for (j in 0 until senses.length()) {
                    val translation = senses.optJSONObject(j)?.optString("word").orEmpty().trim()
                    if (translation.isNotBlank()) return translation
                }
            }
            ""
        } catch (error: Exception) {
            Log.d(TAG, "Hindi translation unavailable for $word", error)
            ""
        }
    }

    private fun readWordHistory(raw: String?): Set<String> = try {
        val array = JSONArray(raw ?: "[]")
        (0 until array.length()).map { array.optString(it).trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    private fun updatedWordHistory(previous: Set<String>, todayWords: JSONArray): JSONArray {
        val history = LinkedHashSet(previous)
        for (i in 0 until todayWords.length()) {
            val word = todayWords.optJSONObject(i)?.optString("word")?.trim()?.lowercase().orEmpty()
            if (word.isNotBlank()) history.add(word)
        }
        return JSONArray(history.toList().takeLast(MAX_HISTORY_WORDS))
    }

    private fun validCachedWords(raw: String?): Boolean = try {
        raw != null && validWords(JSONArray(raw))
    } catch (_: Exception) {
        false
    }

    private fun validWords(words: JSONArray): Boolean {
        if (words.length() != 2) return false
        val first = words.optJSONObject(0) ?: return false
        val second = words.optJSONObject(1) ?: return false
        return first.optString("word").isNotBlank() &&
            first.optString("meaning").isNotBlank() &&
            second.optString("word").isNotBlank() &&
            second.optString("meaning").isNotBlank() &&
            !first.optString("word").trim().equals(second.optString("word").trim(), ignoreCase = true)
    }

    private fun updateWidgets(context: Context, words: JSONArray, date: String) {
        val manager = AppWidgetManager.getInstance(context)
        manager.updateAppWidget(
            ComponentName(context, FirstWordWidget::class.java),
            FirstWordWidget.views(context, words.getJSONObject(0), date)
        )
        manager.updateAppWidget(
            ComponentName(context, SecondWordWidget::class.java),
            SecondWordWidget.views(context, words.getJSONObject(1), date)
        )
    }

    private fun requestJson(url: String): JSONObject {
        val response = request(url)
        if (response.first !in 200..299) {
            throw IllegalStateException("Dictionary API returned HTTP ${response.first}")
        }
        return JSONObject(response.second)
    }

    private fun requestJsonArray(url: String): JSONArray {
        val response = request(url)
        if (response.first !in 200..299) {
            throw IllegalStateException("Dictionary API returned HTTP ${response.first}")
        }
        return JSONArray(response.second)
    }

    private fun request(url: String): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 18000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LexiSprint-Android/1.0")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val TAG = "LexiSprint"
        private const val DICTIONARY_BASE = "https://api.suvankar.cc/dictionaryapi/v1"
        private const val PREFS_NAME = "daily_words"
        private const val KEY_DATE = "date"
        private const val KEY_WORDS = "words"
        private const val KEY_HISTORY = "seen_words"
        private const val MAX_HISTORY_WORDS = 10000
        private const val MAX_WORD_ATTEMPTS = 8
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
