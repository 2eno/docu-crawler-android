# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Jsoup (optionale re2j-Abhaengigkeit)
-dontwarn com.google.re2j.**

# Flexmark nutzt Reflection fuer Extensions / DataKeys
-keep class com.vladsch.flexmark.** { *; }
-dontwarn com.vladsch.flexmark.**
-dontwarn org.jetbrains.annotations.**
