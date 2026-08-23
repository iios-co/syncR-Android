# Add project specific ProGuard rules here.
# smbj uses reflection internally — keep all its classes
-keep class com.hierynomus.** { *; }
-keep class net.engio.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.**
-dontwarn org.bouncycastle.**

# Keep our service and receiver classes (referenced from manifest)
-keep class com.syncr.app.service.SyncService { *; }
-keep class com.syncr.app.service.BootReceiver { *; }

# Strip ALL android.util.Log calls in Release (production) builds.
# Since isMinifyEnabled=false in debug, logs will remain during development.
# This eliminates string allocation overhead and prevents leaking file paths/SSIDs.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static java.lang.String getStackTraceString(...);
}
