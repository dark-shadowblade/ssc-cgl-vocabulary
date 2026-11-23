# LexiSprint — SSC CGL Vocabulary

Installable vocabulary PWA with two challenging words, Hindi meanings, examples, synonyms, antonyms, quiz, bookmarks and local history.

## GitHub Pages
This repository is configured for a static site. Open **Settings → Pages**, choose **Deploy from a branch**, select **main** and **/(root)**, then Save. The site URL will be https://dark-shadowblade.github.io/ssc-cgl-vocabulary/.

## Native Android daily widgets
The two native Android home-screen widgets fetch two distinct daily vocabulary entries directly from the free Suvankar Dictionary API, whose data is sourced from Wiktionary (CC BY-SA 4.0). The Android widget feature does not use Cloudflare, Gemini, an API key, or a bundled rotating word list. It caches the latest successful pair on-device for offline display. Internet access is required to fetch a new pair. Android may delay background refreshes because WorkManager is subject to battery and Doze restrictions.

## AI generation backend
The frontend calls a Cloudflare Worker at /daily. Deploy `worker/src/index.js` as a Worker, add the secret `GEMINI_API_KEY`, and bind a Cloudflare KV namespace named `DAILY_WORDS` for persistent once-per-day caching. Without this binding, daily words will not be saved centrally. Set `GEMINI_MODEL` to a model enabled for your key (default gemini-2.5-flash). Paste the deployed Worker URL into Settings in LexiSprint.

Do not put the Gemini key in public frontend files. Browser history/bookmarks are local to that device. A PWA cannot create a native Android home-screen widget; that needs a native companion app. Browser notification permission alone does not schedule a daily reminder.