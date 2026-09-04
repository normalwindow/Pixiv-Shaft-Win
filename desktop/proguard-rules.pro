# Optional platform backends referenced by OkHttp / Retrofit on JVM.
-dontwarn android.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn okhttp3.internal.platform.**
-dontwarn retrofit2.Platform$Java8
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class ceui.pixshaft.** { *; }
-keep class ceui.pixshaft.desktop.protocol.** { *; }
-keep class ceui.pixshaft.desktop.protocol.pixiv.Handler { *; }
-keep class ceui.pixshaft.desktop.protocol.shaft.Handler { *; }
-keep class ceui.lisa.** { *; }
-keep class com.google.gson.** { *; }
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class coil3.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepdirectories **
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
