package io.rebble.libpebblecommon.packets

import io.rebble.libpebblecommon.util.DataBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceTest {
    @Test
    fun sessionTypesParseFromSetupPackets() {
        SessionType.entries.forEach { expectedSessionType ->
            val setup = SessionSetupCommand().apply {
                m.fromBytes(
                    DataBuffer(
                        ubyteArrayOf(
                            VoiceCommand.SessionSetup.value,
                            0u, 0u, 0u, 0u,
                            expectedSessionType.value,
                            0x34u, 0x12u,
                            0u,
                        )
                    )
                )
            }

            assertEquals(
                expectedSessionType,
                SessionType.entries.single { it.value == setup.sessionType.get() },
            )
            assertEquals(0x1234u, setup.sessionId.get())
        }
    }
}
