# Task List: Backup/Restore Critical Fixes

## Phase 1: Core Data Layer

- [ ] Task 1: Create BackupRestore types in core/data/commonMain
- [ ] Task 2: Add backupTo/restoreFrom to CommonRepository interface (domain)
- [ ] Task 3: Implement backupTo in CommonRepositoryImpl (commonMain)
- [ ] Task 4: Implement restoreFrom in CommonRepositoryImpl (commonMain)
- [ ] Task 5: Android backupNative implementation (core/data/androidMain)
- [ ] Task 6: Android restoreNative implementation - CRITICAL FIX (core/data/androidMain)
- [ ] Task 7: Desktop backupNative implementation (core/data/jvmMain)
- [ ] Task 8: Desktop restoreNative implementation (core/data/jvmMain)

**Checkpoint: Core Data Layer**

### Phase 2: Presentation Layer

- [ ] Task 9: SettingsViewModel uses commonRepository.backupTo/restoreFrom
- [ ] Task 10: Remove old expect/actual backup functions
- [ ] Task 11: Add restartAppAfterRestore() expect/actual

### Phase 3: AutoBackupWorker

- [ ] Task 12: AutoBackupWorker uses commonRepository.backupTo()

### Phase 4: Progress Indicator & UX

- [ ] Task 13: Real-time progress UI in Settings
- [ ] Task 14: "Restore complete - restarting..." message

### Phase 5: Artwork Priority

- [ ] Task 15: Canvas > Apple Music > Normal artwork priority

### Phase 6: Verification

- [ ] Task 16: Manual test full cycle on device
- [ ] Task 17: CI passes (jvmTest + assembleDebug)

---

## Current Status: Ready to start Task 1