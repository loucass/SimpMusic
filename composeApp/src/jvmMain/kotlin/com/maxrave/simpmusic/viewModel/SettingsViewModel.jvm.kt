package com.maxrave.simpmusic.viewModel

import com.eygraber.uri.Uri
import com.maxrave.common.DB_NAME
import com.maxrave.common.SETTINGS_FILENAME
import com.maxrave.data.io.getHomeFolderPath
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.CommonRepository
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.viewModel.RestoreProgress
import com.maxrave.simpmusic.extension.zipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import multiplatform.network.cmptoast.ToastGravity
import multiplatform.network.cmptoast.showToast
import org.jetbrains.compose.resources.getString
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.restore_success
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

actual suspend fun calculateDataFraction(cacheRepository: CacheRepository): SettingsStorageSectionFraction? = null

actual suspend fun readPickedFile(uri: Uri): ByteArray = File(uri.toString()).readBytes()

actual suspend fun restoreNative(
    commonRepository: CommonRepository,
    uri: Uri,
    backupDownloaded: Boolean,
    progressCallback: ((RestoreProgress) -> Unit)?,
    getData: () -> Unit,
) {
    fun report(progress: RestoreProgress) {
        progressCallback?.invoke(progress)
    }

    report(RestoreProgress.Started("Restore in progress"))

    ZipInputStream(
        FileInputStream(File(uri.toString())),
    ).use { inputStream ->
        var entry: java.util.zip.ZipEntry? = inputStream.nextEntry()
        while (entry != null) {
            Logger.d("BackupRestore", "Processing entry: ${entry.name}")
            when {
                entry.name == "$SETTINGS_FILENAME.preferences_pb" -> {
                    File(getHomeFolderPath(listOf(".simpmusic")), "$SETTINGS_FILENAME.preferences_pb")
                        .outputStream()
                        .use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                }

                entry.name == DB_NAME -> {
                    report(RestoreProgress.Step("Restoring database...", 1, 2))
                    runBlocking(Dispatchers.IO) {
                        commonRepository.databaseDaoCheckpoint()
                        commonRepository.closeDatabase()
                    }
                    FileOutputStream(commonRepository.getDatabasePath()).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                else -> {
                    Logger.d("BackupRestore", "Unhandled entry: ${entry.name}")
                }
            }
            entry = inputStream.nextEntry() as java.util.zip.ZipEntry?
        }
        report(RestoreProgress.Step("Verifying database...", 2, 2))
        val dbPath = commonRepository.getDatabasePath()
        if (!File(dbPath).exists()) {
            throw IllegalStateException("Database not found after restore")
        }
        report(RestoreProgress.Completed("Restore successful"))

        withContext(Dispatchers.Main) {
            showToast("Restore successful", ToastGravity.Bottom)
            showToast("App will restart to apply changes", ToastGravity.Bottom)
            delay(2000)
            getData()
            exitProcess(0)
        }
    }
}

actual suspend fun backupNative(
    commonRepository: CommonRepository,
    uri: Uri,
    backupDownloaded: Boolean,
    progressCallback: ((RestoreProgress) -> Unit)?,
) {
    fun report(progress: RestoreProgress) {
        progressCallback?.invoke(progress)
    }

    report(RestoreProgress.Started("Backup in progress"))

    FileOutputStream(File(uri.toString())).use {
        it.buffered().zipOutputStream().use { outputStream ->
            report(RestoreProgress.Step("Backing up settings...", 1, 2))
            File(getHomeFolderPath(listOf(".simpmusic")), "$SETTINGS_FILENAME.preferences_pb")
                .inputStream()
                .buffered()
                .use { inputStream ->
                    outputStream.putNextEntry(ZipEntry("$SETTINGS_FILENAME.preferences_pb"))
                    inputStream.copyTo(outputStream)
                }
            report(RestoreProgress.Step("Backing up database...", 2, 2))
            runBlocking(Dispatchers.IO) {
                commonRepository.databaseDaoCheckpoint()
            }
            FileInputStream(commonRepository.getDatabasePath()).use { inputStream ->
                outputStream.putNextEntry(ZipEntry(DB_NAME))
                inputStream.copyTo(outputStream)
            }
        }
    }
    report(RestoreProgress.Completed("Backup created successfully"))
}

actual fun getPackageName(): String = ""

actual fun getFileDir(): String = ""

actual fun changeLanguageNative(code: String) {
    Locale.setDefault(
        Locale.forLanguageTag(
            if (code == "id-ID") {
                "in-ID"
            } else {
                code
            },
        ),
    )
}