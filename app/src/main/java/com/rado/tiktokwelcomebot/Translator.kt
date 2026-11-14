package com.rado.tiktokwelcomebot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Безплатен превод през публичния Google Translate endpoint (client=gtx).
 * Не изисква ключ, но е неофициален — при много заявки Google може временно да ограничи.
 */
object Translator {

    data class Result(val text: String, val sourceLang: String)

    private val gate = Semaphore(3) // макс. 3 едновременни заявки

    suspend fun translate(text: String, targetLang: String): Result? = gate.withPermit {
        withContext(Dispatchers.IO) {
            try {
                val url = URL(
                    "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t" +
                        "&tl=" + URLEncoder.encode(targetLang, "UTF-8") +
                        "&q=" + URLEncoder.encode(text, "UTF-8")
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                val body = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }

                val root = JSONArray(body)
                val sb = StringBuilder()
                val segments = root.getJSONArray(0)
                for (i in 0 until segments.length()) {
                    val piece = segments.optJSONArray(i)?.opt(0)
                    if (piece is String) sb.append(piece)
                }
                val lang = if (root.length() > 2) (root.opt(2) as? String).orEmpty() else ""
                Result(sb.toString(), lang)
            } catch (e: Exception) {
                null
            }
        }
    }
}
