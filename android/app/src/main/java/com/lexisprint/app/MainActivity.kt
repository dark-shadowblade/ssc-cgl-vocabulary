package com.lexisprint.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val view = TextView(this).apply {
            text = "LexiSprint\n\nTwo dictionary-sourced vocabulary words every day.\n\nAdd the two separate LexiSprint widgets from your home-screen widget picker. They fetch fresh entries directly from a free dictionary service, then keep the last saved words available offline. No Cloudflare account, API key, or AI generation is used by the Android widgets.\n\nDictionary data: Wiktionary via Suvankar Free Dictionary API (CC BY-SA 4.0)."
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
