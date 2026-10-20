package com.lionplayer.app.vault

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.lionplayer.app.R

/**
 * ============================================================================
 *  بوابة الدخول إلى الخزينة السرية
 * ----------------------------------------------------------------------------
 *  • رمز PIN (4–8 أرقام) أو مصادقة بيومترية عبر androidx.biometric.
 *  • استعادة عبر سؤال أمان محلي (إجابته مخزّنة كمشتق PBKDF2).
 *  • FLAG_SECURE: لا لقطات شاشة ولا تسجيل شاشة ولا ظهور في قائمة التطبيقات الحديثة.
 *  • تجميد 30 ثانية بعد 5 محاولات خاطئة.
 * ============================================================================
 */
class VaultLockActivity : FragmentActivity() {

    private lateinit var etPin: EditText
    private lateinit var tvMessage: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var pinDots: LinearLayout
    private lateinit var btnUnlock: View
    private lateinit var btnBiometric: View
    private lateinit var btnForgot: View

    private var lockoutTimer: CountDownTimer? = null
    private var isVerifying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ===== حماية الشاشة: منع لقطة الشاشة/تسجيل الشاشة داخل الخزينة =====
        applySecureFlag()

        setContentView(R.layout.activity_vault_lock)

        etPin = findViewById(R.id.etPin)
        tvMessage = findViewById(R.id.tvLockMessage)
        tvTitle = findViewById(R.id.tvLockTitle)
        tvSubtitle = findViewById(R.id.tvLockSubtitle)
        pinDots = findViewById(R.id.pinDotsContainer)
        btnUnlock = findViewById(R.id.btnUnlock)
        btnBiometric = findViewById(R.id.btnBiometric)
        btnForgot = findViewById(R.id.btnForgotPin)

        // إن لم تُفعَّل الخزينة بعد -> شاشة الإعداد
        if (!VaultSecurityManager.isVaultEnabled(this)) {
            startActivity(Intent(this, VaultSetupActivity::class.java))
            finish()
            return
        }

        VaultSecurityManager.getAutoLockMinutes(this)

        btnUnlock.setOnClickListener { attemptUnlock() }
        btnForgot.setOnClickListener { showRecoveryFlow() }
        findViewById<View>(R.id.btnCancelLock).setOnClickListener { cancelAndExit() }

        // إظهار زر البصمة فقط عند توفر عتاد مدعوم وتفعيل المستخدم له
        val biometricAvailable = VaultSecurityManager.isBiometricEnabled(this) &&
            BiometricVaultHelper.isBiometricHardwareAvailable(this)
        btnBiometric.visibility = if (biometricAvailable) View.VISIBLE else View.GONE
        btnBiometric.setOnClickListener { startBiometricPrompt() }

        buildPinDots(VaultSecurityManager.MIN_PIN_LENGTH)

        etPin.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                updatePinDots(text.length)
                if (text.length >= VaultSecurityManager.MIN_PIN_LENGTH && !isVerifying) {
                    attemptUnlock()
                }
            }
        })
    }

    private fun applySecureFlag() {
        if (VaultSecurityManager.isSecureWindowEnabled(this)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!VaultSecurityManager.isVaultEnabled(this)) {
            startActivity(Intent(this, VaultSetupActivity::class.java))
            finish()
            return
        }
        refreshLockoutState()
        // فتح تلقائي بالبصمة عند الدخول
        if (VaultSecurityManager.isBiometricEnabled(this) &&
            BiometricVaultHelper.isBiometricHardwareAvailable(this)
        ) {
            startBiometricPrompt()
        }
    }

    override fun onStop() {
        super.onStop()
        lockoutTimer?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        lockoutTimer?.cancel()
        lockoutTimer = null
    }

    // =========================================================================
    //  مؤشرات PIN
    // =========================================================================

    private fun buildPinDots(count: Int) {
        pinDots.removeAllViews()
        val size = (16 * resources.displayMetrics.density).toInt()
        val margin = (8 * resources.displayMetrics.density).toInt()
        for (i in 0 until VaultSecurityManager.MAX_PIN_LENGTH) {
            val dot = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = margin
                    marginEnd = margin
                }
                setImageResource(R.drawable.bg_vault_pin_dot)
            }
            pinDots.addView(dot)
        }
        updatePinDots(0)
    }

    private fun updatePinDots(filled: Int) {
        for (i in 0 until pinDots.childCount) {
            val child = pinDots.getChildAt(i) as? ImageView ?: continue
            val visible = i < VaultSecurityManager.MAX_PIN_LENGTH
            child.visibility = if (visible) View.VISIBLE else View.GONE
            child.setImageResource(
                if (i < filled) R.drawable.bg_vault_pin_dot_filled else R.drawable.bg_vault_pin_dot
            )
        }
    }

    // =========================================================================
    //  فتح القفل
    // =========================================================================

    private fun attemptUnlock() {
        if (isVerifying) return
        val pin = etPin.text?.toString().orEmpty()
        if (pin.length < VaultSecurityManager.MIN_PIN_LENGTH) {
            showMessage("أدخل رمز PIN من ${VaultSecurityManager.MIN_PIN_LENGTH} إلى ${VaultSecurityManager.MAX_PIN_LENGTH} أرقام", true)
            return
        }
        val lockout = VaultSecurityManager.lockoutRemainingMs(this)
        if (lockout > 0L) {
            startLockoutCountdown(lockout)
            return
        }

        isVerifying = true
        when (val result = VaultSecurityManager.verifyPin(this, pin)) {
            is VaultResult.Success -> onUnlocked()
            is VaultResult.Failure -> {
                isVerifying = false
                etPin.setText("")
                updatePinDots(0)
                showMessage(result.message, true)
                shakePinField()
                refreshLockoutState()
            }
        }
    }

    private fun onUnlocked() {
        isVerifying = false
        etPin.setText("")
        showMessage("", false)
        Toast.makeText(this, "تم فتح الخزينة السرية 🔓", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, VaultActivity::class.java))
        finish()
    }

    private fun shakePinField() {
        etPin.animate()
            .translationX(18f).setDuration(60)
            .withEndAction {
                etPin.animate().translationX(-18f).setDuration(60)
                    .withEndAction { etPin.animate().translationX(0f).setDuration(60).start() }
                    .start()
            }
            .start()
    }

    // =========================================================================
    //  المصادقة البيومترية
    // =========================================================================

    private fun startBiometricPrompt() {
        BiometricVaultHelper.prompt(
            activity = this,
            title = getString(R.string.vault_biometric_title),
            subtitle = getString(R.string.vault_biometric_subtitle),
            negativeButtonText = getString(R.string.vault_biometric_negative),
            onSuccess = { onUnlocked() },
            onError = { msg -> showMessage(msg, true) },
            onFallback = { etPin.requestFocus() }
        )
    }

    // =========================================================================
    //  الاستعادة عند نسيان الرمز (سؤال أمان محلي)
    // =========================================================================

    private fun showRecoveryFlow() {
        val question = VaultSecurityManager.getSecurityQuestion(this)

        val answerInput = EditText(this).apply {
            hint = "إجابة سؤال الأمان"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this, R.style.VaultTheme)
            .setTitle(R.string.vault_recovery_title)
            .setMessage("$question\n\n${getString(R.string.vault_recovery_subtitle)}")
            .setView(answerInput)
            .setPositiveButton("تحقق") { _, _ ->
                verifyAnswerAndReset(answerInput.text?.toString().orEmpty())
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun verifyAnswerAndReset(answer: String) {
        if (answer.isBlank()) {
            showMessage("أدخل إجابة سؤال الأمان", true)
            return
        }
        // تحقق أولي باستخدام رمز فارغ غير صالح: نستخدم دالة مخصصة أدناه
        val newPinInput = EditText(this).apply {
            hint = getString(R.string.vault_new_pin)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }

        // نؤجل كتابة الرمز الجديد حتى يتم التحقق من الإجابة (يتم داخل resetPinWithSecurityAnswer)
        val confirmInput = EditText(this).apply {
            hint = getString(R.string.vault_confirm_pin)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
            addView(newPinInput)
            addView(confirmInput)
        }

        // التحقق من الإجابة أولاً (باستخدام رمز مؤقت مكوّن من 4 أصفار لن يُحفظ إن فشلت الإجابة)
        val probe = VaultSecurityManager.verifySecurityAnswer(this, answer)
        if (!probe) {
            showMessage("إجابة سؤال الأمان غير صحيحة", true)
            return
        }

        AlertDialog.Builder(this, R.style.VaultTheme)
            .setTitle(R.string.vault_new_pin)
            .setMessage("تم التحقق من إجابة سؤال الأمان ✔ — عيّن رمز PIN جديداً")
            .setView(container)
            .setPositiveButton(R.string.vault_reset_pin) { _, _ ->
                val pin = newPinInput.text?.toString().orEmpty()
                val confirm = confirmInput.text?.toString().orEmpty()
                if (!isValidPin(pin)) {
                    showMessage("رمز PIN يجب أن يكون من ${VaultSecurityManager.MIN_PIN_LENGTH} إلى ${VaultSecurityManager.MAX_PIN_LENGTH} أرقام", true)
                    return@setPositiveButton
                }
                if (pin != confirm) {
                    showMessage("رمزا PIN غير متطابقين", true)
                    return@setPositiveButton
                }
                when (val result = VaultSecurityManager.resetPinWithSecurityAnswer(this, answer, pin)) {
                    is VaultResult.Success -> {
                        showMessage("", false)
                        Toast.makeText(this, "تم تعيين رمز PIN جديد بنجاح ✔", Toast.LENGTH_LONG).show()
                        onUnlocked()
                    }
                    is VaultResult.Failure -> showMessage(result.message, true)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun isValidPin(pin: String): Boolean =
        pin.length in VaultSecurityManager.MIN_PIN_LENGTH..VaultSecurityManager.MAX_PIN_LENGTH &&
            pin.all { it.isDigit() }

    // =========================================================================
    //  التجميد المؤقت بعد المحاولات الفاشلة
    // =========================================================================

    private fun refreshLockoutState() {
        val remaining = VaultSecurityManager.lockoutRemainingMs(this)
        if (remaining > 0L) startLockoutCountdown(remaining)
        else {
            lockoutTimer?.cancel()
            lockoutTimer = null
            btnUnlock.isEnabled = true
            etPin.isEnabled = true
        }
    }

    private fun startLockoutCountdown(remainingMs: Long) {
        lockoutTimer?.cancel()
        btnUnlock.isEnabled = false
        etPin.isEnabled = false
        lockoutTimer = object : CountDownTimer(remainingMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000L) + 1
                showMessage("محاولات كثيرة خاطئة — أعد المحاولة بعد $seconds ثانية", true)
            }

            override fun onFinish() {
                btnUnlock.isEnabled = true
                etPin.isEnabled = true
                showMessage("", false)
            }
        }.start()
    }

    private fun showMessage(message: String, isError: Boolean) {
        tvMessage.text = message
        tvMessage.setTextColor(
            ContextCompat.getColor(
                this,
                if (isError) R.color.vault_error else R.color.vault_accent
            )
        )
    }

    private fun cancelAndExit() {
        VaultSession.lock()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        cancelAndExit()
    }
}
