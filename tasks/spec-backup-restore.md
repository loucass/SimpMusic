# Spec: Reliable Backup & Restore System for SimpMusic

## Objective

Build a **single, reliable backup/restore system** that:
- Works for both manual (Settings UI) and automatic (WorkManager) backups
- Properly handles app restart after restore on both Android and Desktop
- Supports progress reporting for manual backups
- Includes all user data: databases, downloaded media, pinned artwork, canvas/animated artwork
- Follows Clean Architecture (data layer owns persistence)
- Compiles and passes CI on both Android and Desktop

## Tech Stack

- **Language**: Kotlin (Multiplatform)
- **Platforms**: Android (API 26+), Desktop (JVM)
- **Build**: Gradle (Kotlin DSL)
- **Architecture**: Clean Architecture + MVVM
- **DI**: Koin
- **Zip**: `java.util.zip` (standard library)
- **SQLite**: `android.database.sqlite` (Android), `org.sqlite` (Desktop) or direct file copy

## Commands

```bash
# Build
./gradlew :composeApp:jvmTest --no-daemon
./gradlew :androidApp:assembleDebug -PciAbi=arm64-v8a --no-daemon

# Test
./gradlew :composeApp:jvmTest --no-daemon
./gradlew :core:data:jvmTest --no-daemon

# Lint
./gradlew :composeApp:lint --no-daemon
```

## Project Structure

```
core/data/src/
├── commonMain/kotlin/com/maxrave/data/repository/
│   ├── CommonRepository.kt              # Interface (add backupTo/restoreFrom)
│   ├── CommonRepositoryImpl.kt          # Common implementation delegating to platform
│   ├── BackupRestore.kt                 # Common types: BackupData, RestoreProgress
│   └── BackupRestoreUtils.kt            # Shared zip/folder utilities
├── androidMain/kotlin/com/maxrave/data/repository/
│   └── CommonRepositoryImpl.android.kt  # Android backup/restore implementation
├── jvmMain/kotlin/com/maxrave/data/repository/
│   └── CommonRepositoryImpl.jvm.kt      # Desktop backup/restore implementation
└── iosMain/kotlin/com/maxrave/data/repository/
    └── CommonRepositoryImpl.ios.kt      # iOS stub (not supported)

composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/viewModel/
├── SettingsViewModel.kt                 # Calls commonRepository.backupTo/restoreFrom
├── SettingsViewModel.android.kt         # Platform-specific: readPickedFile, restartAppAfterRestore
├── SettingsViewModel.jvm.kt             # Platform-specific: readPickedFile, restartAppAfterRestore
└── BackupRestoreStrings.kt              # Expect/actual strings

androidApp/src/main/java/com/maxrave/simpmusic/service/backup/
└── AutoBackupWorker.kt                  # Uses commonRepository.backupTo(), saves to Downloads
```

## Code Style

**Example: Platform-specific implementation pattern**

```kotlin
// commonMain - CommonRepositoryImpl.kt
actual suspend fun backupTo(...) {
    backupNative(uri, backupDownloaded, progressCallback, ...)
}

// androidMain - CommonRepositoryImpl.android.kt
actual suspend fun backupNative(...) {
    // Android-specific: ContentResolver, filesDir, ExoPlayer DB
}

// jvmMain - CommonRepositoryImpl.jvm.kt
actual suspend fun backupNative(...) {
    // Desktop-specific: File IO, ~/.simpmusic
}
```

**Naming Conventions:**
- Platform-specific functions: `actual suspend fun backupNative(...)`
- Common types: `BackupData`, `RestoreProgress` (sealed interface)
- String resources: `backupInProgress`, `restoreSuccess`, etc.

## Testing Strategy

- **Unit Tests** (JVM): `core/data/src/jvmTest/` - test zip logic, folder utilities
- **Integration Tests**: Manual verification on device/emulator
- **CI**: `jvmTest` + `assembleDebug` on every push

## Boundaries

### Always Do:
- Single source of truth for backup/restore logic in `core/data`
- Platform-specific code only in `androidMain`/`jvmMain`/`iosMain`
- Progress reporting via `RestoreProgress` sealed interface
- Proper app restart after restore (both platforms)
- Verify databases after restore before restart

### Ask First:
- Database schema changes
- Adding new folders to backup
- Changing backup file format

### Never Do:
- Duplicate backup logic in `AutoBackupWorker` or `SettingsViewModel`
- Put backup logic in presentation layer (`composeApp`)
- Call non-existent `DownloadHandler` methods
- Swallow exceptions silently

## Success Criteria

1. **Manual backup** (Settings → Backup):
   - Creates `.zip` with: settings, main DB, ExoPlayer DB (if downloaded), download folder, artwork, canvas
   - Shows progress: "Backing up settings..." → "Backing up database..." → "Backing up downloaded files..." → "Backing up artwork..." → "Backing up canvas..."
   - Completes with success toast

2. **Manual restore** (Settings → Restore):
   - Stops media service
   - Restores all files, clears target folders first
   - Verifies both databases can be opened
   - Restarts app gracefully (Android: Intent restart; Desktop: exitProcess)

3. **Auto backup** (WorkManager):
   - Runs daily on WiFi
   - Uses same `commonRepository.backupTo()` as manual
   - Saves to `Downloads/SimpMusic/simpmusic_backup_YYYYMMDD_HHMMSS.zip`
   - Keeps last N backups (configurable)
   - No progress UI needed

4. **Cross-platform**:
   - Android: Uses `ContentResolver`, `filesDir`, ExoPlayer DB path
   - Desktop: Uses `File` IO, `~/.simpmusic`, no ExoPlayer DB
   - Both compile and pass CI

5. **No regressions**:
   - All existing tests pass
   - CI builds green on both platforms

## Open Questions

1. Should iOS stub throw or just log "not supported"?
2. Should backup include `exoplayer_internal.db-wal`/`-shm` files? (Currently: no, checkpoint handles it)
3. Should auto backup run on app start if overdue? (Currently: WorkManager handles scheduling)

---

**Approved by**: _______________  
**Date**: _______________