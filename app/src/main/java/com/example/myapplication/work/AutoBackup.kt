package com.example.myapplication

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// ============================================================================
// АВТОБЭКАП: раз в неделю JSON-бэкап в папку, выбранную пользователем (SAF, без разрешений на память).
// Хранятся последние KEEP_FILES файлов, старые удаляются.
// ============================================================================

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!SettingsManager(applicationContext).isAutoBackupEnabled()) return Result.success()
        val err = writeBackup(applicationContext)
        return if (err != null && runAttemptCount < 2) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK = "AUTO_BACKUP"
        private const val PREFIX = "kurer-autobackup-"
        private const val KEEP_FILES = 5

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS).addTag(WORK).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)

        /** Пишет бэкап; возвращает текст ошибки или null при успехе. Результат сохраняется в настройках. */
        suspend fun writeBackup(context: Context): String? {
            val settings = SettingsManager(context)
            val err = try {
                val tree = Uri.parse(settings.getAutoBackupUri() ?: throw IllegalStateException("папка не выбрана"))
                val repo = CourierRepository(context, settings)
                val json = Backup.export(settings, repo.allShifts(), repo.allIntercoms())
                val resolver = context.contentResolver
                val treeId = DocumentsContract.getTreeDocumentId(tree)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)

                // Уже существующие автобэкапы в папке
                val existing = mutableListOf<Pair<String, String>>() // id, имя
                resolver.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId),
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
                )?.use { c -> while (c.moveToNext()) { val name = c.getString(1) ?: ""; if (name.startsWith(PREFIX)) existing.add(c.getString(0) to name) } }
                    ?: throw IllegalStateException("нет доступа к папке")

                val name = PREFIX + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
                val target = existing.firstOrNull { it.second == name }?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.first) }
                    ?: DocumentsContract.createDocument(resolver, parent, "application/json", name)
                    ?: throw IllegalStateException("не удалось создать файл")
                resolver.openOutputStream(target, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }

                // Чистим старые: оставляем KEEP_FILES последних (имена сортируются по дате)
                (existing.map { it.second } + name).distinct().sortedDescending().drop(KEEP_FILES).forEach { old ->
                    existing.firstOrNull { it.second == old }?.let {
                        try { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.first)) } catch (_: Exception) {}
                    }
                }
                null
            } catch (e: SecurityException) {
                "нет доступа к папке — выберите её заново"
            } catch (e: Exception) {
                e.message ?: "ошибка записи"
            }
            settings.saveAutoBackupResult(if (err == null) System.currentTimeMillis() else settings.getAutoBackupLastMs(), err)
            return err
        }
    }
}
