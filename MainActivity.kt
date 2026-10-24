package com.dpibypass

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val Bg1 = Color(0xFF050816)
private val Bg2 = Color(0xFF150A3A)
private val Violet = Color(0xFF8B5CF6)
private val Cyan = Color(0xFF22D3EE)
private val Green = Color(0xFF34D399)
private val Red = Color(0xFFFF7A8A)
private val CardBg = Color(0x1AFFFFFF)
private val Muted = Color(0xFFB4B4D6)

class MainActivity : ComponentActivity() {

    private var autoRetried = false

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (it.resultCode == RESULT_OK) startVpn() }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // :vpn sürecinden gelen durum yayınları
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val s = try { VpnStatus.valueOf(i.getStringExtra("s") ?: "") } catch (_: Exception) { return }
            DpiVpnService.error.value = i.getStringExtra("m") ?: ""
            DpiVpnService.engine.value = i.getStringExtra("e") ?: ""
            DpiVpnService.status.value = s
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        ExitInfo.log(this)
        ContextCompat.registerReceiver(
            this, statusReceiver, IntentFilter(DpiVpnService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        syncStatus()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Violet, secondary = Cyan,
                    surface = Bg1, background = Bg1,
                    onSurface = Color.White, onBackground = Color.White
                )
            ) {
                CompositionLocalProvider(LocalContentColor provides Color.White) { Screen() }
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun vpnProcessAlive(): Boolean {
        val am = getSystemService(ActivityManager::class.java)
        return am.runningAppProcesses?.any { it.processName == "$packageName:vpn" } == true
    }

    /** Tünel servisi beklenmedik şekilde ölmüşse bunu fark eder, gerekirse yedek motorla yeniden dener. */
    private fun syncStatus() {
        val f = File(filesDir, "status.txt")
        val saved = try { VpnStatus.valueOf(f.readText().trim()) } catch (_: Exception) { VpnStatus.STOPPED }
        val busy = saved == VpnStatus.CONNECTED || saved == VpnStatus.CONNECTING
        if (busy && !vpnProcessAlive()) {
            AppLog.w("UI: tünel servisi beklenmedik şekilde kapandı")
            ExitInfo.log(this)
            try { f.writeText("STOPPED") } catch (_: Exception) {}
            val tryFlag = File(filesDir, "hev_trying")
            val broken = File(filesDir, "hev_broken")
            if (tryFlag.exists()) {
                try { broken.writeText("1"); tryFlag.delete() } catch (_: Exception) {}
                AppLog.w("hev motoru çöktü → yedek motora geçildi")
            }
            if (broken.exists() && !autoRetried) {
                autoRetried = true
                DpiVpnService.error.value = ""
                DpiVpnService.status.value = VpnStatus.CONNECTING
                startVpn()
            } else {
                DpiVpnService.error.value = "Tünel servisi beklenmedik şekilde kapandı."
                DpiVpnService.status.value = VpnStatus.ERROR
            }
        } else if (busy && saved == VpnStatus.CONNECTED && DpiVpnService.status.value == VpnStatus.STOPPED) {
            DpiVpnService.status.value = VpnStatus.CONNECTED
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
        val p = Prefs(this)
        ContextCompat.startForegroundService(
            this,
            Intent(this, DpiVpnService::class.java)
                .setAction(DpiVpnService.ACTION_START)
                .putExtra(DpiVpnService.EXTRA_ARGS, p.activeArgs())
                .putExtra(DpiVpnService.EXTRA_ONLY, p.onlyDiscord)
        )
    }

    @Composable
    private fun Screen() {
        val prefs = remember { Prefs(applicationContext) }
        val status by DpiVpnService.status.collectAsState()
        val err by DpiVpnService.error.collectAsState()
        val engine by DpiVpnService.engine.collectAsState()
        var preset by remember { mutableStateOf(prefs.presetId) }
        var custom by remember { mutableStateOf(prefs.customArgs) }
        var only by remember { mutableStateOf(prefs.onlyDiscord) }
        var tuning by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf("") }
        var log by remember { mutableStateOf(AppLog.read()) }
        val clip = LocalClipboardManager.current
        val scope = rememberCoroutineScope()
        val idle = status == VpnStatus.STOPPED || status == VpnStatus.ERROR
        val presetName = if (preset == Presets.CUSTOM) "Özel"
        else Presets.all.firstOrNull { it.id == preset }?.name ?: "-"

        LaunchedEffect(Unit) { while (true) { syncStatus(); delay(1500) } }
        LaunchedEffect(status, err, tuning) { log = AppLog.read() }

        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Bg1, Bg2)))) {
            // arka plan ışıltıları
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    Brush.radialGradient(listOf(Violet.copy(alpha = 0.28f), Color.Transparent)),
                    radius = size.width * 0.8f, center = Offset(size.width * 0.1f, size.height * 0.12f)
                )
                drawCircle(
                    Brush.radialGradient(listOf(Cyan.copy(alpha = 0.16f), Color.Transparent)),
                    radius = size.width * 0.7f, center = Offset(size.width * 0.95f, size.height * 0.55f)
                )
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(36.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Shield(48, status == VpnStatus.CONNECTED)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "DPI AŞICI",
                            style = TextStyle(
                                brush = Brush.horizontalGradient(listOf(Cyan, Violet)),
                                fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp
                            )
                        )
                        Text("Ping'siz erişim engeli aşma", color = Muted, fontSize = 12.sp)
                    }
                    StatusPill(status)
                }

                Spacer(Modifier.height(14.dp))
                ConnectButton(status, enabled = !tuning && status != VpnStatus.CONNECTING) { toggle(status) }

                Text(
                    when (status) {
                        VpnStatus.CONNECTED -> "Korumalı: engelli siteler açılabilir"
                        VpnStatus.CONNECTING -> "Bağlanıyor…"
                        VpnStatus.ERROR -> err.ifBlank { "Bir hata oluştu" }
                        VpnStatus.STOPPED -> if (err.isNotBlank()) err else "Bağlanmak için dokun"
                    },
                    color = when (status) {
                        VpnStatus.ERROR -> Red
                        VpnStatus.CONNECTED -> Green
                        else -> Muted
                    },
                    textAlign = TextAlign.Center, fontSize = 14.sp
                )

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoChip("Yöntem", presetName, Modifier.weight(1f))
                    InfoChip("Motor", engine.ifBlank { "—" }, Modifier.weight(1f))
                }

                Spacer(Modifier.height(18.dp))
                SectionCard("Otomatik ayarla", "Discord'a karşı tüm yöntemleri dener, çalışanı seçer.") {
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
                                    progress = "Bulundu: ${p.name} ✓  Şimdi bağlanabilirsin."
                                } else {
                                    progress = "Hiçbir yöntem çalışmadı. Bu operatörde tünel modu gerekebilir."
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(if (tuning) "Deneniyor…" else "Otomatik bul") }
                    if (progress.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(progress, fontSize = 12.sp, color = Cyan)
                    }
                }

                Spacer(Modifier.height(12.dp))
                SectionCard("Yöntem") {
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

                Spacer(Modifier.height(12.dp))
                SectionCard("Kapsam") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sadece Discord")
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
                    Spacer(Modifier.height(12.dp))
                    SectionCard("Hata kaydı") {
                        Text(log, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Muted)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { clip.setText(AnnotatedString(log)) }, shape = RoundedCornerShape(14.dp)) { Text("Kopyala") }
                            OutlinedButton(onClick = { AppLog.clear(); log = "" }, shape = RoundedCornerShape(14.dp)) { Text("Temizle") }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "İpucu: Telefon ayarlarında \"Özel DNS\"i dns.google yaparsan DNS engeline karşı ek koruma sağlar.",
                    fontSize = 11.sp, color = Muted, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, Color(0x22FFFFFF))
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Muted)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(CardBg).padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label.uppercase(), fontSize = 10.sp, color = Muted, letterSpacing = 1.sp)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun StatusPill(status: VpnStatus) {
    val (txt, col) = when (status) {
        VpnStatus.CONNECTED -> "BAĞLI" to Green
        VpnStatus.CONNECTING -> "BAĞLANIYOR" to Cyan
        VpnStatus.ERROR -> "HATA" to Red
        VpnStatus.STOPPED -> "KAPALI" to Muted
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(col))
        Spacer(Modifier.width(7.dp))
        Text(txt, color = col, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun Shield(sizeDp: Int, active: Boolean) {
    val glow = if (active) Green else Violet
    Canvas(Modifier.size(sizeDp.dp)) {
        val w = size.width
        val h = size.height
        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.45f), Color.Transparent)), radius = w / 2)
        val outer = Path().apply {
            moveTo(w * 0.5f, h * 0.14f)
            lineTo(w * 0.82f, h * 0.26f)
            lineTo(w * 0.82f, h * 0.5f)
            cubicTo(w * 0.82f, h * 0.73f, w * 0.67f, h * 0.85f, w * 0.5f, h * 0.93f)
            cubicTo(w * 0.33f, h * 0.85f, w * 0.18f, h * 0.73f, w * 0.18f, h * 0.5f)
            lineTo(w * 0.18f, h * 0.26f)
            close()
        }
        drawPath(outer, Brush.linearGradient(listOf(Cyan, Violet)))
        val inner = Path().apply {
            moveTo(w * 0.5f, h * 0.21f)
            lineTo(w * 0.75f, h * 0.31f)
            lineTo(w * 0.75f, h * 0.5f)
            cubicTo(w * 0.75f, h * 0.68f, w * 0.63f, h * 0.78f, w * 0.5f, h * 0.85f)
            cubicTo(w * 0.37f, h * 0.78f, w * 0.25f, h * 0.68f, w * 0.25f, h * 0.5f)
            lineTo(w * 0.25f, h * 0.31f)
            close()
        }
        drawPath(inner, Color(0xFF0B1230))
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
    val active = connected || busy
    val c1 = if (connected) Green else Violet
    val c2 = if (connected) Cyan else Color(0xFF5B8DEF)
    val tr = rememberInfiniteTransition(label = "rings")
    val p1 by tr.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "r1"
    )
    val p2 by tr.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart, StartOffset(1100)),
        label = "r2"
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val base = size.minDimension * 0.30f
            val maxR = size.minDimension * 0.5f
            drawCircle(Brush.radialGradient(listOf(c1.copy(alpha = 0.30f), Color.Transparent)), radius = maxR)
            if (active) {
                for (p in listOf(p1, p2)) {
                    val r = base + (maxR - base) * p
                    drawCircle(c1.copy(alpha = 0.55f * (1f - p)), radius = r, style = Stroke(width = 3.dp.toPx()))
                }
            } else {
                drawCircle(c1.copy(alpha = 0.25f), radius = base * 1.18f, style = Stroke(width = 2.dp.toPx()))
            }
        }
        Box(
            Modifier.size(156.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(c1, c2)))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(46.dp)) {
                    val sw = 5.dp.toPx()
                    val pad = sw
                    drawArc(
                        color = Color.White, startAngle = -60f, sweepAngle = 300f, useCenter = false,
                        topLeft = Offset(pad, pad + 4.dp.toPx()),
                        size = Size(size.width - pad * 2, size.height - pad * 2 - 4.dp.toPx()),
                        style = Stroke(width = sw, cap = StrokeCap.Round)
                    )
                    drawLine(
                        Color.White, Offset(size.width / 2, pad), Offset(size.width / 2, size.height * 0.48f),
                        strokeWidth = sw, cap = StrokeCap.Round
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        connected -> "BAĞLI"
                        busy -> "Bağlanıyor…"
                        else -> "BAĞLAN"
                    },
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 1.sp
                )
            }
        }
    }
}
