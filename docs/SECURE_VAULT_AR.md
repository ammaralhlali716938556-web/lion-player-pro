# Secure Vault — التصميم التقني ونموذج التهديد

## 1. تدفق البيانات

```
MediaStore (عام)                      الخزينة السرية                        الذاكرة العامة
────────────────                      ──────────────                        ─────────────
  file.mp4  ──lockMediaItem()──►  filesDir/lion_vault/ab12cd34.lv  ──restoreToPublicStorage()──►  Movies/LionVault/file.mp4
  (يُحذف الأصل)                    (AES-256-GCM-HKDF-4KB)              (MediaStore insert)
                                          │
                                          │ decryptToCache()
                                          ▼
                                   cacheDir/lion_vault_cache/vault_<id>.mp4  ──► ExoPlayer
                                          │
                                          └── lockVault() → حذف + مسح ذاكرة Coil
```

## 2. التشفير

| الطبقة | الآلية |
|---|---|
| محتوى الملف | `EncryptedFile` + `FileEncryptionScheme.AES256_GCM_HKDF_4KB` (Tink: HKDF-SHA256 → AES-256-GCM، تجزئة 4KB) |
| المفاتيح | `MasterKey` (`KeyScheme.AES256_GCM`) مخزّنة في **Android Keystore** (API 23+) |
| API 21/22 | `MasterKeys.getOrCreate(AES256_GCM_SPEC)` + الـ Builder النصي (deprecated) |
| الأسرار (PIN/الإجابة/الفهرس) | `EncryptedSharedPreferences` — `AES256_SIV` للمفاتيح و`AES256_GCM` للقيم |
| رمز PIN | `PBKDF2WithHmacSHA256`، 120,000 دورة، Salt 16 بايت (`SecureRandom`)، مقارنة بزمن ثابت |
| إجابة سؤال الأمان | `PBKDF2WithHmacSHA256`، 60,000 دورة، Salt مستقل، مع تطبيع (lowercase + توحيد المسافات) |

**لا يُخزَّن رمز PIN ولا إجابة سؤال الأمان كنص صريح في أي مكان.**

## 3. صيغة الفهرس (`index.json` داخل EncryptedSharedPreferences)

```json
[{
  "id": "6f1c…", "name": "clip.mp4", "file": "6f1c2a91.lv",
  "size": 10485760, "video": true, "folder": "Camera",
  "addedAt": 1712345678901, "uri": "content://media/external/video/media/123"
}]
```
عند القراءة تُستبعد السجلات التي لم يعد ملفها المشفّر موجوداً (تنظيف تلقائي).

## 4. الإخفاء من الفهرسة

- `filesDir` خاص بالتطبيق أصلاً ولا تراه `MediaStore`.
- مع ذلك يُنشأ `.nomedia` في `filesDir/lion_vault/` و`cacheDir/lion_vault_cache/`
  كطبقة دفاع إضافية (مثلاً عند نسخ احتياطي أو root).
- أنشطة الخزينة `excludeFromRecents="true"` + `FLAG_SECURE`.

## 5. نموذج التهديد

| التهديد | الحماية |
|---|---|
| تطبيق آخر/معرض الوسائط يرى الملفات | `filesDir` معزول + `.nomedia` + حذف الأصل |
| لقطة شاشة / تسجيل شاشة / معاينة Recent Apps | `FLAG_SECURE` على كل شاشات الخزينة + مشغل الملفات المحمية |
| تخمين رمز PIN | 5 محاولات → تجميد 30 ثانية + PBKDF2 120k + مقارنة بزمن ثابت |
| سرقة ملف الـ SharedPreferences | مشفّر بـ AES-256-GCM ومفتاحه في Keystore |
| قراءة الملف المشفّر من القرص | AES-256-GCM-HKDF-4KB (سلامة + سرية) |
| نسيان الرمز | سؤال أمان محلي (لا خادم ولا بريد) |
| آثار بعد إغلاق الخزينة | مسح `lion_vault_cache` + `diskCache.clear()` + `memoryCache.clear()` + إنهاء التشغيل |
| تسريب عبر PiP/المشاركة/الإشعار | PiP والمشاركة معطّلان لملفات الخزينة، والإشعار يعرض «🔐 ملف محمي» |

### خارج نطاق الحماية (بصدق)
- جهاز مفتوح بيد من يعرف رمز PIN.
- مهاجم بصلاحيات root يستطيع قراءة `cacheDir` **أثناء** التشغيل فقط (والملف مفكوك التشفير حينها).
- إزالة تثبيت التطبيق = فقدان نهائي للملفات (لا نسخ احتياطي: `allowBackup=false`).

## 6. تحسينات مقترحة لاحقاً
1. رفع `minSdk` إلى 23 والتخلي عن مسار `MasterKeys` القديم.
2. `MasterKey.Builder.setUserAuthenticationRequired(true)` لربط المفتاح بمصادقة حيوية.
3. مشغّل مخصص يفك التشفير إلى `DataSource` في الذاكرة (بدون ملف مؤقت) — يتطلب `DataSource.Factory` مخصص لـ Media3.
4. تخزين بصمة SHA-256 لكل ملف في الفهرس للتحقق من السلامة.
5. إخفاء أيقونة الخزينة خلف رمز سري يُدخل في شريط البحث.
