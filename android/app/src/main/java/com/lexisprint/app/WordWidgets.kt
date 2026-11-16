package com.lexisprint.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONObject

abstract class BaseWordWidget : AppWidgetProvider() {
    abstract val index: Int
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val prefs = context.getSharedPreferences("daily_words", Context.MODE_PRIVATE)
        val raw = prefs.getString("words", null)
        val date = prefs.getString("date", "") ?: ""
        if (raw != null) {
            val words = org.json.JSONArray(raw)
            if (words.length() == 2) {
                val word = words.getJSONObject(index)
                for (id in ids) manager.updateAppWidget(id, views(context, word, date, index))
            }
        } else {
            for (id in ids) manager.updateAppWidget(id, loadingViews(context, index))
        }
        DailyWordWorker.enqueueOneTime(context)
    }
    companion object {
        fun createViews(context: Context, word: JSONObject, date: String, index: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.word_widget)
            views.setTextViewText(R.id.kicker, "LEXISPRINT · WORD 0${index + 1} · $date")
            views.setTextViewText(R.id.word, word.optString("word", "Word unavailable"))
            views.setTextViewText(R.id.meaning, word.optString("meaning", "Meaning unavailable"))
            views.setTextViewText(R.id.hindi, word.optString("hindi", "हिंदी अर्थ उपलब्ध नहीं"))
            val intent = PendingIntent.getActivity(context, index, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.word, intent)
            views.setOnClickPendingIntent(R.id.meaning, intent)
            return views
        }
        fun loadingViews(context: Context, index: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.word_widget)
            views.setTextViewText(R.id.kicker, "LEXISPRINT · WORD 0${index + 1}")
            views.setTextViewText(R.id.word, "Loading today's word…")
            return views
        }
    }
}

class FirstWordWidget : BaseWordWidget() {
    override val index = 0
    companion object {
        fun views(context: Context, word: JSONObject, date: String) = createViews(context, word, date, 0)
    }
}
class SecondWordWidget : BaseWordWidget() {
    override val index = 1
    companion object {
        fun views(context: Context, word: JSONObject, date: String) = createViews(context, word, date, 1)
    }
}
