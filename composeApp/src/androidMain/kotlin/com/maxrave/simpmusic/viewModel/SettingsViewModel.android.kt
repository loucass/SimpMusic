package com.maxrave.simpmusic.viewModel

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.storage.StorageManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.room.Room
import androidx.room.RoomDatabase
import coil3.imageLoader
import com.eygraber.uri.Uri
import com.eygraber.uri.toAndroidUri
import com.maxrave.common.Config
import com.maxrave.common.DB_NAME
import com.maxrave.common.DOWNLOAD_EXOPLAYER_FOLDER
import com.maxrave.common.EXOPLAYER_DB_NAME
import com.maxrave.common.SETTINGS_FILENAME
import com.maxrave.data.db.MusicDatabase
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.CommonRepository
import com.maxrave.logger.Logger
import com.maxrave.media3.di.stopService
import com.maxrave.simpmusic.canvas.CANVAS_DIRECTORY
import com.maxrave.simpmusic.extension.bytesToMB
import com.maxrave.simpmusic.extension.getSizeOfFile
import com.maxrave.simpmusic.extension.zipInputStream
import com.maxrave.simpmusic.extension.zipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import multiplatform.network.cmptoast.ToastGravity
import multiplatform.network.cmptoast.showToast
import org.jetbrains.compose.resources.getString
import org.koin.mp.KoinPlatform.getKoin
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.restore_success
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

actual suspend fun calculateDataFraction(cacheRepository: CacheRepository): SettingsStorageSectionFraction? {
    val application: Context = getKoin().get()
    return withContext(Dispatchers.Default) {
        val playerCache = cacheRepository.getCacheSize(Config.PLAYER_CACHE)
        val downloadCache = cacheRepository.getCacheSize(Config.DOWNLOAD_CACHE)
        val canvasCache = cacheRepository.getCacheSize(Config.CANVAS_CACHE)
        val simpMusicSize = playerCache + downloadCache + canvasCache
        val usedSpace = application.filesDir.totalSpace - application.filesDir.freeSpace
        val freeSpace = application.filesDir.freeSpace
        val thumbSize = (application.imageLoader.diskCache?.size ?: 0L).bytesToMB()
        val otherApp = simpMusicSize.let { usedSpace.minus(it) - thumbSize }
        val databaseSize =
            simpMusicSize - playerCache.bytesToMB() - downloadCache.bytesToMB() - canvasCache.bytesToMB()
        if (totalByte ==
            freeSpace + otherApp + simpMusicSize + thumbSize
        ) {
            SettingsStorageSectionFraction(
                otherApp = otherApp.toFloat().div(totalByte.toFloat()),
                downloadCache =
                    downloadCache
                        .bytesToMB()
                        .toFloat()
                        .div(totalByte.toFloat()),
                playerCache =
                    playerCache
                        .bytesToMB()
                        .toFloat()
                        .div(totalByte.toFloat()),
                canvasCache =
                    canvasCache
                        .bytesToMB()
                        .toFloat()
                        .div(totalByte.toFloat()),
                thumbCache = thumbSize.toFloat().div(totalByte.toFloat()),
                freeSpace = freeSpace.toFloat().div(totalByte.toFloat()),
                appDatabase = databaseSize.toFloat().div(totalByte.toFloat()),
            )
        } else {
            null
        }
    }
}

actual suspend fun readPickedFile(uri: Uri): ByteArray {
    val application: Context = getKoin().get()
    return application.contentResolver.openInputStream(uri.toAndroidUri())?.use { it.readBytes() }
        ?: error("Cannot open $uri")
}

/**
 * Shuts down the DownloadManager gracefully so its database can be safely backed up.
 */
private suspend fun shutdownDownloadManagerForBackup(application: Context) {
    val downloadHandler = getKoin().get<DownloadHandler>()
    downloadHandler.shutdownForBackup()
}

/**
 * Reinitializes the DownloadManager after restore, syncing Room states with ExoPlayer DB.
 */
private suspend fun reinitializeDownloadManagerAfterRestore(application: Context) {
    val downloadHandler = getKoin().get<DownloadHandler>()
    downloadHandler.reinitializeAfterRestore()
}

/**
 * Verifies the restored database by creating a fresh Room instance.
 * Does NOT use the closed CommonRepository instance.
 */
private suspend fun verifyRestoredDatabase(dbPath: String): Boolean {
    return try {
        val testDb = Room.databaseBuilder(
            getKoin().get<Context>(),
            MusicDatabase::class.java,
            DB_NAME
        ).setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        testDb.openHelper.writableDatabase
        testDb.close()
        true
    } catch (e: Exception) {
        Logger.w("BackupRestore", "Database verification failed: ${e.message}")
        false
    }
}

actual suspend fun restoreNative(
    commonRepository: CommonRepository,
    uri: Uri,
    backupDownloaded: Boolean,
    progressCallback: ((RestoreProgress) -> Unit)?,
    getData: () -> Unit,
) {
    val application: Context = getKoin().get()

    // Helper to report progress
    fun report(progress: RestoreProgress) {
        progressCallback?.invoke(progress)
    }

    report(RestoreProgress.Started(restoreInProgress))

    // PHASE 0: Stop media service and shutdown DownloadManager
    report(RestoreProgress.Step("Stopping media services...", 0, 7))
    stopService(application)
    shutdownDownloadManagerForBackup(application)

    // PHASE 1: Close and backup Room database
    report(RestoreProgress.Step("Preparing databases...", 1, 7))
    runBlocking(Dispatchers.IO) {
        commonRepository.databaseDaoCheckpoint()
        commonRepository.closeDatabase()
    }

    // PHASE 2: Extract and restore all files
    application.applicationContext.contentResolver.openInputStream(uri.toAndroidUri())?.use {
        it.zipInputStream().use { inputStream ->
            var entry =
                try {
                    inputStream.nextEntry
                } catch (e: Exception) {
                    null
                }

            var downloadFolderCleared = false
            var artworkFolderCleared = false
            var canvasFolderCleared = false
            var exoPlayerDbRestored = false

            // First pass: count total entries for progress
            val entries = mutableListOf<ZipEntry>()
            val tempStream = application.applicationContext.contentResolver.openInputStream(uri.toAndroidUri())?.zipInputStream()
            var tempEntry = tempStream?.nextEntry()
            while (tempEntry != null) {
                if (tempEntry.name == DB_NAME || tempEntry.name == EXOPLAYER_DB_NAME ||
                    tempEntry.name.startsWith("$DOWNLOAD_EXOPLAYER_FOLDER/") ||
                    tempEntry.name.startsWith("artwork/") ||
                    tempEntry.name.startsWith("$CANVAS_DIRECTORY/") ||
                    tempEntry.name == "$SETTINGS_FILENAME.preferences_pb") {
                    entries.add(tempEntry)
                }
                tempEntry = tempStream?.nextEntry()
            }
            tempStream?.close()
            val totalEntries = entries.size
            var currentEntry = 0

            // Reopen the actual stream for restoration
            application.applicationContext.contentResolver.openInputStream(uri.toAndroidUri())?.use {
                it.zipInputStream().use { inputStream ->
                    var entry =
                        try {
                            inputStream.nextEntry
                        } catch (e: Exception) {
                            null
                        }

                    var downloadFolderCleared = false
                    var artworkFolderCleared = false
                    var canvasFolderCleared = false
                    var exoPlayerDbRestored = false

                    while (entry != null) {
                        currentEntry++
                        report(RestoreProgress.Step(
                            "Restoring ${entry.name} ($currentEntry/$totalEntries)",
                            currentEntry, totalEntries
                        ))

                        Logger.d("BackupRestore", "Processing entry: ${entry.name}")
                        when {
                            entry.name == "$SETTINGS_FILENAME.preferences_pb" -> {
                                (application.filesDir / "datastore" / "$SETTINGS_FILENAME.preferences_pb")
                                    .outputStream()
                                    .use { outputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                            }

                            entry.name == DB_NAME -> {
                                // Delete the database file AND its WAL/SHM files to avoid Room corruption
                                val dbPath = commonRepository.getDatabasePath()
                                File(dbPath).delete()
                                File("$dbPath-wal").delete()
                                File("$dbPath-shm").delete()
                                FileOutputStream(dbPath).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                                // Force filesystem sync to ensure database is fully written
                                runBlocking(Dispatchers.IO) {
                                    File(dbPath).absoluteFile.parentFile?.let { dir ->
                                        val fos = FileOutputStream(File(dir, ".sync"))
                                        fos.getFD().sync()
                                        fos.close()
                                        File(dir, ".sync").delete()
                                    }
                                }
                            }

                            entry.name == EXOPLAYER_DB_NAME -> {
                                // Clear ExoPlayer DB first
                                val exoDbPath = application.getDatabasePath(EXOPLAYER_DB_NAME).path
                                File(exoDbPath).delete()
                                File("$exoDbPath-wal").delete()
                                File("$exoDbPath-shm").delete()
                                FileOutputStream(exoDbPath).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                                runBlocking(Dispatchers.IO) {
                                    File(exoDbPath).absoluteFile.parentFile?.let { dir ->
                                        val fos = FileOutputStream(File(dir, ".sync"))
                                        fos.getFD().sync()
                                        fos.close()
                                        File(dir, ".sync").delete()
                                    }
                                }
                                exoPlayerDbRestored = true
                            }

                            entry.name.startsWith("$DOWNLOAD_EXOPLAYER_FOLDER/") -> {
                                Logger.d("BackupRestore", "Found download entry: ${entry.name}")
                                if (!downloadFolderCleared) {
                                    val downloadFolder = application.filesDir / DOWNLOAD_EXOPLAYER_FOLDER
                                    Logger.d("BackupRestore", "=== RESTORE: Download folder contents BEFORE clearing ===")
                                    debugFolderContents(downloadFolder)
                                    Logger.d("BackupRestore", "Clearing download folder: ${downloadFolder.absolutePath}")
                                    clearFolder(downloadFolder)
                                    Logger.d("BackupRestore", "=== RESTORE: Download folder contents AFTER clearing ===")
                                    debugFolderContents(downloadFolder)
                                    downloadFolderCleared = true
                                }
                                restoreFolder(entry.name, inputStream, "download")
                            }

                            entry.name.startsWith("artwork/") -> {
                                Logger.d("BackupRestore", "Found artwork entry: ${entry.name}")
                                if (!artworkFolderCleared) {
                                    val artworkFolder = application.filesDir / "artwork"
                                    Logger.d("BackupRestore", "=== RESTORE: Artwork folder contents BEFORE clearing ===")
                                    debugFolderContents(artworkFolder)
                                    Logger.d("BackupRestore", "Clearing artwork folder: ${artworkFolder.absolutePath}")
                                    clearFolder(artworkFolder)
                                    Logger.d("BackupRestore", "=== RESTORE: Artwork folder contents AFTER clearing ===")
                                    debugFolderContents(artworkFolder)
                                    artworkFolderCleared = true
                                }
                                restoreFolder(entry.name, inputStream, "artwork")
                            }

                            entry.name.startsWith("$CANVAS_DIRECTORY/") -> {
                                Logger.d("BackupRestore", "Found canvas entry: ${entry.name}")
                                if (!canvasFolderCleared) {
                                    val canvasFolder = application.filesDir / CANVAS_DIRECTORY
                                    Logger.d("BackupRestore", "Clearing canvas folder: ${canvasFolder.absolutePath}")
                                    clearFolder(canvasFolder)
                                    canvasFolderCleared = true
                                }
                                restoreFolder(entry.name, inputStream, CANVAS_DIRECTORY)
                            }

                            else -> {
                                Logger.d("BackupRestore", "Unhandled entry: ${entry.name}")
                            }
                        }
                        entry = inputStream.nextEntry
                    }
                }
            }
        }
    }

    // PHASE 3: Verify databases
    report(RestoreProgress.Step("Verifying restored databases...", 5, 7))
    val dbPath = commonRepository.getDatabasePath()
    val dbVerified = verifyRestoredDatabase(dbPath)
    if (!dbVerified) {
        throw IllegalStateException("Main database verification failed after restore")
    }
    val exoDbPath = application.getDatabasePath(EXOPLAYER_DB_NAME).path
    if (File(exoDbPath).exists()) {
        // Verify ExoPlayer DB can be opened
        val exoVerified = verifyRestoredDatabase(exoDbPath)
        if (!exoVerified) {
            throw IllegalStateException("ExoPlayer database verification failed after restore")
        }
    }

    // PHASE 4: Reinitialize DownloadManager and sync download states
    report(RestoreProgress.Step("Synchronizing download states...", 6, 7))
    reinitializeDownloadManagerAfterRestore(application)

    // PHASE 5: Verify downloaded files actually exist on disk
    report(RestoreProgress.Step("Verifying downloaded files...", 7, 7))
    val downloadHandler = getKoin().get<DownloadHandler>()
    val downloadFolder = application.filesDir / DOWNLOAD_EXOPLAYER_FOLDER
    if (downloadFolder.exists()) {
        val exoDownloads = downloadHandler.downloads.value
        var verified = 0
        var failed = 0
        for ((videoId, pair) in exoDownloads) {
            val state = pair.first?.state ?: pair.second?.state
            if (state == DownloadHandler.State.STATE_COMPLETED) {
                // Check if file exists on disk
                val downloadFolder = application.filesDir / DOWNLOAD_EXOPLAYER_FOLDER
                val videoFile = downloadFolder.listFiles()?.find { it.name.contains(videoId) }
                if (videoFile != null && videoFile.exists() && videoFile.length() > 0) {
                    verified++
                } else {
                    // Mark as not downloaded in Room
                    val songRepository = getKoin().get<com.maxrave.domain.repository.SongRepository>()
                    songRepository.updateDownloadState(videoId, com.maxrave.domain.data.entities.DownloadState.STATE_NOT_DOWNLOADED)
                    failed++
                    Logger.w("BackupRestore", "Download missing for $videoId, marked as not downloaded")
                }
            }
        }
        Logger.i("BackupRestore", "Download verification: $verified verified, $failed missing")
    }

    // Final debug check
    val downloadFolder = application.filesDir / DOWNLOAD_EXOPLAYER_FOLDER
    Logger.d("BackupRestore", "=== RESTORE: Download folder contents AFTER RESTORE ===")
    debugFolderContents(downloadFolder)

    val artworkFolder = application.filesDir / "artwork"
    Logger.d("BackupRestore", "=== RESTORE: Artwork folder contents AFTER RESTORE ===")
    debugFolderContents(artworkFolder)

    val canvasFolder = application.filesDir / CANVAS_DIRECTORY
    Logger.d("BackupRestore", "=== RESTORE: Canvas folder contents AFTER RESTORE ===")
    debugFolderContents(canvasFolder)

    report(RestoreProgress.Completed(restoreSuccess))

    // Graceful restart
    withContext(Dispatchers.Main) {
        showToast(restoreSuccess, ToastGravity.Bottom)
        getData()
        val ctx = application.applicationContext
        val pm: PackageManager = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(ctx.packageName)
        val mainIntent = Intent.makeRestartActivityTask(intent?.component)
        ctx.startActivity(mainIntent)
        Runtime.getRuntime().exit(0)
    }
}

actual suspend fun backupNative(
    commonRepository: CommonRepository,
    uri: Uri,
    backupDownloaded: Boolean,
    progressCallback: ((RestoreProgress) -> Unit)?,
) {
    val application: Context = getKoin().get()

    fun report(progress: RestoreProgress) {
        progressCallback?.invoke(progress)
    }

    report(RestoreProgress.Started(backupInProgress))

    // PHASE 0: Shutdown DownloadManager if backing up downloads
    if (backupDownloaded) {
        report(RestoreProgress.Step("Preparing download database...", 0, 5))
        shutdownDownloadManagerForBackup(application)
    }

    application.applicationContext.contentResolver.openOutputStream(uri.toAndroidUri())?.use {
        it.buffered().zipOutputStream().use { outputStream ->
            // Settings
            report(RestoreProgress.Step("Backing up settings...", 1, 5))
            (application.filesDir / "datastore" / "$SETTINGS_FILENAME.preferences_pb")
                .inputStream()
                .buffered()
                .use { inputStream ->
                    outputStream.putNextEntry(ZipEntry("$SETTINGS_FILENAME.preferences_pb"))
                    inputStream.copyTo(outputStream)
                }

            // Main database
            report(RestoreProgress.Step("Backing up main database...", 2, 5))
            runBlocking(Dispatchers.IO) {
                commonRepository.databaseDaoCheckpoint()
            }
            FileInputStream(commonRepository.getDatabasePath()).use { inputStream ->
                outputStream.putNextEntry(ZipEntry(DB_NAME))
                inputStream.copyTo(outputStream)
            }

            if (backupDownloaded) {
                // ExoPlayer database
                report(RestoreProgress.Step("Backing up download database...", 3, 5))
                FileInputStream(application.getDatabasePath(EXOPLAYER_DB_NAME)).use { inputStream ->
                    outputStream.putNextEntry(ZipEntry(EXOPLAYER_DB_NAME))
                    inputStream.copyTo(outputStream)
                }

                // Download folder
                report(RestoreProgress.Step("Backing up downloaded files...", 4, 5))
                val downloadFolder = application.filesDir / DOWNLOAD_EXOPLAYER_FOLDER
                Logger.d("BackupRestore", "=== BACKUP: Download folder contents BEFORE backup ===")
                debugFolderContents(downloadFolder)
                backupFolder(downloadFolder, DOWNLOAD_EXOPLAYER_FOLDER, outputStream)

                // Artwork folder
                val artworkFolder = application.filesDir / "artwork"
                Logger.d("BackupRestore", "=== BACKUP: Artwork folder contents BEFORE backup ===")
                debugFolderContents(artworkFolder)
                backupFolder(artworkFolder, "artwork", outputStream)

                // Canvas folder
                val canvasFolder = application.filesDir / CANVAS_DIRECTORY
                Logger.d("BackupRestore", "=== BACKUP: Canvas folder contents BEFORE backup ===")
                debugFolderContents(canvasFolder)
                backupFolder(canvasFolder, CANVAS_DIRECTORY, outputStream)

                // Reinitialize DownloadManager after backup
                reinitializeDownloadManagerAfterRestore(application)
            }
        }
    }
    report(RestoreProgress.Completed(backupCreateSuccess))
}

private fun backupFolder(
    folder: File,
    baseName: String,
    zipOutputStream: ZipOutputStream,
) {
    if (!folder.exists() || !folder.isDirectory) return

    Logger.d("BackupRestore", "Backing up folder: ${folder.absolutePath} as $baseName")
    folder.listFiles()?.forEach { file ->
        if (file.isFile) {
            val entryName = "$baseName/${file.name}"
            Logger.d("BackupRestore", "Backing up file: $entryName")
            zipOutputStream.putNextEntry(ZipEntry(entryName))
            file.inputStream().buffered().use { inputStream ->
                inputStream.copyTo(zipOutputStream)
            }
            zipOutputStream.closeEntry()
        } else if (file.isDirectory) {
            Logger.d("BackupRestore", "Entering subdirectory: ${file.name}")
            backupFolder(file, "$baseName/${file.name}", zipOutputStream)
        }
    }
}

private fun debugFolderContents(
    folder: File,
    level: Int = 0,
) {
    if (!folder.exists()) {
        Logger.d("BackupRestore", "${"  ".repeat(level)}Folder does not exist: ${folder.absolutePath}")
        return
    }

    Logger.d("BackupRestore", "${"  ".repeat(level)}Folder: ${folder.name} (${folder.absolutePath})")
    folder.listFiles()?.forEach { file ->
        if (file.isFile) {
            Logger.d("BackupRestore", "${"  ".repeat(level + 1)}File: ${file.name} (${file.length()} bytes)")
        } else if (file.isDirectory) {
            debugFolderContents(file, level + 1)
        }
    }
}

private fun clearFolder(folder: File) {
    if (folder.exists() && folder.isDirectory) {
        Logger.d("BackupRestore", "Clearing folder: ${folder.absolutePath}")
        folder.listFiles()?.forEach { file ->
            if (file.isFile) {
                Logger.d("BackupRestore", "Deleting file: ${file.name}")
                file.delete()
            } else if (file.isDirectory) {
                clearFolder(file) // Recursive
                Logger.d("BackupRestore", "Deleting directory: ${file.name}")
                file.delete() // Delete empty directory
            }
        }
    }
}

private fun restoreFolder(
    entryName: String,
    zipInputStream: ZipInputStream,
    baseFolderName: String,
) {
    val application: Context = getKoin().get()
    Logger.d("BackupRestore", "Restoring entry: $entryName")

    // Extract relative path from entry name
    val relativePath = entryName.removePrefix("$baseFolderName/")
    val targetFile = application.filesDir / baseFolderName / relativePath

    Logger.d("BackupRestore", "Target file path: ${targetFile.absolutePath}")
    Logger.d("BackupRestore", "Relative path: $relativePath")

    // Create parent directories if they don't exist
    val parentCreated = targetFile.parentFile?.mkdirs()
    Logger.d("BackupRestore", "Parent dir created: $parentCreated, parent exists: ${targetFile.parentFile?.exists()}")

    try {
        // Restore the file content
        targetFile.outputStream().use { outputStream ->
            val bytesWritten = zipInputStream.copyTo(outputStream)
            Logger.d("BackupRestore", "Restored file: ${targetFile.name}, bytes: $bytesWritten")

            // Verify file was created
            if (targetFile.exists()) {
                Logger.d("BackupRestore", "File exists after restore: ${targetFile.name}, size: ${targetFile.length()}")
            } else {
                Logger.e("BackupRestore", "File NOT created: ${targetFile.name}")
            }
        }
    } catch (e: Exception) {
        Logger.e("BackupRestore", "Error restoring file: ${targetFile.name}")
    }
}

operator fun File.div(child: String): File = File(this, child)

actual fun getPackageName(): String {
    val application: Context = getKoin().get()
    return application.packageName
}

actual fun getFileDir(): String {
    val application: Context = getKoin().get()
    return application.filesDir.absolutePath
}

actual fun changeLanguageNative(code: String) {
    try {
        val localeList =
            LocaleListCompat.forLanguageTags(code)
        Logger.d("Language", localeList.toString())
        AppCompatDelegate.setApplicationLocales(localeList)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}