package io.rebble.libpebblecommon.connection.endpointmanager.audio

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceRecordingChunkingTest {
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun base64ChunksDoNotExceedProtocolLimit() {
        val input = ByteArray(RECORDING_CHUNK_BYTES * 2 + 1) { it.toByte() }
        val chunks = input.recordingChunks().toList()

        assertEquals(listOf(RECORDING_CHUNK_BYTES, RECORDING_CHUNK_BYTES, 1), chunks.map { it.size })
        assertTrue(chunks.all { Base64.encode(it).length <= MAX_RECORDING_BASE64_CHARS })
        assertContentEquals(input, chunks.flatMap { it.asList() }.toByteArray())
    }
}
