# Kosh release rules.

# Room entities and DAOs (generated code references them by name).
-keep class com.amitjha.kosh.data.** { *; }
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# WorkManager instantiates workers reflectively.
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# Manifest-registered components.
-keep class com.amitjha.kosh.sms.SmsReceiver { *; }
-keep class com.amitjha.kosh.KoshApp { *; }

# Investment specs are serialised with org.json by field names in code (no reflection), but keep the
# finance and mail engines' data classes readable in crash traces.
-keepnames class com.amitjha.kosh.finance.** { *; }
-keepnames class com.amitjha.kosh.mail.** { *; }
-keepnames class com.amitjha.kosh.sms.** { *; }

# Google Play services auth.
-keep class com.google.android.gms.auth.api.identity.** { *; }
-dontwarn com.google.android.gms.**

# Coroutines.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.debug.**

# Kotlin metadata for enums used with valueOf().
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
