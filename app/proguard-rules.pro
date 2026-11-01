# Keep JNI entry points used by libspiros.so
-keepclasseswithmembernames class com.spiros.app.engine.SpirEngine {
    native <methods>;
}

# commons-compress: optional codecs and tooling we never use (only tar + xz).
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn org.apache.commons.compress.harmony.**
# Tink: optional integrations.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
