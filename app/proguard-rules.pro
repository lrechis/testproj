# sherpa-onnx reads its Kotlin config classes from native code via JNI (field names and
# signatures must survive shrinking and obfuscation).
-keep class com.k2fsa.sherpa.onnx.** { *; }

# The native TTS code finds the audio callback by name and exact signature:
# invoke([F)Ljava/lang/Integer;. Keep that method on our callback class.
-keep class com.lrechis.kittentts.TtsEngine$AudioCallback { *; }
-keepclassmembers class * implements kotlin.jvm.functions.Function1 {
    public java.lang.Integer invoke(float[]);
}

# commons-compress has optional integrations (zstd, brotli, xz, ASM) that this app does not
# ship. Tell R8 the missing classes are expected.
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
-dontwarn org.objectweb.asm.**
-dontwarn io.airlift.compress.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.apache.commons.io.**
-dontwarn org.apache.commons.lang3.**
-dontwarn org.apache.commons.codec.**
