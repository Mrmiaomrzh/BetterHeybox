-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

-keep class com.better.heybox.** { *; }

-dontwarn org.luckypray.**
-dontwarn com.google.flatbuffers.**
-dontwarn org.lsposed.**
-dontwarn hidden.**
-dontwarn dev.rikka.**
