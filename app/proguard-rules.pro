# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep useful release crash traces without exposing local source file names.
-keepattributes SourceFile,LineNumberTable,RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# WebRTC calls Java methods from native code. The SDK ships consumer rules, but
# keeping this small Java bridge is safer than risking call failures after obfuscation.
# The large native .so files are unaffected by this rule.
-keep class org.webrtc.** { *; }

# Android instantiates these framework components by class name.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
