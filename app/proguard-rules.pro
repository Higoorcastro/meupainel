# Room, Media3, Coil, WorkManager e DataStore já incluem suas próprias regras de consumidor.
# Workers são instanciados por reflexão pelo WorkManager.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
# Mantém números de linha nos stack traces (útil para os logs de crash).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
