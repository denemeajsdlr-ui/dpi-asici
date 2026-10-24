package com.dpibypass

import android.content.Context

data class Preset(val id: String, val name: String, val args: String)

object Presets {
    val all = listOf(
        Preset("split", "Parçala (Split)", "--split 1+s"),
        Preset("disorder", "Sırasız gönder (Disorder)", "--disorder 1+s"),
        Preset("split_disorder", "Split + Disorder", "--split 1+s --disorder 3+s"),
        Preset("tlsrec", "TLS kaydını böl", "--tlsrec 1+s --split 1+s"),
        Preset("fake", "Sahte paket (Fake)", "--fake -1 --ttl 8 --split 1+s"),
        Preset("oob", "OOB verisi", "--oob 1+s"),
        Preset("aggressive", "Agresif", "--tlsrec 1+s --disorder 1 --split 2+s --fake -1 --ttl 6"),
    )
    const val CUSTOM = "custom"
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("dpi_prefs", Context.MODE_PRIVATE)

    var presetId: String
        get() = sp.getString("preset", "split_disorder") ?: "split_disorder"
        set(v) = sp.edit().putString("preset", v).apply()

    var customArgs: String
        get() = sp.getString("custom", "") ?: ""
        set(v) = sp.edit().putString("custom", v).apply()

    var onlyDiscord: Boolean
        get() = sp.getBoolean("only_discord", false)
        set(v) = sp.edit().putBoolean("only_discord", v).apply()

    fun activeArgs(): String =
        if (presetId == Presets.CUSTOM) customArgs
        else Presets.all.firstOrNull { it.id == presetId }?.args ?: ""
}
