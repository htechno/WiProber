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
