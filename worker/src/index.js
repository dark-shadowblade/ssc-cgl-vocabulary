const cors = (origin) => ({
  "Access-Control-Allow-Origin": origin || "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
  "Vary": "Origin"
});
const json = (data, status = 200, origin = "*", extra = {}) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { ...cors(origin), "Content-Type": "application/json", "Cache-Control": "no-store", ...extra }
  });

export default {
  async fetch(request, env) {
    const origin = request.headers.get("Origin") || "*";
    if (request.method === "OPTIONS") return new Response(null, { headers: cors(origin) });

    const url = new URL(request.url);
    if (url.pathname !== "/daily") return json({ error: "Not found. Use /daily." }, 404, origin);
    if (!["GET", "POST"].includes(request.method)) return json({ error: "Method not allowed" }, 405, origin);

    const date = String(url.searchParams.get("date") || (request.method === "POST" ? (await request.clone().json().catch(() => ({}))).date : "") || new Date().toISOString().slice(0, 10));
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return json({ error: "Invalid date" }, 400, origin);
    const cacheKey = "daily:" + date;

    // Durable daily cache: bind a Cloudflare KV namespace named DAILY_WORDS.
    if (env.DAILY_WORDS) {
      const cached = await env.DAILY_WORDS.get(cacheKey);
      if (cached) return json(JSON.parse(cached), 200, origin, { "X-LexiSprint-Cache": "HIT" });
    }

    if (request.method === "GET") {
      return json({ error: "No saved vocabulary for this date yet", cached: false }, 404, origin, {
        "X-LexiSprint-Cache": env.DAILY_WORDS ? "MISS" : "KV-NOT-CONFIGURED"
      });
    }
    if (!env.GEMINI_API_KEY) return json({ error: "GEMINI_API_KEY is not configured" }, 500, origin);

    try {
      const body = await request.json();
      const used = Array.isArray(body.used) ? body.used.filter(x => typeof x === "string").slice(-600) : [];
      const prompt = [
        "Create exactly TWO challenging but useful English vocabulary entries for SSC CGL advanced preparation.",
        "Choose uncommon, exam-relevant words; avoid duplicates from this list: " + used.join(", "),
        "For each word provide accurate concise fields: word, part, meaning (English), hindi (natural Hindi translation), example (one natural sentence), synonyms (3 comma-separated), antonyms (3 comma-separated).",
        "Return only JSON in this exact shape: {",
        '"words":[{"word":"","part":"","meaning":"","hindi":"","example":"","synonyms":"","antonyms":""},{"word":"","part":"","meaning":"","hindi":"","example":"","synonyms":"","antonyms":""}]}'
      ].join("\n");

      const model = env.GEMINI_MODEL || "gemini-3.8-flash";
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 25000);
      let response;
      try {
        response = await fetch("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent", {
          signal: controller.signal,
          method: "POST",
          headers: { "Content-Type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
          body: JSON.stringify({
            contents: [{ parts: [{ text: prompt }] }],
            generationConfig: { temperature: 0.6, maxOutputTokens: 900, responseMimeType: "application/json", thinkingConfig: { thinkingLevel: "LOW" } }
          })
        });
      } finally {
        clearTimeout(timer);
      }

      if (!response.ok) {
        const detail = await response.text();
        return json({ error: "Gemini API error: " + response.status, detail: detail.slice(0, 1200) }, 502, origin);
      }
      const result = await response.json();
      const raw = result.candidates?.[0]?.content?.parts?.map(p => p.text || "").join("");
      if (!raw) throw new Error("Gemini returned no text. Check model access and API quota.");

      const parsed = JSON.parse(raw.replace(/^```json\s*/i, "").replace(/\s*```$/, ""));
      if (!Array.isArray(parsed.words) || parsed.words.length !== 2) throw new Error("Gemini did not return exactly two words.");
      const fields = ["word", "part", "meaning", "hindi", "example", "synonyms", "antonyms"];
      const words = parsed.words.map(item => Object.fromEntries(fields.map(field => [field, String(item[field] || "").trim()])));
      const usedSet = new Set(used.map(word => word.toLowerCase()));
      if (words.some(word => fields.some(field => !word[field]) || usedSet.has(word.word.toLowerCase())) ||
          words[0].word.toLowerCase() === words[1].word.toLowerCase()) {
        throw new Error("Gemini returned duplicate or incomplete vocabulary. Please retry.");
      }

      const data = { date, words };
      if (env.DAILY_WORDS) {
        await env.DAILY_WORDS.put(cacheKey, JSON.stringify(data), { expirationTtl: 60 * 60 * 24 * 400 });
      }
      return json(data, 200, origin, { "X-LexiSprint-Cache": env.DAILY_WORDS ? "STORED" : "NO-KV" });
    } catch (error) {
      const message = error.name === "AbortError" ? "Gemini request timed out after 25 seconds." : (error.message || "Unexpected server error");
      return json({ error: message }, 500, origin);
    }
  }
};