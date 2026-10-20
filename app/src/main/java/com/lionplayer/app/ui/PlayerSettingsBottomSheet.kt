package com.lionplayer.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.lionplayer.app.LionPlayerApp
import com.lionplayer.app.player.LionPlayerManager
import com.lionplayer.app.vault.VaultLockActivity
import com.lionplayer.app.vault.VaultSecurityManager
import com.lionplayer.app.vault.VaultSession

/** نافذة إعدادات المشغل والتطبيق (تشمل قسم الخزينة السرية) */
object PlayerSettingsBottomSheet {

    fun show(
        activity: Activity,
        onEqualizerClick: (() -> Unit)? = null,
        onVolumeBoostChanged: ((Boolean) -> Unit)? = null,
        onSettingChanged: (() -> Unit)? = null
    ) {
        val prefs = activity.getSharedPreferences("lion_player_prefs", Context.MODE_PRIVATE)
        val builder = AlertDialog.Builder(activity)
        builder.setTitle("إعدادات المشغل والتطبيق")

        val typedValue = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        val primaryTextColor = if (typedValue.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(activity, typedValue.resourceId)
        } else {
            typedValue.data
        }

        val scroll = ScrollView(activity)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        fun createHeader(title: String): TextView {
            return TextView(activity).apply {
                text = title
                setTextColor(Color.parseColor("#FF9800"))
                textSize = 14f
                paint.isFakeBoldText = true
                setPadding(0, 16, 0, 8)
            }
        }

        // ============================ المظهر ============================
        layout.addView(createHeader("مظهر التطبيق (السمة):"))
        val themeModes = arrayOf("dark", "light", "system")
        val themeLabels = arrayOf("داكن", "فاتح", "النظام")
        var currentTheme = prefs.getString("theme_mode", "dark") ?: "dark"
        val themeSpinner = Spinner(activity)
        themeSpinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, themeLabels)
        themeSpinner.setSelection(themeModes.indexOf(currentTheme).coerceAtLeast(0))

        var themeInit = true
        themeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                if (themeInit) {
                    themeInit = false
                    return
                }
                val selectedMode = themeModes[position]
                if (currentTheme != selectedMode) {
                    currentTheme = selectedMode
                    prefs.edit().putString("theme_mode", selectedMode).apply()
                    LionPlayerApp.applyTheme(selectedMode)
                }
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        layout.addView(themeSpinner)

        // ============================ الصوت ============================
        layout.addView(createHeader("الصوت والمؤثرات:"))
        val switchBoost = Switch(activity).apply {
            text = "مضاعفة الصوت حتى 200% (تعزيز نقي)"
            isChecked = prefs.getBoolean("volume_boost_enabled", false)
            setTextColor(primaryTextColor)
            setPadding(0, 8, 0, 8)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("volume_boost_enabled", isChecked).apply()
                onVolumeBoostChanged?.invoke(isChecked)
                onSettingChanged?.invoke()
                val msg = if (isChecked) "تم تفعيل مضاعفة الصوت حتى 200%" else "تم إيقاف مضاعفة الصوت"
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(switchBoost)

        if (onEqualizerClick != null) {
            val tvEq = TextView(activity).apply {
                text = "فتح معادل الصوت والمؤثرات (Equalizer) ❯"
                setTextColor(Color.parseColor("#FF9800"))
                textSize = 14f
                setPadding(0, 12, 0, 8)
                setOnClickListener { onEqualizerClick.invoke() }
            }
            layout.addView(tvEq)
        }

        // ============================ سرعة الضغط المطول ============================
        layout.addView(createHeader("سرعة الضغط المطول للتسريع:"))
        val speedLabels = arrayOf("1.25x", "1.5x", "1.75x", "2.0x")
        val speedValues = floatArrayOf(1.25f, 1.5f, 1.75f, 2.0f)
        val speedSpinner = Spinner(activity)
        speedSpinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, speedLabels)
        speedSpinner.setSelection(prefs.getInt("long_press_speed_idx", 3).coerceIn(0, 3))

        var speedInit = true
        speedSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                if (speedInit) {
                    speedInit = false
                    return
                }
                prefs.edit()
                    .putInt("long_press_speed_idx", position)
                    .putFloat("long_press_speed", speedValues[position])
                    .putString("long_press_speed_str", speedLabels[position])
                    .apply()
                onSettingChanged?.invoke()
                Toast.makeText(activity, "سرعة التسريع بالضغط: ${speedLabels[position]}", Toast.LENGTH_SHORT).show()
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        layout.addView(speedSpinner)

        // ============================ استئناف التشغيل ============================
        layout.addView(createHeader("استئناف تشغيل الفيديو:"))
        val resumeLabels = arrayOf(
            "الاستئناف من حيث توقف المقطع تلقائياً",
            "البدء من بداية المقطع دائماً",
            "السؤال قبل اتخاذ القرار عند كل تشغيل"
        )
        val resumeSpinner = Spinner(activity)
        resumeSpinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, resumeLabels)
        resumeSpinner.setSelection(prefs.getInt("resume_playback_mode", 0).coerceIn(0, 2))

        var resumeInit = true
        resumeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                if (resumeInit) {
                    resumeInit = false
                    return
                }
                prefs.edit().putInt("resume_playback_mode", position).apply()
                onSettingChanged?.invoke()
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        layout.addView(resumeSpinner)

        // ============================ تفضيلات إضافية ============================
        layout.addView(createHeader("تفضيلات إضافية:"))
        val switchBt = Switch(activity).apply {
            text = "التحكم عبر سماعة البلوتوث"
            isChecked = prefs.getBoolean("bluetooth_headset_control", true)
            setTextColor(primaryTextColor)
            setPadding(0, 8, 0, 8)
            setOnCheckedChangeListener { _, isChecked ->
                LionPlayerManager.bluetoothControlEnabled = isChecked
                prefs.edit().putBoolean("bluetooth_headset_control", isChecked).apply()
                onSettingChanged?.invoke()
            }
        }
        layout.addView(switchBt)

        val switchLyrics = Switch(activity).apply {
            text = "إظهار كلمات الأغاني في الصوتيات"
            isChecked = prefs.getBoolean("show_lyrics", true)
            setTextColor(primaryTextColor)
            setPadding(0, 8, 0, 8)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("show_lyrics", isChecked).apply()
                onSettingChanged?.invoke()
            }
        }
        layout.addView(switchLyrics)

        val switchExtensions = Switch(activity).apply {
            text = "إظهار امتداد الملفات في الأسماء"
            isChecked = prefs.getBoolean("show_extensions", false)
            setTextColor(primaryTextColor)
            setPadding(0, 8, 0, 8)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("show_extensions", isChecked).apply()
                onSettingChanged?.invoke()
            }
        }
        layout.addView(switchExtensions)

        // ============================ الخزينة السرية ============================
        layout.addView(createHeader("🔐 الخزينة السرية:"))

        val switchSecureWindow = Switch(activity).apply {
            text = "منع لقطات الشاشة وتسجيل الشاشة داخل الخزينة (FLAG_SECURE)"
            isChecked = VaultSecurityManager.isSecureWindowEnabled(activity)
            setTextColor(primaryTextColor)
            setPadding(0, 8, 0, 8)
            setOnCheckedChangeListener { _, isChecked ->
                VaultSecurityManager.setSecureWindowEnabled(activity, isChecked)
                Toast.makeText(
                    activity,
                    if (isChecked) "تم تفعيل حماية الشاشة داخل الخزينة" else "تم إيقاف حماية الشاشة (غير موصى به)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        layout.addView(switchSecureWindow)

        val switchBiometric = Switch(activity).apply {
            text = "فتح الخزينة ببصمة الإصبع"
            isChecked = VaultSecurityManager.isBiometricEnabled(activity)
            setTextColor(primaryTextColor)
            setPadding(0, 8, 0, 8)
            setOnCheckedChangeListener { _, isChecked ->
                VaultSecurityManager.setBiometricEnabled(activity, isChecked)
            }
        }
        layout.addView(switchBiometric)

        val tvVaultInfo = TextView(activity).apply {
            text = "حالة الخزينة: " +
                (if (VaultSecurityManager.isVaultEnabled(activity)) "مفعّلة 🔐" else "غير مفعّلة") +
                "\nالقفل التلقائي بعد ${VaultSession.autoLockMinutes} دقيقة" +
                "\nفتح الخزينة ❯"
            setTextColor(Color.parseColor("#FF9800"))
            textSize = 13f
            setPadding(0, 16, 0, 8)
            setOnClickListener {
                val intent = Intent(activity, VaultLockActivity::class.java)
                activity.startActivity(intent)
            }
        }
        layout.addView(tvVaultInfo)

        scroll.addView(layout)
        builder.setView(scroll)
        builder.setPositiveButton("تم", null)
        builder.show()
    }
}
