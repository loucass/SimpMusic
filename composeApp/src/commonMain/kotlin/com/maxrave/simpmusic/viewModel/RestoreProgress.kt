package com.maxrave.simpmusic.viewModel

/**
 * Restore/backup progress reported by platform-specific implementation
 */
sealed interface RestoreProgress {
    data class Started(val message: String) : RestoreProgress
    data class Step(val message: String, val current: Int, val total: Int) : RestoreProgress
    data class Completed(val message: String) : RestoreProgress
    data class Failed(val message: String) : RestoreProgress
    object Idle : RestoreProgress
}