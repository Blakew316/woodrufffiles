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
