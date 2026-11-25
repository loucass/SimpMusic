# Plan: Fix Backup/Restore System - Critical Bug Fixes

## Overview
Fix the critical "Database is closed" crash after restore, ensure all media files are restored, add proper progress indicator, and fix artwork priority.

## Architecture Decisions
1. **Move backup/restore to data layer** (`core/data`) - single source of truth
2. **Proper Room reinitialization** after restore - create new Room instance
3. **Complete file restore** - databases + ALL media files (downloads, artwork, canvas)
4. **Progress tracking** - real-time progress for manual restore
5. **Graceful restart** - both Android and Desktop
6. **Artwork priority** - Canvas > Apple Music > Normal during restore/playback

## Task List

### Phase 1: Core Data Layer - Single Backup/Restore Implementation

- [ ] **Task 1**: Create `BackupData` and `RestoreProgress` types in `core/data/commonMain`
  - Acceptance: Sealed interface for progress, data class for backup contents
  - Verify: Compiles on all platforms
  - Files: `core/data/src/commonMain/kotlin/com/maxrave/data/repository/BackupRestore.kt`

- [ ] **Task 2**: Add `backupTo()` and `restoreFrom()` to `CommonRepository` interface
  - Acceptance: Interface declares suspend functions with progress callback
  - Verify: Compiles
  - Files: `core/domain/src/commonMain/kotlin/com/maxrave/domain/repository/CommonRepository.kt`

- [ ] **Task 3**: Implement `backupTo()` in `CommonRepositoryImpl` (commonMain)
  - Acceptance: Delegates to platform-specific `backupNative()`, includes all folders
  - Verify: Unit test for zip logic
  - Files: `core/data/src/commonMain/kotlin/com/maxrave/data/repository/CommonRepositoryImpl.kt`

- [ ] **Task 4**: Implement `restoreFrom()` in `CommonRepositoryImpl` (commonMain)
  - Acceptance: Delegates to platform-specific `restoreNative()`, proper Room reinit
  - Verify: Unit test
  - Files: `core/data/src/commonMain/kotlin/com/maxrave/data/repository/CommonRepositoryImpl.kt`

- [ ] **Task 5**: Android `backupNative()` implementation
  - Acceptance: Backups settings, main DB, ExoPlayer DB, downloads, artwork, canvas folders
  - Verify: Creates valid .zip, CI passes
  - Files: `core/data/src/androidMain/kotlin/com/maxrave/data/repository/CommonRepositoryImpl.android.kt`

- [ ] **Task 6**: Android `restoreNative()` implementation - CRITICAL FIX
  - Acceptance: 
    - Stops media service
    - Closes Room DB
    - Restores ALL files (DBs + media folders)
    - **Recreates Room database instance** (not just verifies)
    - Reinitializes all DAOs
    - Verifies both DBs work with Room
    - Triggers graceful restart
  - Verify: Manual restore works, no "Database is closed" crash
  - Files: `core/data/src/androidMain/kotlin/com/maxrave/data/repository/CommonRepositoryImpl.android.kt`

- [ ] **Task 7**: Desktop `backupNative()` implementation
  - Acceptance: Backups settings, main DB (no ExoPlayer DB on Desktop)
  - Verify: Creates valid .zip, CI passes
  - Files: `core/data/src/jvmMain/kotlin/com/maxrave/data/repository/CommonRepositoryImpl.jvm.kt`

- [ ] **Task 8**: Desktop `restoreNative()` implementation
  - Acceptance: Restores files, recreates Room DB, graceful restart
  - Verify: Works on Desktop
  - Files: `core/data/src/jvmMain/kotlin/com/maxrave/data/repository/CommonRepositoryImpl.jvm.kt`

### Checkpoint: Core Data Layer Complete
- [ ] All tests pass
- [ ] CI builds green (jvmTest + assembleDebug)

### Phase 2: Presentation Layer - Clean Integration

- [ ] **Task 9**: Update `SettingsViewModel` to use `commonRepository.backupTo()`/`restoreFrom()`
  - Acceptance: Removes expect/actual backupNative/restoreNative, calls repository
  - Verify: Manual backup/restore works from Settings UI
  - Files: `composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/viewModel/SettingsViewModel.kt`

- [ ] **Task 10**: Remove old expect/actual backup functions from SettingsViewModel
  - Acceptance: No more `backupNative`/`restoreNative` in expect declarations
  - Verify: Compiles
  - Files: `composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/viewModel/SettingsViewModel.kt`

- [ ] **Task 11**: Add `restartAppAfterRestore()` expect/actual for graceful restart
  - Acceptance: Android restarts via Intent, Desktop via exitProcess
  - Verify: App restarts after restore
  - Files: `SettingsViewModel.kt`, `.android.kt`, `.jvm.kt`

### Phase 3: AutoBackupWorker - Use Shared Implementation

- [ ] **Task 12**: Update `AutoBackupWorker` to use `commonRepository.backupTo()`
  - Acceptance: Removes duplicate backup logic, uses shared implementation
  - Verify: Auto backup works, saves to Downloads/SimpMusic
  - Files: `androidApp/src/main/java/com/maxrave/simpmusic/service/backup/AutoBackupWorker.kt`

### Phase 4: Progress Indicator & UX

- [ ] **Task 13**: Add real-time progress UI in Settings restore flow
  - Acceptance: Progress dialog shows "Restoring X of Y files", doesn't dismiss until truly complete
  - Verify: User sees progress, knows when done
  - Files: `SettingsViewModel.kt`, Settings UI composable

- [ ] **Task 14**: Show "Restore complete - restarting app..." before restart
  - Acceptance: User sees final message, app restarts automatically
  - Verify: No manual intervention needed

### Phase 5: Artwork Priority System

- [ ] **Task 15**: Implement artwork priority: Canvas > Apple Music > Normal
  - Acceptance: During playback/restore, canvas files take precedence, then Apple Music animated, then normal covers
  - Verify: Correct artwork displays in all scenarios
  - Files: `ArtworkStore`, `CanvasStore`, playback logic

### Phase 6: Verification & Testing

- [ ] **Task 16**: Manual test full backup/restore cycle on device
  - Acceptance: 
    - Backup creates valid .zip with all folders
    - Restore completes without crash
    - All downloaded songs play offline
    - Artwork/canvas displays correctly
    - App restarts automatically

- [ ] **Task 17**: Run CI and all tests
  - Acceptance: `jvmTest` passes, `assembleDebug` passes

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Room recreation fails | High | Test extensively, use Room's `openHelper` pattern |
| Media files not restored | High | Verify each folder in backup/restore |
| Restart doesn't work on some devices | Medium | Test on multiple Android versions |
| Progress UI blocks main thread | Low | Use proper coroutines/Dispatchers |

## Open Questions

1. Should we also backup/restore `exoplayer_internal.db-wal` and `-shm`? (Current: No, checkpoint handles it)
2. How to handle partial restore failures? (Current: Fail fast, throw exception)

---

**Approved by**: _______________  
**Date**: _______________