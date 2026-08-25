package coredevices.ring.ui.screens.settings

import coredevices.util.models.CactusSTTMode
import coredevices.util.CloudTranscriptionProvider
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechSectionTest {

    @Test
    fun onlyTheEnginesThatReachTheCloudNeedAnAccount() {
        assertFalse(CactusSTTMode.LocalOnly.needsSignIn())
        assertFalse(CactusSTTMode.PlatformOnly.needsSignIn())
        assertTrue(CactusSTTMode.RemoteOnly.needsSignIn())
        assertTrue(CactusSTTMode.RemoteFirst.needsSignIn())
        assertTrue(CactusSTTMode.LocalFirst.needsSignIn())
        assertFalse(CactusSTTMode.RemoteOnly.needsSignIn(CloudTranscriptionProvider.OpenAI))
        assertFalse(CactusSTTMode.RemoteFirst.needsSignIn(CloudTranscriptionProvider.OpenAI))
    }

    @Test
    fun everyOfferedEngineIsNamed() {
        indexSpeechModes.forEach {
            assertTrue(it.speechEngineName().isNotBlank(), "$it has no name")
        }
    }
}
