# AndroidJUnitRunner uses this shared dependency after the target APK is shrunk.
# Only the opt-in device-test variant needs it; no application models are kept here.
-keep class androidx.tracing.** { *; }
# The separate test APK references Kotlin APIs removed/inlined from the target.
-keep class kotlin.** { *; }
# Instrumentation lives in another APK: retain the public entry points it calls.
# Do not retain fields: JSON field preservation is still exercised by release rules.
-keepclassmembers class com.example.wiprober.** {
    public <init>(...);
    public <methods>;
}
-keep class androidx.core.content.FileProvider { public *; }
# Assertions parse literal JSON and generated binary tracks independently.
-keep class com.google.gson.Json** { public *; }
-keep class com.example.wiprober.BinaryTrackReader** { *; }
# Separate UI-test APK references non-final resource IDs after target shrinking.
# This is deliberately test-only; production release uses proguard-rules.pro alone.
-keep class com.example.wiprober.R$id { *; }
-keep class com.example.wiprober.R$string { *; }
-keep class com.example.wiprober.R$plurals { *; }
# Cross-APK test entry points and Espresso's shared future interface.
-keep class androidx.lifecycle.ViewModelProvider** { public *; }
-keep class com.google.common.util.concurrent.** { *; }
-keep class androidx.concurrent.futures.** { *; }
# Scanner instrumentation uses coroutine helpers not called directly by the target APK.
-keep class kotlinx.coroutines.BuildersKt** { public *; }
-keep class kotlinx.coroutines.JobKt** { public *; }
-keep class kotlinx.coroutines.TimeoutKt** { public *; }
-keep enum kotlinx.coroutines.CoroutineStart { *; }
-keep interface kotlinx.coroutines.Job { public *; }
-keep interface kotlinx.coroutines.Deferred { public *; }
