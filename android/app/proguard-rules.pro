# Keep Apache MINA SSHD — it relies heavily on reflection
-keep class org.apache.sshd.** { *; }
-keep interface org.apache.sshd.** { *; }
-dontwarn org.apache.sshd.**

# Keep JGit
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**

# Keep BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep SLF4J
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# Keep logback-android
-keep class ch.qos.logback.** { *; }
-dontwarn ch.qos.logback.**
