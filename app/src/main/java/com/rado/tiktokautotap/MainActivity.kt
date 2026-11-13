package com.rado.tiktokautotap

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rado.tiktokautotap.ui.theme.TikTokAutoTapTheme
import kotlinx.coroutines.launch

/**
 * Основна активност на приложението с Jetpack Compose UI (на български).
 * Показва статус на услугата, бутон за отваряне на Accessibility Settings,
 * слайдери за интервал и разброс, лимити и упътване.
 */
class MainActivity : ComponentActivity() {

    private lateinit var settingsRepo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepo = SettingsRepository(applicationContext)

        setContent {
            TikTokAutoTapTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(settingsRepo, onOpenAccessibilitySettings = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                    })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkAccessibilityServiceStatus()
    }

    private fun checkAccessibilityServiceStatus() {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val isEnabled = enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                    it.resolveInfo.serviceInfo.name == AutoTapService::class.java.name
        }
        AutoTapState.setServiceRunning(isEnabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settingsRepo: SettingsRepository,
    onOpenAccessibilitySettings: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    val isServiceRunning by AutoTapState.isServiceRunning.collectAsState()
    val isTapping by AutoTapState.isTapping.collectAsState()
    val tapCount by AutoTapState.tapCount.collectAsState()

    val intervalFlow = settingsRepo.intervalFlow.collectAsState(initial = 200L)
    val jitterFlow = settingsRepo.jitterFlow.collectAsState(initial = 40)
    val maxTapsFlow = settingsRepo.maxTapsFlow.collectAsState(initial = 500)
    val showOverlayFlow = settingsRepo.showOverlayFlow.collectAsState(initial = true)

    var maxTapsText by remember { mutableStateOf("500") }

    LaunchedEffect(maxTapsFlow.value) { maxTapsText = maxTapsFlow.value.toString() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TikTok Auto Tap", fontWeight = FontWeight.Bold, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFFE2C55))
            )
        }
    ) { paddingVals ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingVals)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Статус карта
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isServiceRunning) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isServiceRunning) "Услугата е включена ✓" else "Услугата е изключена ✗",
                        fontWeight = FontWeight.Bold,
                        color = if (isServiceRunning) Color(0xFF2E7D32) else Color(0xFFC62828),
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onOpenAccessibilitySettings,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFE2C55))
                    ) {
                        Text("Включи в Достъпност")
                    }
                }
            }

            // Брояч на живо
            if (isServiceRunning) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "Статус на тапане: ${if (isTapping) "РАБОТИ (■)" else "СПРЯН (▶)"}", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "Направени тапове: $tapCount", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFE2C55))
                    }
                }
            }

            // Настройки
            Text(text = "Настройки за тапване", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            // Интервал
            Column {
                Text("Интервал между таповете (ms): ${intervalFlow.value}")
                Slider(
                    value = intervalFlow.value.toFloat().coerceIn(50f, 5000f),
                    onValueChange = { newVal ->
                        coroutineScope.launch { settingsRepo.setInterval(newVal.toLong()) }
                    },
                    valueRange = 50f..5000f,
                    steps = 98,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFE2C55), activeTrackColor = Color(0xFFFE2C55))
                )
            }

            // Случаен разброс (jitter)
            Column {
                Text("Случаен разброс / Jitter (ms): ${jitterFlow.value}")
                Slider(
                    value = jitterFlow.value.toFloat().coerceIn(0f, 200f),
                    onValueChange = { newVal ->
                        coroutineScope.launch { settingsRepo.setJitter(newVal.toInt()) }
                    },
                    valueRange = 0f..200f,
                    steps = 20,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFE2C55), activeTrackColor = Color(0xFFFE2C55))
                )
            }

            // Макс тапове
            OutlinedTextField(
                value = maxTapsText,
                onValueChange = {
                    maxTapsText = it
                    val intVal = it.toIntOrNull() ?: 0
                    coroutineScope.launch { settingsRepo.setMaxTaps(intVal) }
                },
                label = { Text("Макс. тапове (0 = без лимит)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            // Покажи плаващия бутон (Switch)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Покажи плаващия бутон (Overlay)", fontWeight = FontWeight.Medium)
                Switch(
                    checked = showOverlayFlow.value,
                    onCheckedChange = { checked ->
                        coroutineScope.launch { settingsRepo.setShowOverlay(checked) }
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFE2C55), checkedTrackColor = Color(0xFFFCC2CD))
                )
            }

            HorizontalDivider()

            // Упътване
            Text(text = "Кратко упътване", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                text = "1. Натисни бутона „Включи в Достъпност“ и активирай TikTok Auto Tap.\n" +
                        "2. Отваряй TikTok LIVE.\n" +
                        "3. Премести полупрозрачния мерник върху областта със сърца/видеото.\n" +
                        "4. Натисни плаващия бутон ▶ за старт (или ■ за стоп).",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Предупреждение
            Text(
                text = "Предупреждение: При интервал под 150 ms TikTok може да не отчита коректно сърцата или да ограничи временно акаунта ви. Използвайте разумни стойности.",
                fontSize = 12.sp,
                color = Color.Gray,
                fontStyle = FontStyle.Italic
            )
        }
    }
}
