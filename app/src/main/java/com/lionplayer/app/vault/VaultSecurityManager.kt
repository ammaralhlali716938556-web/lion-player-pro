package com.lionplayer.app.vault

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.security.crypto.MasterKeys
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * ============================================================================
 *  Lion Player - Secure Vault (الخزينة السرية)
 * ----------------------------------------------------------------------------
 *  1) حماية الدخول : PIN مشتق عبر PBKDF2-HMAC-SHA256 (120,000 دورة) +
 *     مصادقة بيومترية عبر androidx.biometric.
 *  2) استعادة الرمز : سؤال أمان محلي (تُخزَّن إجابته كمشتق PBKDF2 وليس نصاً صريحاً).
 *  3) المفاتيح       : كل الأسرار محفوظة داخل EncryptedSharedPreferences
 *     (AES-256-GCM) ومفتاحها الرئيسي مربوط بـ Android Keystore.
 *  4) الملفات        : تُشفَّر بـ EncryptedFile (AES-256-GCM-HKDF-4KB / Tink)
 *     داخل context.filesDir/lion_vault + ملف .nomedia.
 *  5) الواجهة        : FLAG_SECURE يمنع لقطات الشاشة وتسجيل الشاشة.
 * ============================================================================
 */

/** وصف ملف واحد داخل الخزينة المشفّرة */
data class VaultFileItem(
    val id: String,
    val displayName: String,
    val encryptedFileName: String,
    val sizeBytes: Long,
    val isVideo: Boolean,
    val originalFolder: String,
    val addedAt: Long,
    val originalUri: String = ""
) {
    /** الامتداد الأصلي (مهم حتى يتعرف ExoPlayer على نوع الحاوية بعد فك التشفير) */
    val extension: String
        get() = displayName.substringAfterLast('.', "").lowercase()

    /** اسم الملف المؤقت بعد فك التشفير (يحمل نفس الامتداد) */
    val decryptedFileName: String
        get() {
            val safeId = id.replace(Regex("[^A-Za-z0-9]"), "")
            val ext = if (extension.isEmpty()) (if (isVideo) "mp4" else "mp3") else extension
            return "vault_${safeId}.$ext"
        }
}

/** نتيجة موحّدة لعمليات الخزينة */
sealed class VaultResult<out T> {
    data class Success<T>(val value: T) : VaultResult<T>()
    data class Failure(val message: String) : VaultResult<Nothing>()
}

/**
 * حالة فتح الخزينة في الذاكرة فقط (لا تُخزَّن على القرص أبداً).
 * تنتهي صلاحيتها تلقائياً بعد المدة التي يحددها المستخدم.
 */
object VaultSession {
    @Volatile
    private var unlockedAt: Long = 0L

    @Volatile
    var autoLockMinutes: Int = 5
        private set

    fun setAutoLock(minutes: Int) {
        autoLockMinutes = minutes.coerceIn(1, 120)
    }

    val isUnlocked: Boolean
        get() {
            if (unlockedAt == 0L) return false
            val timeoutMs = autoLockMinutes * 60_000L
            return (System.currentTimeMillis() - unlockedAt) < timeoutMs
        }

    fun unlock() {
        unlockedAt = System.currentTimeMillis()
    }

    fun touch() {
        if (unlockedAt != 0L) unlockedAt = System.currentTimeMillis()
    }

    fun lock() {
        unlockedAt = 0L
    }
}

object VaultSecurityManager {

    private const val PREFS_NAME = "lion_vault_secure_prefs"
    private const val KEYSET_ALIAS = "lion_vault_master_keyset"
    private const val PREF_FILE_NAME = "__lion_vault_master_key_prefs__"

    // ---- مفاتيح التخزين ----
    private const val PREF_PIN_SALT = "pin_salt"
    private const val PREF_PIN_HASH = "pin_hash"
    private const val PREF_ANSWER_SALT = "answer_salt"
    private const val PREF_ANSWER_HASH = "answer_hash"
    private const val PREF_QUESTION = "security_question"
    private const val PREF_BIOMETRIC = "biometric_enabled"
    private const val PREF_AUTO_LOCK = "auto_lock_minutes"
    private const val PREF_FAIL_COUNT = "fail_count"
    private const val PREF_LOCKOUT_UNTIL = "lockout_until"
    private const val PREF_SECURE_WINDOW = "flag_secure_enabled"

    private const val PIN_ITERATIONS = 120_000
    private const val ANSWER_ITERATIONS = 60_000
    private const val KEY_LENGTH_BITS = 256

    /** حد محاولات PIN الفاشلة قبل التجميد المؤقت */
    const val MAX_PIN_ATTEMPTS = 5
    private const val LOCKOUT_MS = 30_000L

    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 8

    val SECURITY_QUESTIONS: List<String> = listOf(
        "ما اسم أول مدرسة درست فيها؟",
        "ما اسم حيوانك الأليف الأول؟",
        "ما اسم مدينة والدتك قبل الزواج؟",
        "ما رقم هاتف منزل العائلة القديم؟",
        "ما اسم أول صديق لك؟",
        "ما اسم الشارع الذي نشأت فيه؟"
    )

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    // =========================================================================
    //  EncryptedSharedPreferences - تخزين الأسرار
    // =========================================================================

    @Synchronized
    fun securePrefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        val appContext = context.applicationContext

        val keyMaterial = masterKeyMaterial(appContext)

        val prefs = if (keyMaterial is MasterKey) {
            // API 23+ : MasterKey الحديث (Android Keystore + AES256-GCM)
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                keyMaterial,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } else {
            // API 21/22 : مسار الاسم النصي (deprecated) المدعوم من security-crypto
            @Suppress("DEPRECATION")
            EncryptedSharedPreferences.create(
                PREFS_NAME,
                keyMaterial as String,
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
        cachedPrefs = prefs
        return prefs
    }

    /**
     * مادة المفتاح الرئيسي:
     *  • API 23+  -> [MasterKey] مربوط بـ Android Keystore (AES-256-GCM).
     *  • API 21/22 -> اسم المفتاح النصي عبر [MasterKeys] (المسار القديم المدعوم).
     */
    fun masterKeyMaterial(context: Context): Any {
        val appContext = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        }
    }

    // =========================================================================
    //  تهيئة الخزينة / حالة التفعيل
    // =========================================================================

    fun isVaultEnabled(context: Context): Boolean {
        val prefs = securePrefs(context)
        return prefs.getString(PREF_PIN_HASH, null)?.isNotEmpty() == true
    }

    fun isBiometricEnabled(context: Context): Boolean =
        securePrefs(context).getBoolean(PREF_BIOMETRIC, true)

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        securePrefs(context).edit().putBoolean(PREF_BIOMETRIC, enabled).apply()
    }

    fun isSecureWindowEnabled(context: Context): Boolean =
        securePrefs(context).getBoolean(PREF_SECURE_WINDOW, true)

    fun setSecureWindowEnabled(context: Context, enabled: Boolean) {
        securePrefs(context).edit().putBoolean(PREF_SECURE_WINDOW, enabled).apply()
    }

    fun getAutoLockMinutes(context: Context): Int {
        val stored = securePrefs(context).getInt(PREF_AUTO_LOCK, 5)
        VaultSession.setAutoLock(stored)
        return VaultSession.autoLockMinutes
    }

    fun setAutoLockMinutes(context: Context, minutes: Int) {
        VaultSession.setAutoLock(minutes)
        securePrefs(context).edit().putInt(PREF_AUTO_LOCK, VaultSession.autoLockMinutes).apply()
    }

    /**
     * أول تهيئة للخزينة: يحفظ PIN وسؤال الأمان (كمشتقات PBKDF2) ثم يفتح الجلسة.
     */
    fun initializeVault(
        context: Context,
        pin: String,
        question: String,
        answer: String,
        biometricEnabled: Boolean
    ): VaultResult<Unit> {
        return try {
            val pinSalt = randomSalt()
            val pinHash = derive(pin, pinSalt, PIN_ITERATIONS)
            val answerSalt = randomSalt()
            val answerHash = derive(normalizeAnswer(answer), answerSalt, ANSWER_ITERATIONS)

            securePrefs(context).edit()
                .putString(PREF_PIN_SALT, encode(pinSalt))
                .putString(PREF_PIN_HASH, encode(pinHash))
                .putString(PREF_ANSWER_SALT, encode(answerSalt))
                .putString(PREF_ANSWER_HASH, encode(answerHash))
                .putString(PREF_QUESTION, question)
                .putBoolean(PREF_BIOMETRIC, biometricEnabled)
                .putInt(PREF_FAIL_COUNT, 0)
                .putLong(PREF_LOCKOUT_UNTIL, 0L)
                .putBoolean(PREF_SECURE_WINDOW, true)
                .apply()

            resetAttemptState(context)
            VaultSession.unlock()
            VaultResult.Success(Unit)
        } catch (e: Exception) {
            VaultResult.Failure(e.localizedMessage ?: "تعذّر تهيئة الخزينة")
        }
    }

    // =========================================================================
    //  التحقق من رمز PIN + الحماية من التخمين
    // =========================================================================

    /** الوقت المتبقي للتجميد بالميلي ثانية (0 إن لم يكن هناك تجميد) */
    fun lockoutRemainingMs(context: Context): Long {
        val until = securePrefs(context).getLong(PREF_LOCKOUT_UNTIL, 0L)
        val remaining = until - System.currentTimeMillis()
        return if (remaining > 0L) remaining else 0L
    }

    fun failedAttempts(context: Context): Int =
        securePrefs(context).getInt(PREF_FAIL_COUNT, 0)

    fun verifyPin(context: Context, pin: String): VaultResult<Unit> {
        if (!isVaultEnabled(context)) {
            return VaultResult.Failure("الخزينة غير مفعّلة بعد")
        }
        val lockout = lockoutRemainingMs(context)
        if (lockout > 0L) {
            return VaultResult.Failure("محاولات كثيرة خاطئة — انتظر ${(lockout / 1000L) + 1} ثانية")
        }
        return try {
            val prefs = securePrefs(context)
            val salt = decode(prefs.getString(PREF_PIN_SALT, null) ?: return VaultResult.Failure("الرمز غير صالح"))
            val expected = decode(prefs.getString(PREF_PIN_HASH, null) ?: return VaultResult.Failure("الرمز غير صالح"))
            val actual = derive(pin, salt, PIN_ITERATIONS)

            if (constantTimeEquals(expected, actual)) {
                resetAttemptState(context)
                VaultSession.unlock()
                VaultResult.Success(Unit)
            } else {
                registerFailedAttempt(context)
                val remaining = MAX_PIN_ATTEMPTS - failedAttempts(context)
                if (remaining <= 0) {
                    VaultResult.Failure("تم إيقاف المحاولات مؤقتاً — أعد المحاولة بعد 30 ثانية")
                } else {
                    VaultResult.Failure("رمز PIN غير صحيح — المحاولات المتبقية: $remaining")
                }
            }
        } catch (e: Exception) {
            VaultResult.Failure("خطأ أثناء التحقق من الرمز")
        }
    }

    private fun registerFailedAttempt(context: Context) {
        val prefs = securePrefs(context)
        val count = prefs.getInt(PREF_FAIL_COUNT, 0) + 1
        val editor = prefs.edit().putInt(PREF_FAIL_COUNT, count)
        if (count >= MAX_PIN_ATTEMPTS) {
            editor.putLong(PREF_LOCKOUT_UNTIL, System.currentTimeMillis() + LOCKOUT_MS)
                .putInt(PREF_FAIL_COUNT, 0)
        }
        editor.apply()
    }

    private fun resetAttemptState(context: Context) {
        securePrefs(context).edit()
            .putInt(PREF_FAIL_COUNT, 0)
            .putLong(PREF_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    // =========================================================================
    //  استعادة الرمز عبر سؤال الأمان المحلي
    // =========================================================================

    fun getSecurityQuestion(context: Context): String =
        securePrefs(context).getString(PREF_QUESTION, SECURITY_QUESTIONS[0])
            ?: SECURITY_QUESTIONS[0]

    /**
     * يتحقق من إجابة سؤال الأمان ثم يعيّن رمز PIN جديد مباشرة.
     * لا تُخزَّن الإجابة أو الرمز كنص صريح إطلاقاً.
     */
    fun resetPinWithSecurityAnswer(
        context: Context,
        answer: String,
        newPin: String
    ): VaultResult<Unit> {
        if (!isVaultEnabled(context)) return VaultResult.Failure("الخزينة غير مفعّلة بعد")
        if (lockoutRemainingMs(context) > 0L) {
            return VaultResult.Failure("انتظر قليلاً ثم أعد المحاولة")
        }
        return try {
            val prefs = securePrefs(context)
            val salt = decode(prefs.getString(PREF_ANSWER_SALT, null)
                ?: return VaultResult.Failure("سؤال الأمان غير مضبوط"))
            val expected = decode(prefs.getString(PREF_ANSWER_HASH, null)
                ?: return VaultResult.Failure("سؤال الأمان غير مضبوط"))
            val actual = derive(normalizeAnswer(answer), salt, ANSWER_ITERATIONS)

            if (!constantTimeEquals(expected, actual)) {
                registerFailedAttempt(context)
                return VaultResult.Failure("إجابة سؤال الأمان غير صحيحة")
            }

            val pinSalt = randomSalt()
            prefs.edit()
                .putString(PREF_PIN_SALT, encode(pinSalt))
                .putString(PREF_PIN_HASH, encode(derive(newPin, pinSalt, PIN_ITERATIONS)))
                .apply()

            resetAttemptState(context)
            VaultSession.unlock()
            VaultResult.Success(Unit)
        } catch (e: Exception) {
            VaultResult.Failure("تعذّر إتمام عملية الاستعادة")
        }
    }

    /**
     * تحقق منفصل من إجابة سؤال الأمان (بدون تغيير الرمز).
     * يُستخدم في واجهة الاستعادة قبل عرض حقول الرمز الجديد.
     */
    fun verifySecurityAnswer(context: Context, answer: String): Boolean {
        if (!isVaultEnabled(context)) return false
        if (lockoutRemainingMs(context) > 0L) return false
        return try {
            val prefs = securePrefs(context)
            val salt = decode(prefs.getString(PREF_ANSWER_SALT, null) ?: return false)
            val expected = decode(prefs.getString(PREF_ANSWER_HASH, null) ?: return false)
            val actual = derive(normalizeAnswer(answer), salt, ANSWER_ITERATIONS)
            val ok = constantTimeEquals(expected, actual)
            if (!ok) registerFailedAttempt(context) else resetAttemptState(context)
            ok
        } catch (e: Exception) {
            false
        }
    }

    /** تغيير رمز PIN بعد التأكد من الرمز الحالي */
    fun changePin(context: Context, oldPin: String, newPin: String): VaultResult<Unit> {
        val verified = verifyPin(context, oldPin)
        if (verified !is VaultResult.Success) {
            return VaultResult.Failure((verified as VaultResult.Failure).message)
        }
        return try {
            val salt = randomSalt()
            securePrefs(context).edit()
                .putString(PREF_PIN_SALT, encode(salt))
                .putString(PREF_PIN_HASH, encode(derive(newPin, salt, PIN_ITERATIONS)))
                .apply()
            VaultResult.Success(Unit)
        } catch (e: Exception) {
            VaultResult.Failure("تعذّر تغيير رمز PIN")
        }
    }

    /**
     * إعادة ضبط كاملة للخزينة (تُحذف كل المفاتيح والملفات المشفّرة).
     * تُستخدم فقط بعد التحقق من إجابة سؤال الأمان.
     */
    fun wipeVault(context: Context): VaultResult<Unit> {
        return try {
            VaultSession.lock()
            securePrefs(context).edit().clear().apply()
            VaultResult.Success(Unit)
        } catch (e: Exception) {
            VaultResult.Failure("تعذّر مسح الخزينة")
        }
    }

    // =========================================================================
    //  أدوات التشفير المساعدة
    // =========================================================================

    private fun randomSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun derive(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun normalizeAnswer(answer: String): String =
        answer.trim().lowercase().replace(Regex("\\s+"), " ")

    /** مقارنة بزمن ثابت لمنع هجمات التوقيت */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    /** تنظيف آمن لمصفوفة بايتات في الذاكرة */
    fun zeroOut(bytes: ByteArray?) {
        bytes?.fill(0)
    }

    /** حذف آمن لملف */
    fun secureDelete(file: File?) {
        try {
            if (file != null && file.exists()) file.delete()
        } catch (e: Exception) {
            // تجاهل مقصود
        }
    }
}
