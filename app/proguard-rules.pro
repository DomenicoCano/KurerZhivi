# Правила R8 для «Курьер Живи»
# Room, WorkManager и Compose поставляют собственные consumer-правила.
# Сохраняем номера строк для читаемых стектрейсов
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Воркеры создаются WorkManager по имени класса
-keep class com.example.myapplication.DailyWorker { *; }
-keep class com.example.myapplication.ExtremeWorker { *; }
-keep class com.example.myapplication.AutoBackupWorker { *; }
-keep class com.example.myapplication.ShiftActionReceiver { *; }
