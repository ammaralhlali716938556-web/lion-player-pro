-keep class androidx.media3.** { *; }
-keep class com.lionplayer.app.model.* { *; }
-keep class com.lionplayer.app.subtitle.* { *; }
-keep class coil.* { *; }

# --- Secure Vault ---
# Tink / androidx.security keep rules (reflection + JNI)
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.protobuf.**
-keep class androidx.biometric.** { *; }
