
const cors = (origin) => ({
  "Access-Control-Allow-Origin": origin || "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
  "Vary": "Origin"
});

const json = (data, status = 200, origin = "*", extra = {}) =>
  new Response(JSON.stringify(data), {
    status,
    headers: {
      ...cors(origin),
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
      ...extra
    }
  });

async function generateWords(env, date, used = []) {
  if (!env.GEMINI_API_KEY) {
    throw new Error("GEMINI_API_KEY is not configured");
  }

  if (!env.DAILY_WORDS) {
    throw new Error("DAILY_WORDS KV binding is not configured");
  }

  const prompt = [
    "Create exactly TWO challenging but useful English vocabulary entries for SSC CGL advanced preparation.",
    "Choose uncommon, exam-relevant words; avoid duplicates from this list: " + used.join(", "),
    "For each main word provide word, part, meaning, hindi, example.",
    "Also provide exactly 3 synonyms and 3 antonyms as arrays of objects, each with word, meaning (short English definition), and hindi (Hindi meaning).",
    "Return only JSON in this exact shape: {",
    '"words":[{"word":"","part":"","meaning":"","hindi":"","example":"","synonyms":[{"word":"","meaning":"","hindi":""}],"antonyms":[{"word":"","meaning":"","hindi":""}]},{"word":"","part":"","meaning":"","hindi":"","example":"","synonyms":[{"word":"","meaning":"","hindi":""}],"antonyms":[{"word":"","meaning":"","hindi":""}]}]}'
  ].join("\n");

  const model = env.GEMINI_MODEL || "gemini-3.5-flash-lite";

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 120000);

  let response;

  try {
    response = await fetch(
      "https://generativelanguage.googleapis.com/v1beta/models/" +
        model + ":generateContent",
      {
        signal: controller.signal,
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-goog-api-key": env.GEMINI_API_KEY
        },
        body: JSON.stringify({
          contents: [{ parts: [{ text: prompt }] }],
          generationConfig: {
            temperature: 0.6,
            maxOutputTokens: 1000,
            responseMimeType: "application/json",
            thinkingConfig: {
              thinkingLevel: "LOW"
            }
          }
        })
      }
    );
  } finally {
    clearTimeout(timer);
  }

  if (!response.ok) {
    const detail = await response.text();
    throw new Error(
      "Gemini API error: " + response.status +
      " — " + detail.slice(0, 1000)
    );
  }

  const result = await response.json();

  const raw = result.candidates?.[0]?.content?.parts
    ?.map(p => p.text || "")
    .join("");

  if (!raw) {
    throw new Error(
      "Gemini returned no text. Check model access and API quota."
    );
  }

  const parsed = JSON.parse(
    raw.replace(/^\x60\x60\x60json\s*/i, "")
       .replace(/\s*\x60\x60\x60$/, "")
  );

  if (!Array.isArray(parsed.words) || parsed.words.length !== 2) {
    throw new Error("Gemini did not return exactly two words.");
  }

  const words = parsed.words.map(item => ({
    word: String(item.word || "").trim(),
    part: String(item.part || "").trim(),
    meaning: String(item.meaning || "").trim(),
    hindi: String(item.hindi || "").trim(),
    example: String(item.example || "").trim(),

    synonyms: Array.isArray(item.synonyms)
      ? item.synonyms.slice(0, 3).map(x => ({
          word: String(x.word || "").trim(),
          meaning: String(x.meaning || "").trim(),
          hindi: String(x.hindi || "").trim()
        }))
      : [],

    antonyms: Array.isArray(item.antonyms)
      ? item.antonyms.slice(0, 3).map(x => ({
          word: String(x.word || "").trim(),
          meaning: String(x.meaning || "").trim(),
          hindi: String(x.hindi || "").trim()
        }))
      : []
  }));

  const usedSet = new Set(
    used.map(word => word.toLowerCase())
  );

  if (
    words.some(word =>
      !word.word ||
      !word.part ||
      !word.meaning ||
      !word.hindi ||
      !word.example ||
      word.synonyms.length !== 3 ||
      word.antonyms.length !== 3 ||
      [...word.synonyms, ...word.antonyms].some(
        x => !x.word || !x.meaning || !x.hindi
      ) ||
      usedSet.has(word.word.toLowerCase())
    ) ||
    words[0].word.toLowerCase() === words[1].word.toLowerCase()
  ) {
    throw new Error(
      "Gemini returned duplicate or incomplete vocabulary. Please retry."
    );
  }

  const data = { date, words };

  await env.DAILY_WORDS.put(
    "daily:" + date,
    JSON.stringify(data),
    { expirationTtl: 60 * 60 * 24 * 400 }
  );

  return data;
}

export default {

  // AUTOMATIC DAILY GENERATION
  async scheduled(controller, env, ctx) {
    const date = new Intl.DateTimeFormat("en-CA", {
      timeZone: "Asia/Kolkata",
      year: "numeric",
      month: "2-digit",
      day: "2-digit"
    }).format(new Date(controller.scheduledTime));

    const cacheKey = "daily:" + date;

    const existing = await env.DAILY_WORDS?.get(cacheKey);

    if (existing) {
      console.log(
        "LexiSprint words already generated for",
        date
      );
      return;
    }

    try {
      const data = await generateWords(env, date, []);

      console.log(
        "Scheduled vocabulary generated:",
        date,
        data.words.map(w => w.word).join(", ")
      );

    } catch (error) {
      console.error(
        "Scheduled generation failed:",
        date,
        error.message
      );
      throw error;
    }
  },

  // WEBSITE API
  async fetch(request, env) {
    const origin = request.headers.get("Origin") || "*";

    if (request.method === "OPTIONS") {
      return new Response(null, {
        headers: cors(origin)
      });
    }

    const url = new URL(request.url);

    if (url.pathname !== "/daily") {
      return json(
        { error: "Not found. Use /daily." },
        404,
        origin
      );
    }

    if (!["GET", "POST"].includes(request.method)) {
      return json(
        { error: "Method not allowed" },
        405,
        origin
      );
    }

    const requestBody =
      request.method === "POST"
        ? await request.clone().json().catch(() => ({}))
        : {};

    const date = String(
      url.searchParams.get("date") ||
      requestBody.date ||
      new Date().toISOString().slice(0, 10)
    );

    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      return json({ error: "Invalid date" }, 400, origin);
    }

    const cacheKey = "daily:" + date;

    const forceNew =
      request.method === "POST" &&
      requestBody.force === true;

    // LOAD SAVED DAILY WORDS
    if (env.DAILY_WORDS && !forceNew) {
      const cached = await env.DAILY_WORDS.get(cacheKey);

      if (cached) {
        const saved = JSON.parse(cached);

        const modern =
          Array.isArray(saved.words) &&
          saved.words.length === 2 &&
          saved.words.every(item =>
            ["synonyms", "antonyms"].every(key =>
              Array.isArray(item[key]) &&
              item[key].length === 3 &&
              item[key].every(x =>
                x &&
                typeof x === "object" &&
                x.word &&
                x.meaning &&
                x.hindi
              )
            )
          );

        if (modern) {
          return json(saved, 200, origin, {
            "X-LexiSprint-Cache": "HIT"
          });
        }
      }
    }

    if (request.method === "GET") {
      return json(
        {
          error: "No saved vocabulary for this date yet",
          cached: false
        },
        404,
        origin,
        {
          "X-LexiSprint-Cache": env.DAILY_WORDS
            ? "MISS"
            : "KV-NOT-CONFIGURED"
        }
      );
    }

    try {
      const used = Array.isArray(requestBody.used)
        ? requestBody.used
            .filter(x => typeof x === "string")
            .slice(-600)
        : [];

      const data = await generateWords(env, date, used);

      return json(data, 200, origin, {
        "X-LexiSprint-Cache": "STORED"
      });

    } catch (error) {
      const message =
        error.name === "AbortError"
          ? "Gemini request timed out after 120 seconds."
          : (error.message || "Unexpected server error");

      return json(
        { error: message },
        500,
        origin
      );
    }
  }
};
