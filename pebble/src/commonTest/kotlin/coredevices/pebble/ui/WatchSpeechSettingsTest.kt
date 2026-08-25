package coredevices.pebble.ui

import coredevices.util.CloudTranscriptionProvider
import coredevices.util.OpenAITranscriptionConfig
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchSpeechSettingsTest {
    @Test
    fun onlyWisprCloudModesRequireCoreAccount() {
        assertTrue(CactusSTTMode.RemoteOnly.requiresCoreAccount(CloudTranscriptionProvider.Wispr))
        assertFalse(CactusSTTMode.RemoteOnly.requiresCoreAccount(CloudTranscriptionProvider.OpenAI))
        assertFalse(CactusSTTMode.LocalOnly.requiresCoreAccount(CloudTranscriptionProvider.Wispr))
        assertFalse(CactusSTTMode.PlatformOnly.requiresCoreAccount(CloudTranscriptionProvider.Wispr))
    }

    @Test
    fun downloadedModelPreservesCloudConfiguration() {
        val openAI = OpenAITranscriptionConfig("https://example.com/v1/audio/transcriptions", "whisper", "Names")
        val original = STTConfig(
            mode = CactusSTTMode.RemoteOnly,
            cloudProvider = CloudTranscriptionProvider.OpenAI,
            openAI = openAI,
        )

        assertEquals(
            original.copy(mode = CactusSTTMode.LocalFirst, modelName = "model"),
            original.withDownloadedModel(CactusSTTMode.LocalFirst, "model"),
        )
    }
}
