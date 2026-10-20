package com.lionplayer.app.vault

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.button.MaterialButton
import com.lionplayer.app.R

/**
 * ============================================================================
 *  الإعداد الأول للخزينة السرية
 * ----------------------------------------------------------------------------
 *  • اختيار رمز PIN (4–8 أرقام).
 *  • سؤال أمان محلي + إجابته (تُخزَّن كمشتق PBKDF2 داخل EncryptedSharedPreferences).
 *  • تفعيل/تعطيل فتح الخزينة بالبصمة.
 *  • FLAG_SECURE مفعّل أيضاً على هذه الشاشة.
 * ============================================================================
 */
class VaultSetupActivity : AppCompatActivity() {

    private lateinit var etPin: EditText
    private lateinit var etPinConfirm: EditText
    private lateinit var etAnswer: EditText
    private lateinit var spinnerQuestion: Spinner
    private lateinit var switchBiometric: MaterialSwitch
    private lateinit var tvInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureFlag()
        setContentView(R.layout.activity_vault_setup)

        etPin = findViewById(R.id.etSetupPin)
        etPinConfirm = findViewById(R.id.etSetupPinConfirm)
        etAnswer = findViewById(R.id.etSetupAnswer)
        spinnerQuestion = findViewById(R.id.spinnerQuestion)
        switchBiometric = findViewById(R.id.switchEnableBiometric)
        tvInfo = findViewById(R.id.tvSetupInfo)

        spinnerQuestion.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            VaultSecurityManager.SECURITY_QUESTIONS
        )

        // البصمة مُفعّلة افتراضياً فقط عند توفر العتاد
        val hasBiometric = BiometricVaultHelper.isBiometricHardwareAvailable(this)
        switchBiometric.isChecked = hasBiometric
        if (!hasBiometric) {
            switchBiometric.isEnabled = false
            switchBiometric.text = "البصمة غير متاحة على هذا الجهاز (سيُستخدم رمز PIN)"
        }

        tvInfo.text = "الملفات تُشفَّر بـ AES-256-GCM داخل المجلد المعزول:\n" +
            VaultManager.vaultPath(this) +
            "\nويُضاف ملف ‎.nomedia لضمان عدم ظهورها في معرض الوسائط أو أي مشغل آخر."

        findViewById<MaterialButton>(R.id.btnSaveVault).setOnClickListener { saveVault() }
    }

    private fun applySecureFlag() {
        if (VaultSecurityManager.isSecureWindowEnabled(this)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
    }

    private fun saveVault() {
        val pin = etPin.text?.toString().orEmpty()
        val confirm = etPinConfirm.text?.toString().orEmpty()
        val answer = etAnswer.text?.toString().orEmpty().trim()
        val question = VaultSecurityManager.SECURITY_QUESTIONS.getOrElse(spinnerQuestion.selectedItemPosition) {
            VaultSecurityManager.SECURITY_QUESTIONS[0]
        }

        if (pin.length < VaultSecurityManager.MIN_PIN_LENGTH ||
            pin.length > VaultSecurityManager.MAX_PIN_LENGTH
        ) {
            toast("رمز PIN يجب أن يكون من ${VaultSecurityManager.MIN_PIN_LENGTH} إلى ${VaultSecurityManager.MAX_PIN_LENGTH} أرقام")
            return
        }
        if (pin != confirm) {
            toast("رمزا PIN غير متطابقين")
            return
        }
        if (answer.length < 2) {
            toast("أدخل إجابة سؤال الأمان (تُستخدم لاستعادة الرمز)")
            return
        }

        when (val result = VaultSecurityManager.initializeVault(
            context = this,
            pin = pin,
            question = question,
            answer = answer,
            biometricEnabled = switchBiometric.isChecked && switchBiometric.isEnabled
        )) {
            is VaultResult.Success -> {
                // تجهيز المجلد المعزول + ملف .nomedia مباشرة
                VaultManager.ensureNoMedia(VaultManager.vaultDir(this))
                VaultManager.ensureNoMedia(VaultManager.decryptCacheDir(this))
                toast("تم تفعيل الخزينة السرية 🔐")
                startActivity(Intent(this, VaultActivity::class.java))
                finish()
            }
            is VaultResult.Failure -> toast(result.message)
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
