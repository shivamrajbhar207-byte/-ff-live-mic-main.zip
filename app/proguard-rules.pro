# RootEncoder uses reflection/JNI-style lookups in places; keep its public API.
-keep class com.pedro.** { *; }
-dontwarn com.pedro.**
# Google Play services auth
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**
