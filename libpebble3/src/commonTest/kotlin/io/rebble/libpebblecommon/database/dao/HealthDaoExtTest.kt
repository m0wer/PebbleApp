package io.rebble.libpebblecommon.database.dao

import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthDaoExtTest {
    @Test
    fun equalStepContextBackfillsLegacyRowWithoutDowngradingIt() {
        val legacy = minute()
        val contextual = minute(timezoneOffset15Minutes = -4)

        assertTrue(shouldReplaceHealthData(legacy, contextual))
        assertFalse(shouldReplaceHealthData(contextual, legacy))
    }

    @Test
    fun higherStepCountRetainsPriority() {
        assertTrue(shouldReplaceHealthData(minute(steps = 1), minute(steps = 2)))
        assertFalse(shouldReplaceHealthData(minute(steps = 2), minute(steps = 1, sleepIntentHint = 1)))
    }

    private fun minute(
        steps: Int = 1,
        sleepIntentHint: Int = 0,
        timezoneOffset15Minutes: Int = 0,
    ) = HealthDataEntity(
        timestamp = 1,
        steps = steps,
        orientation = 0,
        intensity = 0,
        lightIntensity = 0,
        activeMinutes = 0,
        restingGramCalories = 0,
        activeGramCalories = 0,
        distanceCm = 0,
        sleepIntentHint = sleepIntentHint,
        timezoneOffset15Minutes = timezoneOffset15Minutes,
    )
}
