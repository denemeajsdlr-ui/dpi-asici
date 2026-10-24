package com.dpibypass

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (it.resultCode == RESULT_OK) startVpn() }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
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
        val scope = rememberCoroutineScope()
        val idle = status == VpnStatus.STOPPED || status == VpnStatus.ERROR

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(28.dp))
            Text("DPI Aşıcı", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { toggle(status) },
                enabled = !tuning && status != VpnStatus.CONNECTING,
                shape = CircleShape,
                modifier = Modifier.size(170.dp)
            ) {
                Text(
                    when (status) {
                        VpnStatus.CONNECTED -> "BAĞLI\nKapatmak için dokun"
                        VpnStatus.CONNECTING -> "Bağlanıyor…"
                        else -> "BAĞLAN"
                    },
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                when (status) {
                    VpnStatus.CONNECTED -> "Korumalı: engelli siteler açılabilir"
                    VpnStatus.CONNECTING -> "Bağlanıyor…"
                    VpnStatus.ERROR -> err
                    VpnStatus.STOPPED -> "Kapalı"
                },
                color = if (status == VpnStatus.ERROR) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(28.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Otomatik ayarla", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Discord'a karşı tüm yöntemleri dener, senin internetinde çalışanı seçer.",
                        style = MaterialTheme.typography.bodySmall
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
                    if (progress.isNotEmpty()) Text(progress, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
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
                                if (args.isNotEmpty()) Text(args, style = MaterialTheme.typography.bodySmall)
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

            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sadece Discord", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Kapalıysa tüm uygulamalar ve tarayıcı korunur.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = only,
                        enabled = idle && !tuning,
                        onCheckedChange = { only = it; prefs.onlyDiscord = it }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "İpucu: Telefon ayarlarında \"Özel DNS\"i dns.google veya one.one.one.one yaparsan " +
                    "DNS engeline karşı ek koruma sağlar.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
