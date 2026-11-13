package com.rado.tiktokwelcomebot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Логиката на бота: опашки, поздрави, благодарности, превод.
 * Всичко работи на главната нишка (scope = lifecycleScope), затова няма нужда от заключване.
 */
class BotEngine(
    private val scope: CoroutineScope,
    private val runJs: suspend (String) -> String?,
) {
    companion object {
        const val MAX_WELCOME_QUEUE = 5      // макс. чакащи поздрави (за да не спами при наплив)
        const val MAX_PRIORITY_QUEUE = 10    // макс. чакащи благодарности
        const val GIFT_QUIET_MS = 4_000L     // изчаква края на комбото, после благодари веднъж
        const val WARMUP_MS = 8_000L         // първите секунди след отваряне само логваме (историята на чата)
        const val MAX_COMMENT_LENGTH = 150
        const val MY_LANG = "bg"
        const val MAX_LOG = 300
    }

    // ── Настройки (вързани към UI) ─────────────────────────────
    var intervalSec by mutableIntStateOf(10)
    var welcomeOn by mutableStateOf(true)
    var welcomeTemplate by mutableStateOf("Добре дошли, {name}! 👋")
    var giftOn by mutableStateOf(true)
    var giftTemplate by mutableStateOf("{name}, благодаря за {gift}! 🙏")
    var followOn by mutableStateOf(true)
    var followTemplate by mutableStateOf("Благодаря за последването, {name}! ❤️")
    var translateOn by mutableStateOf(true)
    var foreignLangOn by mutableStateOf(true)

    // ── Състояние за UI ────────────────────────────────────────
    var connectedUser by mutableStateOf<String?>(null)
        private set
    var listening by mutableStateOf(false)
        private set
    var status by mutableStateOf("не е свързан")
        private set
    val log = mutableStateListOf<String>()
    val replyLangs = mutableStateListOf("", "en")   // "" = без превод
    var replyLang by mutableStateOf("")
    var manualDraft by mutableStateOf("")

    // ── Вътрешно ───────────────────────────────────────────────
    /** lang = null → на български; иначе шаблонът се превежда при изпращане. */
    private data class Outgoing(val template: String, val name: String, val lang: String?)

    private class PendingGift(val name: String) {
        val gifts = LinkedHashSet<String>()
        var last = 0L
    }

    private val welcomeQueue = ArrayDeque<Outgoing>()
    private val priorityQueue = ArrayDeque<Outgoing>()  // благодарности — изпращат се първи
    private val greeted = HashSet<String>()
    private val foreignGreeted = HashSet<String>()
    private val thankedFollow = HashSet<String>()
    private val pendingGifts = LinkedHashMap<String, PendingGift>()
    private val userLang = HashMap<String, String>()
    private val templateCache = HashMap<String, String>()
    private var listeningSince = 0L
    private var sending = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        scope.launch {
            while (isActive) {
                delay(intervalSec * 1000L)
                processQueue()
            }
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                flushGifts()
            }
        }
    }

    // ── Връзка ─────────────────────────────────────────────────
    fun connect(user: String) {
        greeted.clear(); foreignGreeted.clear(); thankedFollow.clear()
        pendingGifts.clear(); userLang.clear()
        welcomeQueue.clear(); priorityQueue.clear()
        connectedUser = user
        listening = false
        status = "отваряне на лайва на @$user…"
        addLog("→ Отварям лайва на @$user")
    }

    fun disconnect() {
        connectedUser = null
        listening = false
        welcomeQueue.clear(); priorityQueue.clear(); pendingGifts.clear()
        status = "не е свързан"
        addLog("■ Спряно")
    }

    /** Резултат от инжектирането на SCRAPER: "ok" при първо, "already" при повторно. */
    fun onScraperResult(raw: String?) {
        val user = connectedUser ?: return
        if (raw?.contains("ok") == true && !listening) {
            listening = true
            listeningSince = System.currentTimeMillis()
            status = "слуша @$user"
            addLog("✔ Слушам чата на @$user")
        }
    }

    suspend fun diagnose() {
        val raw = runJs(LiveScripts.DIAGNOSE)
        // evaluateJavascript връща резултата като JSON низ — махаме кавичките.
        val r = try { org.json.JSONArray("[$raw]").optString(0) } catch (e: Exception) { raw }
        addLog("🔍 data-e2e на страницата: ${r ?: "няма отговор"}")
    }

    // ── Събития от страницата ──────────────────────────────────
    fun onEvent(json: String) {
        if (connectedUser == null) return
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        val name = o.optString("name").trim()
        val text = o.optString("text").trim()
        when (o.optString("type")) {
            "chat" -> chatReceived(name, text)
            "join" -> userJoined(name)
            "follow" -> userFollowed(name)
            "gift" -> giftReceived(name, parseGift(text))
            else -> addLog("· $name $text")
        }
    }

    /** Първите секунди след отваряне само логваме — това е историята на чата. */
    private val isLive get() = listening && System.currentTimeMillis() - listeningSince > WARMUP_MS

    private fun chatReceived(name: String, text: String) {
        addLog("💬 $name: $text")
        if (!translateOn && !foreignLangOn) return
        if (!looksTranslatable(text)) return

        scope.launch {
            val r = Translator.translate(text, MY_LANG) ?: return@launch
            val lang = r.sourceLang
            if (lang.isEmpty() || sameLang(lang, MY_LANG)) return@launch
            if (r.text.trim().equals(text.trim(), ignoreCase = true)) return@launch

            if (translateOn) addLog("   ↳ [$lang] ${r.text}")
            rememberReplyLanguage(lang)

            if (name.isEmpty()) return@launch
            val key = name.lowercase()
            userLang[key] = lang

            // Първи чужд коментар → поздрав на неговия език (веднъж).
            if (foreignLangOn && welcomeOn && isLive && foreignGreeted.add(key)) {
                greeted.add(key)
                enqueue(welcomeQueue, MAX_WELCOME_QUEUE, Outgoing(welcomeTemplate, name, lang),
                    "   (поздравът е пропуснат — опашката е пълна)")
            }
        }
    }

    private fun userJoined(name: String) {
        if (name.isEmpty()) return
        addLog("→ $name влезе")
        if (!welcomeOn || !isLive) return
        val key = name.lowercase()
        if (!greeted.add(key)) return // всеки се поздравява само веднъж

        // Ако вече знаем езика му (напр. влиза отново), поздравяваме директно на него.
        val lang = foreignLangOf(key)
        if (lang != null) foreignGreeted.add(key)
        enqueue(welcomeQueue, MAX_WELCOME_QUEUE, Outgoing(welcomeTemplate, name, lang),
            "   (поздравът е пропуснат — твърде много влизания наведнъж)")
    }

    private fun giftReceived(name: String, gift: String) {
        if (name.isEmpty()) return
        val key = name.lowercase()
        val p = pendingGifts.getOrPut(key) { PendingGift(name) }
        // При комбо идват много съобщения — логваме само нов вид подарък.
        if (p.gifts.add(gift)) addLog("🎁 $name изпрати $gift")
        p.last = System.currentTimeMillis()
    }

    /** Благодари веднъж, когато зрителят спре да праща подаръци за GIFT_QUIET_MS. */
    private fun flushGifts() {
        if (pendingGifts.isEmpty()) return
        val now = System.currentTimeMillis()
        val ready = pendingGifts.filterValues { now - it.last >= GIFT_QUIET_MS }
        for ((key, p) in ready) {
            pendingGifts.remove(key)
            if (!giftOn || !isLive) continue
            val template = giftTemplate.replace("{gift}", p.gifts.joinToString(", "))
            enqueue(priorityQueue, MAX_PRIORITY_QUEUE, Outgoing(template, p.name, foreignLangOf(key)),
                "   (благодарността е пропусната — опашката е пълна)")
        }
    }

    private fun userFollowed(name: String) {
        if (name.isEmpty()) return
        addLog("⭐ $name последва")
        val key = name.lowercase()
        if (!followOn || !isLive || !thankedFollow.add(key)) return // само веднъж на човек
        enqueue(priorityQueue, MAX_PRIORITY_QUEUE, Outgoing(followTemplate, name, foreignLangOf(key)),
            "   (благодарността е пропусната — опашката е пълна)")
    }

    // ── Изпращане ──────────────────────────────────────────────
    private suspend fun processQueue() {
        if (sending || connectedUser == null) return
        val item = priorityQueue.removeFirstOrNull() ?: welcomeQueue.removeFirstOrNull() ?: return
        sending = true
        try {
            sendComment(buildText(item))
        } catch (e: Exception) {
            addLog("✖ Грешка при изпращане: ${e.message}")
        } finally {
            sending = false
        }
    }

    /** Ръчен коментар от полето долу; превежда се, ако е избран език. */
    suspend fun sendManual() {
        var text = manualDraft.trim()
        if (text.isEmpty() || sending) return
        sending = true
        try {
            if (replyLang.isNotEmpty()) {
                val r = Translator.translate(text, replyLang)
                if (r == null) {
                    addLog("✖ Преводът не успя — коментарът не е изпратен.")
                    return
                }
                text = r.text
            }
            if (sendComment(text)) manualDraft = ""
        } catch (e: Exception) {
            addLog("✖ Грешка при изпращане: ${e.message}")
        } finally {
            sending = false
        }
    }

    private suspend fun sendComment(raw: String): Boolean {
        val text = raw.trim().take(MAX_COMMENT_LENGTH)
        if (text.isEmpty()) return false
        val result = runJs(LiveScripts.send(text))
        if (result == null || !result.contains("ok")) {
            addLog("✖ Не намерих полето за коментар. Влязъл ли си в акаунта и отворен ли е лайвът?")
            return false
        }
        addLog("📤 $text")
        return true
    }

    /** Сглобява текста; превежда шаблона, ако зрителят е чужденец. */
    private suspend fun buildText(o: Outgoing): String {
        val plain = o.template.replace("{name}", o.name)
        val lang = o.lang ?: return plain
        val translated = translateTemplate(o.template, lang) ?: return plain // преводът не успя → на български
        // Името се пази като {0}, за да не го „преведе“ Google.
        return if (translated.contains("{0}")) translated.replace("{0}", o.name)
        else "@${o.name} $translated"
    }

    private suspend fun translateTemplate(template: String, lang: String): String? {
        val key = lang + "\n" + template
        templateCache[key]?.let { return it }
        val r = Translator.translate(template.replace("{name}", "{0}"), lang) ?: return null
        val text = r.text.replace(Regex("""\{\s*0\s*\}"""), "{0}")
        templateCache[key] = text
        return text
    }

    // ── Помощни ────────────────────────────────────────────────
    private fun enqueue(queue: ArrayDeque<Outgoing>, max: Int, item: Outgoing, skippedMessage: String) {
        if (queue.size >= max) { addLog(skippedMessage); return }
        queue.addLast(item)
    }

    private fun foreignLangOf(key: String): String? = if (foreignLangOn) userLang[key] else null

    private fun rememberReplyLanguage(lang: String) {
        val existing = replyLangs.firstOrNull { sameLang(it, lang) } ?: lang.also { replyLangs.add(it) }
        // Не сменяй езика, докато пишеш отговор.
        if (manualDraft.isEmpty()) replyLang = existing
    }

    private fun parseGift(text: String): String {
        val m = Regex("""(?:sent|изпрати|подари)\s+(.+?)(?:\s*[x×]\s*\d+)?\s*$""", RegexOption.IGNORE_CASE).find(text)
        return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "подаръка"
    }

    private fun looksTranslatable(text: String) = text.count { it.isLetter() } >= 2

    private fun sameLang(a: String, b: String) =
        a.substringBefore('-').equals(b.substringBefore('-'), ignoreCase = true)

    fun addLog(text: String) {
        log.add("[${timeFormat.format(Date())}] $text")
        if (log.size > MAX_LOG) log.removeAt(0)
    }
}
