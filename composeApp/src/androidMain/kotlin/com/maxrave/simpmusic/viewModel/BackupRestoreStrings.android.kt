package com.maxrave.simpmusic.viewModel

import org.jetbrains.compose.resources.getString
import simpmusic.composeapp.generated.resources.Res

actual val restoreInProgress: String = getString(Res.string.restore_in_progress)
actual val restoreSuccess: String = getString(Res.string.restore_success)
actual val restoreFailed: String = getString(Res.string.restore_failed)
actual val backupInProgress: String = getString(Res.string.backup_in_progress)
actual val backupCreateSuccess: String = getString(Res.string.backup_create_success)
actual val backupCreateFailed: String = getString(Res.string.backup_create_failed)