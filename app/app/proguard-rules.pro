# Project-specific ProGuard / R8 rules.
# The release build (r39+) is optimised by R8 more aggressively than the old minified debug build:
# fields and methods that only native code (JNI) uses look unused to R8 and are removed or renamed.
# Everything that JNI looks up by name must be kept here.

# zstd-jni: the native part reads/writes the fields srcPos, dstPos, ... of its Java classes
# (crash "no J field srcPos in ZstdInputStreamNoFinalizer" when the rootfs / drivers are unpacked).
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# commons-compress / xz: checks for optional codecs by class name, keep them as they are
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**

# All classes with native methods of the app: their JNI callbacks (methods / fields looked up by name) stay.
-keepclasseswithmembers,includedescriptorclasses class com.factorio.** {
    native <methods>;
}
-keepclasseswithmembers class com.factorio.** {
    native <methods>;
    <fields>;
    <methods>;
}

# Called back from native code by name (GetMethodID / GetFieldID)
-keep class com.factorio.xenvironment.components.VortekRendererComponent { *; }
-keep class com.factorio.xenvironment.components.VortekRendererComponent$Options { *; }
-keep class com.factorio.xserver.extensions.LegacyGLXExtension { *; }
-keep class com.factorio.xserver.extensions.GLXExtension { *; }
-keep class com.factorio.xconnector.XConnectorEpoll { *; }
-keep class com.factorio.xconnector.XInputStream { *; }
-keep class com.factorio.xconnector.XOutputStream { *; }
-keep class com.factorio.renderer.GPUImage { *; }
-keep class com.factorio.xserver.Drawable { *; }
-keep class com.factorio.sysvshm.SysVSharedMemory { *; }
-keep class com.factorio.core.GPUHelper { *; }
-keep class com.factorio.core.TextureCompressor { *; }

# androidx.annotation.Keep is honoured by the default rules; keep it explicit for members too
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
