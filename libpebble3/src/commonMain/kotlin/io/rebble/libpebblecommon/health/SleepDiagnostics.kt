package io.rebble.libpebblecommon.health

/** Flags appended to version-15 health minute records. */
object SleepDiagnosticFlags {
    const val SCORE_VALID = 1 shl 0
    const val DEFINITELY_NOT_WORN_INPUT = 1 shl 1
    const val COMPUTED_NOT_WORN = 1 shl 2
    const val SLEEP_MINUTE = 1 shl 3
    const val SESSION_ACTIVE = 1 shl 4
    const val SESSION_STARTED = 1 shl 5
    const val SESSION_ENDED = 1 shl 6
    const val SESSION_ACCEPTED = 1 shl 7
    const val SESSION_REJECTED = 1 shl 8
    const val REJECTED_NOT_WORN = 1 shl 9
    const val REJECTED_MOTION_QUALITY = 1 shl 10
    const val REJECTED_TOO_SHORT = 1 shl 11
    const val FRAGMENT_HELD = 1 shl 12
    const val FRAGMENT_DISCARDED = 1 shl 13
    const val FRAGMENT_ACCEPTED = 1 shl 14
    const val HRM_OFF_WRIST_INPUT = 1 shl 15
}

const val SLEEP_DIAGNOSTICS_SIZE_BYTES = 6

fun Int.hasSleepDiagnosticFlag(flag: Int): Boolean = (this and flag) != 0
