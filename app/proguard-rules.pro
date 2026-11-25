# --- LiteRT-LM: JNI calls back into these Kotlin classes by name; keep them intact.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn com.google.ai.edge.**

# --- ONNX Runtime: JNI-bound classes.
-keep class ai.onnxruntime.** { *; }

# --- kotlinx.serialization: keep generated serializers for our catalog models.
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class com.gccera.myai.** {
    *** Companion;
}
-keepclasseswithmembers class com.gccera.myai.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Gson (used internally by LiteRT-LM)
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-dontwarn org.slf4j.**

# --- JGit (Code tab): desktop-only APIs referenced on code paths we disable (JMX stats, gc PID lock,
# Kerberos/SPNEGO auth). See IsolatedSystemReader in GitService.kt.
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
-keep class org.eclipse.jgit.** { *; }

# --- pdfbox-android: optional JPEG2000 decoder (JPX images in PDFs are skipped).
-dontwarn com.gemalto.jp2.**

# --- Sora Editor / tm4e: TextMate grammars & language configuration are parsed with Gson reflection.
-keep class org.eclipse.tm4e.** { *; }
-keep class io.github.rosemoe.sora.langs.textmate.** { *; }

# --- Chaquopy calls PyOutput.onOutput from Python by name.
-keep class com.gccera.myai.engine.code.PyOutput { *; }
-keep class * implements com.gccera.myai.engine.code.PyOutput { *; }
