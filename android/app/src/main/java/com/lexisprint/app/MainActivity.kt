package com.lexisprint.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val view = TextView(this).apply {
            text = "LexiSprint\n\nTwo challenging SSC CGL words every day.\n\nAdd the two separate LexiSprint widgets from your home-screen widget picker. They refresh automatically when the new daily words are available and keep the last saved words offline."
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.rgb(18, 22, 37))
            setPadding(32, 48, 32, 32)
        }
        setContentView(view)

        DailyWordWorker.scheduleDailyRefresh(this)
        DailyWordWorker.enqueueOneTime(this)
    }
}
