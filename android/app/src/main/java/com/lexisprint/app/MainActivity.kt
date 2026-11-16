package com.lexisprint.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            text = "LexiSprint\n\nTwo challenging SSC CGL words every day.\n\nAdd the two LexiSprint widgets from your home-screen widget picker."
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.rgb(18, 22, 37))
            setPadding(32, 48, 32, 32)
        }
        setContentView(view)
        scheduleRefresh()
        DailyWordWorker.enqueueOneTime(this)
    }

    private fun scheduleRefresh() {
        val request = PeriodicWorkRequestBuilder<DailyWordWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "lexisprint_daily_refresh", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }
}
