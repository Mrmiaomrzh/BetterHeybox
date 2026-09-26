-dontwarn io.github.libxposed.annotation.**

-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep class com.better.heybox.** { *; }

-keep class com.highcapable.yukihookapi.generated.** { *; }
-keep class com.highcapable.yukihookapi.YukiHook_Impl { *; }
-keep class com.highcapable.yukihookapi.hook.xposed.application.ModuleApplication_Impl { *; }

-dontwarn com.highcapable.**
-dontwarn org.jetbrains.annotations.**
-dontwarn androidx.annotation.**

-dontwarn org.luckypray.**
-dontwarn com.google.flatbuffers.**
-dontwarn org.lsposed.**
-dontwarn hidden.**
-dontwarn dev.rikka.**
