package com.dpibypass

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

private val Bg1 = Color(0xFF060A1C)
private val Bg2 = Color(0xFF1A0A38)
private val Violet = Color(0xFF7C5CFF)
private val Cyan = Color(0xFF22D3EE)
private val Green = Color(0xFF34D399)
private val CardBg = Color(0x1FFFFFFF)
private val Muted = Color(0xFFB8B8D8)

class MainActivity : ComponentActivity() {

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (it.resultCode == RESULT_OK) startVpn() }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        ExitInfo.log(this)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Violet, secondary = Cyan,
                    surface = Bg1, background = Bg1,
                    onSurface = Color.White, onBackground = Color.White
                )
            ) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Screen()
                }
            }
        }
    }

    private fun toggle(status: VpnStatus) {
        if (status == VpnStatus.CONNECTED) {
            startService(Intent(this, DpiVpnService::class.java).setAction(DpiVpnService.ACTION_STOP))
        } else {
            val i = VpnService.prepare(this)
            if (i != null) vpnPermission.launch(i) else startVpn()
        }
    }

    private fun startVpn() {
        ContextCompat.startForegroundService(
            this, Intent(this, DpiVpnService::class.java).setAction(DpiVpnService.ACTION_START)
        )
    }

    @Composable
    private fun Screen() {
        val prefs = remember { Prefs(applicationContext) }
        val status by DpiVpnService.status.collectAsState()
        val err by DpiVpnService.error.collectAsState()
        var preset by remember { mutableStateOf(prefs.presetId) }
        var custom by remember { mutableStateOf(prefs.customArgs) }
        var only by remember { mutableStateOf(prefs.onlyDiscord) }
        var tuning by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf("") }
        var log by remember { mutableStateOf(AppLog.read()) }
        val clip = LocalClipboardManager.current
        val scope = rememberCoroutineScope()
        val idle = status == VpnStatus.STOPPED || status == VpnStatus.ERROR

        LaunchedEffect(status, err, tuning) { log = AppLog.read() }

        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Bg1, Bg2)))) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(28.dp))
                Shield(status == VpnStatus.CONNECTED)
                Text(
                    "DPI AŞICI",
                    style = TextStyle(
                        brush = Brush.horizontalGradient(listOf(Cyan, Violet)),
                        fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp
                    )
                )
                Text("Yasaklı sitelere ping'siz geçiş", color = Muted, fontSize = 13.sp)

                Spacer(Modifier.height(20.dp))
                ConnectButton(status, enabled = !tuning && status != VpnStatus.CONNECTING) { toggle(status) }
                Spacer(Modifier.height(6.dp))
                Text(
                    when (status) {
                        VpnStatus.CONNECTED -> "Korumalı: engelli siteler açılabilir"
                        VpnStatus.CONNECTING -> "Bağlanıyor…"
                        VpnStatus.ERROR -> err
                        VpnStatus.STOPPED -> "Kapalı"
                    },
                    color = if (status == VpnStatus.ERROR) Color(0xFFFF7A8A)
                    else if (status == VpnStatus.CONNECTED) Green else Muted,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Otomatik ayarla", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Discord'a karşı tüm yöntemleri dener, senin internetinde çalışanı seçer.",
                            fontSize = 12.sp, color = Muted
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            enabled = idle && !tuning,
                            onClick = {
                                scope.launch {
                                    tuning = true
                                    progress = "Başlıyor…"
                                    val p = Tester.autoTune(applicationContext) { progress = it }
                                    tuning = false
                                    if (p != null) {
                                        preset = p.id
                                        prefs.presetId = p.id
                                        progress = "Bulundu: ${p.name} ✓  Şimdi BAĞLAN'a bas."
                                    } else {
                                        progress = "Hiçbir yöntem çalışmadı. Bu operatörde tünel modu gerekebilir."
                                    }
                                }
                            }
                        ) { Text(if (tuning) "Deneniyor…" else "Otomatik bul") }
                        if (progress.isNotEmpty()) Text(progress, fontSize = 12.sp, color = Cyan)
                    }
                }

                Spacer(Modifier.height(14.dp))
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Yöntem", style = MaterialTheme.typography.titleMedium)
                        (Presets.all.map { Triple(it.id, it.name, it.args) } +
                            Triple(Presets.CUSTOM, "Özel (gelişmiş)", "")).forEach { (id, name, args) ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .selectable(
                                        selected = preset == id,
                                        enabled = idle && !tuning,
                                        role = Role.RadioButton,
                                        onClick = { preset = id; prefs.presetId = id }
                                    )
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = preset == id, onClick = null)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(name)
                                    if (args.isNotEmpty()) Text(args, fontSize = 11.sp, color = Muted)
                                }
                            }
                        }
                        if (preset == Presets.CUSTOM) {
                            OutlinedTextField(
                                value = custom,
                                onValueChange = { custom = it; prefs.customArgs = it },
                                label = { Text("ByeDPI argümanları") },
                                enabled = idle && !tuning,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sadece Discord", style = MaterialTheme.typography.titleMedium)
                            Text("Kapalıysa tüm uygulamalar ve tarayıcı korunur.", fontSize = 12.sp, color = Muted)
                        }
                        Switch(
                            checked = only,
                            enabled = idle && !tuning,
                            onCheckedChange = { only = it; prefs.onlyDiscord = it }
                        )
                    }
                }

                if (log.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Hata kaydı", style = MaterialTheme.typography.titleMedium)
                            Text(
                                log, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Muted,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { clip.setText(AnnotatedString(log)) }) { Text("Kopyala") }
                                OutlinedButton(onClick = { AppLog.clear(); log = "" }) { Text("Temizle") }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "İpucu: Telefon ayarlarında \"Özel DNS\"i dns.google yaparsan DNS engeline karşı ek koruma sağlar.",
                    fontSize = 11.sp, color = Muted, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Shield(active: Boolean) {
    val glow = if (active) Green else Violet
    Canvas(Modifier.size(120.dp)) {
        val w = size.width
        val h = size.height
        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.45f), Color.Transparent)), radius = w / 2)
        val shield = Path().apply {
            moveTo(w * 0.5f, h * 0.16f)
            lineTo(w * 0.8f, h * 0.27f)
            lineTo(w * 0.8f, h * 0.5f)
            cubicTo(w * 0.8f, h * 0.72f, w * 0.66f, h * 0.84f, w * 0.5f, h * 0.92f)
            cubicTo(w * 0.34f, h * 0.84f, w * 0.2f, h * 0.72f, w * 0.2f, h * 0.5f)
            lineTo(w * 0.2f, h * 0.27f)
            close()
        }
        drawPath(shield, Brush.linearGradient(listOf(Cyan, Violet)))
        val bolt = Path().apply {
            moveTo(w * 0.54f, h * 0.30f)
            lineTo(w * 0.38f, h * 0.55f)
            lineTo(w * 0.49f, h * 0.55f)
            lineTo(w * 0.46f, h * 0.78f)
            lineTo(w * 0.64f, h * 0.48f)
            lineTo(w * 0.52f, h * 0.48f)
            close()
        }
        drawPath(bolt, Color.White)
    }
}

@Composable
private fun ConnectButton(status: VpnStatus, enabled: Boolean, onClick: () -> Unit) {
    val connected = status == VpnStatus.CONNECTED
    val busy = status == VpnStatus.CONNECTING
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )
    val scale = if (connected || busy) pulse else 1f
    val c1 = if (connected) Green else Violet
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
        Box(Modifier.size(195.dp).scale(scale).clip(CircleShape).background(c1.copy(alpha = 0.18f)))
        Box(
            Modifier.size(160.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(c1, Cyan)))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                when {
                    connected -> "BAĞLI\nkapatmak için dokun"
                    busy -> "Bağlanıyor…"
                    else -> "BAĞLAN"
                },
                color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = if (connected) 15.sp else 20.sp, textAlign = TextAlign.Center
            )
        }
    }
}
