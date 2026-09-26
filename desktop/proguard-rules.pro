# Optional platform backends referenced by OkHttp / Retrofit on JVM.
-dontwarn android.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn okhttp3.internal.platform.**
-dontwarn retrofit2.Platform$Java8
# FlatLaf / JBR 用 MethodHandle.invoke / invokeExact（多态签名）调 JDK 内部实现，
# ProGuard 的库模型里没有这些描述符，会当成「引用了不存在的方法」直接失败。
-dontwarn java.lang.invoke.MethodHandle
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class ceui.pixshaft.** { *; }
-keep class ceui.pixshaft.desktop.protocol.** { *; }
-keep class ceui.pixshaft.desktop.protocol.pixiv.Handler { *; }
-keep class ceui.pixshaft.desktop.protocol.shaft.Handler { *; }
-keep class ceui.lisa.** { *; }
# JBR 的 `com.jetbrains.JBR` 是桥接类，运行时靠反射去 java.desktop 里找实现；
# FlatLaf 也用反射 / MethodHandle 读 UI 默认值。混淆或裁掉它们 = 发布包里自绘标题栏失效。
-keep class com.jetbrains.** { *; }
-keep class com.formdev.flatlaf.** { *; }
# JNA 全靠反射绑定原生函数 / Structure 字段，被裁掉或改名之后 `Native.getWindowPointer`
# 之类的调用会在发布包里静默失败（DWM 深色、窗口样式读取都会失效）。
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
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
