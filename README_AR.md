# Lion Player — نسخة "الخزينة السرية" (Secure Vault)

مشغل وسائط أندرويد (فيديو + صوتيات) مبني على **Media3/ExoPlayer** مع إضافة ميزة
**الخزينة السرية**: إخفاء وتشفير مقاطع الفيديو والصوت داخل مجلد معزول خاص بالتطبيق.

- `applicationId` : `com.lionplayer.app`
- `versionName` : `2.0.0-vault` — `minSdk 21` — `targetSdk 34` — JDK 17 — AGP 8.2.2 — Gradle 8.5

---

## 1) ما الذي تمت إضافته

### 🔐 1. حماية الدخول والتحقق البيومتري
| العنصر | التنفيذ |
|---|---|
| شاشة القفل | `vault/VaultLockActivity.kt` + `res/layout/activity_vault_lock.xml` |
| رمز PIN | 4–8 أرقام، يُخزَّن كمشتق **PBKDF2-HMAC-SHA256 / 120,000 دورة** مع Salt عشوائي 16 بايت |
| البصمة | `vault/BiometricVaultHelper.kt` عبر `androidx.biometric:biometric:1.1.0` (`BIOMETRIC_STRONG` + `DEVICE_CREDENTIAL`) |
| استعادة الرمز | سؤال أمان **محلي** (6 أسئلة جاهزة) — الإجابة تُخزَّن كمشتق PBKDF2 (60,000 دورة) وليس نصاً |
| مضاد التخمين | تجميد 30 ثانية بعد 5 محاولات خاطئة + مقارنة بزمن ثابت (Constant-Time) ضد هجمات التوقيت |
| جلسة الذاكرة | `VaultSession` — فتح مؤقت (1/5/10/30/60 دقيقة) لا يُكتب على القرص أبداً |
| الإعداد الأول | `vault/VaultSetupActivity.kt` (PIN + سؤال الأمان + تفعيل البصمة) |

### 🗄️ 2. إخفاء وتشفير الملفات
| العنصر | التنفيذ |
|---|---|
| المجلد المعزول | `context.filesDir/lion_vault/` — لا يصله أي تطبيق آخر ولا يظهر في إدارة الملفات |
| التشفير | `EncryptedFile` من `androidx.security:security-crypto:1.1.0` = **AES-256-GCM-HKDF-4KB** (Tink) |
| المفتاح | `MasterKey` (AES256_GCM) مربوط بـ **Android Keystore** على API 23+، ومسار `MasterKeys` القديم على API 21/22 |
| الفهرس | `index.json` داخل `EncryptedSharedPreferences` (AES256_SIV للمفاتيح + AES256_GCM للقيم) |
| الإخفاء من المعرض | ملف **`.nomedia`** داخل `lion_vault/` وداخل `cacheDir/lion_vault_cache/` |
| التشغيل | فك تشفير مؤقت إلى `cacheDir/lion_vault_cache/` ثم تشغيله عبر ExoPlayer، ويُمسح فور الخروج |
| **زر إلغاء القفل/الاستعادة** | `VaultManager.restoreToPublicStorage()` → `Movies/LionVault` أو `Music/LionVault` عبر MediaStore، أو إلى مجلد **SAF** تختاره (بطاقة SD) |
| الحذف | حذف نهائي للملف المشفّر + نسخته المفكوكة |

### 🖥️ 3. حماية الشاشة والواجهة
- `WindowManager.LayoutParams.FLAG_SECURE` مفعّل في:
  - `VaultLockActivity` (شاشة القفل)
  - `VaultSetupActivity` (شاشة الإعداد)
  - `VaultActivity` (داخل الخزينة)
  - `PlayerActivity` **تلقائياً** عند تشغيل ملف قادم من الخزينة
- النتيجة: لا لقطة شاشة، لا تسجيل شاشة، لا معاينة في قائمة التطبيقات الحديثة.
- إضافات داعمة:
  - `android:excludeFromRecents="true"` لأنشطة الخزينة.
  - مسح ذاكرة **Coil** (القرص + الذاكرة) عند إعادة القفل حتى لا تبقى مصغّرات.
  - تعطيل النافذة العائمة (PiP) والمشاركة والحفظ التلقائي لموضع الاستئناف لملفات الخزينة.
  - إشعار التشغيل يعرض «🔐 ملف محمي» بدل تفاصيل الملف.
  - زر إعدادات لإيقاف/تفعيل `FLAG_SECURE` (مفعّل افتراضياً).

---

## 2) طريقة الاستخدام

1. **تفعيل الخزينة**: من الشاشة الرئيسية → زر القفل في الأعلى (أو تبويب «الخزينة») →
   عيّن رمز PIN واختر سؤال الأمان وأجب عليه → «حفظ وتفعيل».
2. **إخفاء ملف**: اضغط مطولاً على أي مقطع (أو حدد عدة مقاطع) →
   شريط التحديد → أيقونة القفل 🔐، أو من قائمة ⋮ → «إخفاء في الخزينة السرية».
   يُشفَّر الملف ثم **يُحذف الأصل** من ذاكرة الهاتف.
3. **فتح الخزينة**: تبويب «الخزينة» أو أيقونة القفل → PIN أو بصمة.
4. **التشغيل**: اضغط «تشغيل» — يُفك التشفير مؤقتاً داخل ذاكرة التطبيق المعزولة.
5. **الاستعادة (إلغاء القفل)**: «استعادة إلى الذاكرة» →
   `Movies/LionVault` / `Music/LionVault` أو مجلد SAF تختاره.
6. **إعادة القفل**: زر القفل في شريط الخزينة أو زر الرجوع — يمسح كل النسخ المفكوكة.

---

## 3) البناء

### محلياً
```bash
gradle assembleDebug        # أو: ./gradlew assembleDebug
# الناتج: app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions
الملف `.github/workflows/build.yml` يبني APK ويرفعه كـ Artifact باسم
`LionPlayer-SecureVault-Debug-APK` (JDK 17 Temurin + Gradle 8.5).

> **تمت إزالة حيلة `local-maven` (ملف AAR فارغ لـ `media3-decoder-ffmpeg`) من سير العمل**:
> اعتماد وحدة وهمية كان يُنتج APK يُنهي التشغيل بـ `NoClassDefFoundError`.
> استُبدلت بـ `androidx.media3:media3-decoder:1.3.0` الحقيقية، و`DefaultRenderersFactory`
> مع `EXTENSION_RENDERER_MODE_PREFER` يعمل بشكل صحيح بدون امتداد FFmpeg.

---

## 4) بنية الملفات المضافة/المعدّلة

```
app/src/main/java/com/lionplayer/app/
├── vault/                              ← جديد بالكامل
│   ├── VaultSecurityManager.kt         PIN + PBKDF2 + سؤال الأمان + EncryptedSharedPreferences
│   ├── VaultManager.kt                 تشفير/فك تشفير/فهرس/.nomedia/استعادة/قفل
│   ├── BiometricVaultHelper.kt         androidx.biometric
│   ├── VaultLockActivity.kt            شاشة القفل (FLAG_SECURE)
│   ├── VaultSetupActivity.kt           الإعداد الأول (FLAG_SECURE)
│   ├── VaultActivity.kt                داخل الخزينة (FLAG_SECURE)
│   └── VaultAdapter.kt                 قائمة الملفات المشفّرة
├── util/StorageHelper.kt               + insertFileToMediaStore() + copyFileToSafTree()
├── player/LionPlayerManager.kt         + دعم عناصر الخزينة + إشعار محايد
├── ui/PlayerActivity.kt                + FLAG_SECURE الديناميكي + حماية ملفات الخزينة
├── ui/MainActivity.kt                  + نقل إلى الخزينة + تبويب/أيقونة الخزينة
├── ui/PlayerSettingsBottomSheet.kt     + قسم إعدادات الخزينة
├── adapter/MediaAdapter.kt             + شارة القفل وعدم تحميل مصغّرات محمية
└── viewmodel/MediaViewModel.kt         + lockItemsInVault()

app/src/main/res/
├── layout/activity_vault_lock.xml      شاشة القفل
├── layout/activity_vault_setup.xml     شاشة الإعداد
├── layout/activity_vault.xml           داخل الخزينة
├── layout/item_vault_file.xml          عنصر ملف مشفّر
├── drawable/bg_vault_*.xml , ic_fingerprint.xml , ic_shield.xml , ic_vault.xml
├── menu/vault_menu.xml
└── values/ + values-night/ (ألوان وثيمات الخزينة)
```

---

## 5) ملاحظات أمنية صريحة (اقرؤها)

1. **الخزينة تحمي المحتوى من التطبيقات الأخرى ومن التصفح العادي**، وليس من مهاجم يملك
   الجهاز مفتوحاً ويعرف رمز PIN. هذا هو الحد الواقعي لأي "خزينة" داخل تطبيق.
2. الملف المُشغَّل يُفك تشفيره **مؤقتاً** في `cacheDir` لأن ExoPlayer لا يستطيع بث ملف
   مشفّر مباشرة؛ تُمسح النسخة عند الخروج/إعادة القفل، والمجلد يحتوي `.nomedia`.
3. إزالة تثبيت التطبيق تحذف الملفات المشفّرة نهائياً (`allowBackup=false`). لا يوجد استرداد.
4. `OpenSubtitlesManager` يحتاج مفتاح API خاصاً بك (`API_KEY`) — لا تُشحن مفتاحاً مشتركاً.
5. للاشتراط الأقصى: يمكن رفع `minSdk` إلى 23 واعتماد `MasterKey` فقط (Keystore)،
   أو إضافة `setUserAuthenticationRequired(true)` لربط المفتاح بمصادقة المستخدم.

---

## 6) حالة التحقق

تم فحص المشروع فعلياً عبر:

- `aapt2 compile` + `aapt2 link` مقابل `android.jar` (API 34) وموارد كل مكتبات AndroidX/Material/Media3
  → **كل ملفات XML (التخطيطات، القيم، القوائم، 64 drawable، الـ Manifest) سليمة**، وتم توليد `R.java`.
- `kotlinc 1.9.22` (JVM 17) على **34 ملف Kotlin** مقابل `android.jar` + كل مكتبات
  `androidx.*`, `media3`, `coil`, `tink`, `guava`, `biometric`, `security-crypto`
  → **0 أخطاء**.
- بناء APK الكامل عبر Gradle يتطلب Android SDK، وهو غير متوفر في بيئة الفحص هذه
  (يُنفَّذ في GitHub Actions).
