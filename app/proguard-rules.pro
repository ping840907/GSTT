# ── LiteRT-LM ────────────────────────────────────────────────────────────────
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.**

# ── Room ──────────────────────────────────────────────────────────────────────
# Entity field names must survive R8 so @ColumnInfo mapping works at runtime.
-keep class com.example.voiceime.dictionary.** { *; }
-keepclassmembers @androidx.room.Entity class * { *; }
-keepclassmembers @androidx.room.Dao    class * { *; }

# ── App sealed classes / data classes used in StateFlow ──────────────────────
# EngineState, DownloadState, TranscriptionResult — accessed reflectively via
# when() branches and Compose collectAsState().
-keepclassmembers class com.example.voiceime.ai.** { *; }
-keepclassmembers class com.example.voiceime.preferences.** { *; }

# ── Hilt ──────────────────────────────────────────────────────────────────────
# AGP's Hilt plugin handles most rules; explicit dontwarn prevents noise.
-dontwarn dagger.hilt.**

# ── Kotlin coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
# Volatile fields in coroutine internals must not be inlined away by R8.
-keepclassmembers class kotlin.coroutines.**    { volatile <fields>; }
-keepclassmembers class kotlinx.coroutines.**  { volatile <fields>; }
-dontwarn kotlin.reflect.**

# ── Compose ───────────────────────────────────────────────────────────────────
-dontwarn androidx.compose.**
