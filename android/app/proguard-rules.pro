# R8 rules for release builds (issue #187). kotlinx.serialization, OkHttp, coroutines and Compose
# ship their own consumer rules, so this file only holds what the app itself needs.

# Keep line numbers in crash stack traces; the mapping file deobfuscates the rest.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
