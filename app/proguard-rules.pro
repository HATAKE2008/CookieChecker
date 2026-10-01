# Keep model + service classes (reflection-free, but safe for R8)
-keep class com.hatake.cookiechecker.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
