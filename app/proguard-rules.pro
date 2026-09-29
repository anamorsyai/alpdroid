# AlpDroid ships proot/pty_bridge as separate executables spawned via ProcessBuilder, not as
# JNI shared libraries loaded into this process — there are no native methods for R8 to strip and
# no JNI signature-matching to preserve. No reflection, no serialization library (Gson/Moshi/Room),
# no custom Parcelable classes either, so this app needs essentially none of the usual keep rules.
# Kept minimal on purpose: fewer blanket -keep rules means R8 can actually shrink/obfuscate this
# app's own code, which is the point of enabling it.

# Keep line numbers in stack traces for crash reports, but drop the source file name (redundant
# once obfuscated, and there's no benefit to leaking it).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
