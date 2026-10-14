# Room, Compose, Navigation and the other AndroidX libraries ship their own keep rules.
# The app itself uses no reflection, so nothing else needs keeping.

# Keep line numbers in stack traces, so a crash report from the release build is readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
