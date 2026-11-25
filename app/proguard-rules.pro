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
