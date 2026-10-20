package com.lionplayer.app.vault

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * ============================================================================
 *  المصادقة البيومترية للخزينة عبر androidx.biometric:biometric
 * ----------------------------------------------------------------------------
 *  • BIOMETRIC_STRONG  : بصمة إصبع (أو قزحية) مصنّفة Class 3.
 *  • DEVICE_CREDENTIAL : رمز قفل الجهاز كبديل عند فشل البصمة.
 *  • إن لم يكن للجهاز مستشعر بصمة، يُخفى زر البصمة ويُعتمد رمز PIN فقط.
 * ============================================================================
 */
object BiometricVaultHelper {

    /** هل الجهاز يدعم مصادقة بيومترية قوية؟ */
    fun isBiometricHardwareAvailable(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun biometricStatus(context: Context): Int =
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

    /**
     * يُظهر نافذة المصادقة البيومترية.
     * @param onSuccess يُستدعى عند نجاح المصادقة (يُفتح القفل).
     * @param onError   يُستدعى عند الخطأ مع رسالة قابلة للعرض.
     * @param onFallback يُستدعى عند اختيار المستخدم "استخدام رمز PIN".
     */
    fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButtonText: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onFallback: () -> Unit = {}
    ) {
        if (!isBiometricHardwareAvailable(activity)) {
            onError("المصادقة البيومترية غير متاحة على هذا الجهاز — استخدم رمز PIN")
            onFallback()
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                // نجاح المصادقة = فتح جلسة الخزينة
                VaultSession.unlock()
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED -> onFallback()

                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                        onError("تم قفل المستشعر مؤقتاً — استخدم رمز PIN ثم أعد المحاولة")

                    BiometricPrompt.ERROR_NO_BIOMETRICS,
                    BiometricPrompt.ERROR_HW_NOT_PRESENT -> {
                        onError("لا توجد بصمة مسجّلة على الجهاز — استخدم رمز PIN")
                        onFallback()
                    }

                    else -> onError(errString.toString())
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onError("لم يتم التعرّف على البصمة — أعد المحاولة")
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .setConfirmationRequired(false)
            .build()

        // ملاحظة: عند استخدام DEVICE_CREDENTIAL لا يُسمح بزر سلبي (Negative Button)
        runCatching { biometricPrompt.authenticate(promptInfo) }
            .onFailure {
                // بعض الأجهزة ترفض DEVICE_CREDENTIAL معاً، نعيد المحاولة بالبصمة فقط + زر PIN
                val fallbackInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setNegativeButtonText(negativeButtonText)
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setConfirmationRequired(false)
                    .build()
                runCatching { biometricPrompt.authenticate(fallbackInfo) }
                    .onFailure { onFallback() }
            }
    }
}
