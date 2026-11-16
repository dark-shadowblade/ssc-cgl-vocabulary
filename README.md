# LexiSprint — SSC CGL Vocabulary

Installable vocabulary PWA with two challenging words, Hindi meanings, examples, synonyms, antonyms, quiz, bookmarks and local history.

## GitHub Pages
This repository is configured for a static site. Open **Settings → Pages**, choose **Deploy from a branch**, select **main** and **/(root)**, then Save. The site URL will be https://dark-shadowblade.github.io/ssc-cgl-vocabulary/.

## AI generation backend
The frontend calls a Cloudflare Worker at /daily. Deploy `worker/src/index.js` as a Worker, add the secret `GEMINI_API_KEY`, and bind a Cloudflare KV namespace named `DAILY_WORDS` for persistent once-per-day caching. Without this binding, daily words will not be saved centrally. Set `GEMINI_MODEL` to a model enabled for your key (default gemini-2.5-flash). Paste the deployed Worker URL into Settings in LexiSprint.

Do not put the Gemini key in public frontend files. Browser history/bookmarks are local to that device. A PWA cannot create a native Android home-screen widget; that needs a native companion app. Browser notification permission alone does not schedule a daily reminder.