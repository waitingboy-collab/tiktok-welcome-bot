@file:OptIn(ExperimentalMaterial3Api::class)

package com.rado.tiktokwelcomebot

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private val Pink = Color(0xFFFE2C55)
private val Green = Color(0xFF2E8B57)
private val Muted = Color(0xFF6B7280)
private val PageBg = Color(0xFFF7F8FA)
private val Line = Color(0xFFE3E5E8)

// Desktop браузър: мобилната версия на tiktok.com не показва чата на лайва.
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var engine: BotEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Екранът не заспива, докато ботът работи.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        engine = BotEngine(lifecycleScope) { js -> runJs(js) }
        webView = createWebView()
        webView.loadUrl("https://www.tiktok.com/login")

        // Презареждане на слушателя, ако страницата се е обновила сама (безопасно е да се вика многократно).
        lifecycleScope.launch {
            while (true) {
                delay(10_000)
                if (engine.connectedUser != null) injectScraper()
            }
        }

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Pink, secondary = Pink)) {
                Surface(Modifier.fillMaxSize(), color = PageBg) {
                    AppScreen(
                        engine = engine,
                        webView = webView,
                        onConnect = { connect(it) },
                        onDisconnect = { engine.disconnect() },
                    )
                }
            }
        }
    }

    private fun connect(user: String) {
        val u = user.trim().removePrefix("@")
        if (u.isEmpty()) return
        engine.connect(u)
        webView.loadUrl("https://www.tiktok.com/@$u/live")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            // Позволява инспекция от компютъра през chrome://inspect (за нови селектори).
            WebView.setWebContentsDebuggingEnabled(true)
        }
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.userAgentString = DESKTOP_UA
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        addJavascriptInterface(Bridge(), "RadoBridge")
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (engine.connectedUser != null && url.contains("/live")) injectScraper()
            }
        }
    }

    private fun injectScraper() {
        if (webView.url?.contains("/live") != true) return
        webView.evaluateJavascript(LiveScripts.SCRAPER) { result -> engine.onScraperResult(result) }
    }

    private suspend fun runJs(script: String): String? = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(script) { result -> if (cont.isActive) cont.resume(result) }
    }

    /** Мостът, през който JS от страницата праща събития към бота. */
    inner class Bridge {
        @JavascriptInterface
        fun onEvent(json: String) {
            runOnUiThread { engine.onEvent(json) }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}

// ── UI ─────────────────────────────────────────────────────────

@Composable
private fun AppScreen(
    engine: BotEngine,
    webView: WebView,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    // В началото браузърът е голям — за вход в TikTok.
    var browserBig by rememberSaveable { mutableStateOf(true) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier.fillMaxWidth().weight(if (browserBig) 1f else 0.38f)) {
            AndroidView(
                factory = {
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        TextButton(
            onClick = { browserBig = !browserBig },
            modifier = Modifier.fillMaxWidth().background(Color.White),
        ) {
            Text(if (browserBig) "▼ Към бота" else "▲ Покажи браузъра (вход в TikTok)")
        }
        if (!browserBig) {
            BotPanel(engine, onConnect, onDisconnect, Modifier.weight(0.62f))
        }
    }
}

@Composable
private fun BotPanel(
    engine: BotEngine,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var user by rememberSaveable { mutableStateOf("") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val connected = engine.connectedUser != null

    Column(
        modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = user,
                onValueChange = { user = it.trim().removePrefix("@") },
                label = { Text("потребител (без @)") },
                singleLine = true,
                enabled = !connected,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (connected) onDisconnect() else onConnect(user) }) {
                Text(if (connected) "Спри" else "Свържи")
            }
        }
        Text(
            "● ${engine.status}",
            color = if (engine.listening) Green else Muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showSettings = !showSettings }) {
                Text(if (showSettings) "▲ Скрий настройките" else "⚙ Настройки")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { scope.launch { engine.diagnose() } }) { Text("🔍 Диагностика") }
        }

        if (showSettings) {
            SettingsSection(
                engine,
                Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            )
        }

        LogList(engine.log, Modifier.weight(1f))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            items(engine.replyLangs) { code ->
                FilterChip(
                    selected = engine.replyLang == code,
                    onClick = { engine.replyLang = code },
                    label = { Text(if (code.isEmpty()) "Без превод" else "🌍 $code") },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = engine.manualDraft,
                onValueChange = { engine.manualDraft = it },
                placeholder = { Text("Напиши коментар…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { engine.sendManual() } }) { Text("Изпрати") }
        }
    }
}

@Composable
private fun SettingsSection(engine: BotEngine, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("Пауза между коментари: ${engine.intervalSec} сек", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = engine.intervalSec.toFloat(),
            onValueChange = { engine.intervalSec = it.roundToInt() },
            valueRange = 3f..60f,
        )
        ToggleTemplate("👋 Приветствай новодошлите", engine.welcomeOn, { engine.welcomeOn = it },
            engine.welcomeTemplate, { engine.welcomeTemplate = it })
        ToggleTemplate("🎁 Благодари за подаръци", engine.giftOn, { engine.giftOn = it },
            engine.giftTemplate, { engine.giftTemplate = it })
        ToggleTemplate("⭐ Благодари за последване", engine.followOn, { engine.followOn = it },
            engine.followTemplate, { engine.followTemplate = it })
        Text(
            "{name} = името на зрителя · {gift} = подаръкът",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        ToggleRow("🌍 Превеждай чуждите коментари", engine.translateOn) { engine.translateOn = it }
        ToggleRow("🌍 Поздравявай и благодари на чужденците на техния език", engine.foreignLangOn) {
            engine.foreignLangOn = it
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ToggleTemplate(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    template: String,
    onTemplate: (String) -> Unit,
) {
    ToggleRow(label, checked, onChange)
    OutlinedTextField(
        value = template,
        onValueChange = onTemplate,
        singleLine = true,
        enabled = checked,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    )
}

@Composable
private fun LogList(log: List<String>, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) state.scrollToItem(log.size - 1)
    }
    LazyColumn(
        state = state,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .border(1.dp, Line, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        items(log) { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
    }
}
