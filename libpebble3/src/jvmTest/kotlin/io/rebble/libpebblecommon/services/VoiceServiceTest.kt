package io.rebble.libpebblecommon.services

import TestPebbleProtocolHandler
import io.rebble.libpebblecommon.packets.SessionSetupCommand
import io.rebble.libpebblecommon.packets.SessionType
import io.rebble.libpebblecommon.packets.VoiceCommand
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceServiceTest {
    @Test
    fun sessionSetupRequestsMapAllKnownSessionTypes() = runTest {
        val handler = TestPebbleProtocolHandler { }
        val service = VoiceService(handler)
        val requests = mutableListOf<VoiceService.SessionSetupRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            service.sessionSetupRequests.take(SessionType.entries.size).toList(requests)
        }

        SessionType.entries.forEach { sessionType ->
            handler.receivePacket(sessionSetup(sessionType.value))
        }

        assertEquals(SessionType.entries.toList(), requests.map { it.sessionType })
    }

    @Test
    fun unknownSessionTypeIsSkippedWithoutCancellingRequestFlow() = runTest {
        val handler = TestPebbleProtocolHandler { }
        val service = VoiceService(handler)
        val requests = mutableListOf<VoiceService.SessionSetupRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            service.sessionSetupRequests.take(1).toList(requests)
        }

        handler.receivePacket(sessionSetup(0xffu))
        handler.receivePacket(sessionSetup(SessionType.NLP.value))

        assertEquals(listOf(SessionType.NLP), requests.map { it.sessionType })
    }

    private fun sessionSetup(sessionType: UByte) = SessionSetupCommand().apply {
        command.set(VoiceCommand.SessionSetup.value)
        flags.set(0u)
        this.sessionType.set(sessionType)
        sessionId.set(0x1234u)
        attributeCount.set(0u)
    }
}
