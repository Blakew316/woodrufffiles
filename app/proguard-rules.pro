# NanoHTTPD and ZXing are plain Java libraries with no reflection; defaults are sufficient.
-dontwarn org.nanohttpd.**

# JavaMail looks up providers and handlers by reflection.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-dontwarn java.awt.**
-dontwarn javax.security.**
-dontwarn com.sun.mail.**
-dontwarn javax.activation.**

# Readable stack traces from the TV (the phone's Smoothness Report samples them; crash reports too):
# class and method names stay, with file names and line numbers. R8 still removes unused code and
# optimises; nothing here relies on renamed classes.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
