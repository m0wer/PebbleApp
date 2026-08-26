package io.rebble.libpebblecommon.packets

import assertUByteArrayEquals
import io.rebble.libpebblecommon.di.CommonPhoneCapabilities
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchAppDataBackupTest {
    @Test
    fun `request and dynamic response use big endian framing`() {
        val request = WatchAppDataBackupRequest(
            WatchAppDataBackupCommand.PUT_RECORD,
            0x1234u,
            0x89abcdefu,
            ubyteArrayOf(0xdeu, 0xadu),
        )
        assertUByteArrayEquals(
            ubyteArrayOf(
                0u, 10u, 0x23u, 0x29u,
                0x21u, 1u, 0x12u, 0x34u, 0x89u, 0xabu, 0xcdu, 0xefu, 0xdeu, 0xadu,
            ),
            request.serialize(),
        )

        val responseBytes = ubyteArrayOf(
            0u, 12u, 0x23u, 0x29u,
            0xa1u, 1u, 0x12u, 0x34u, 0x89u, 0xabu, 0xcdu, 0xefu, 0u, 9u, 0xbeu, 0xefu,
        )
        val response = PebblePacket.deserialize(responseBytes) as WatchAppDataBackupResponse

        assertEquals(0xa1u, response.command.get())
        assertEquals(0x1234u, response.requestId.get())
        assertEquals(0x89abcdefu, response.transactionId.get())
        assertEquals(WatchAppDataBackupStatus.STALE_SNAPSHOT.value, response.status.get())
        assertTrue(response.payload.get().contentEquals(ubyteArrayOf(0xbeu, 0xefu)))
    }

    @Test
    fun `capability bit 25 encodes and decodes`() {
        val flags = ProtocolCapsFlag.makeFlags(listOf(ProtocolCapsFlag.SupportsWatchAppDataBackup))

        assertUByteArrayEquals(ubyteArrayOf(0u, 0u, 0u, 2u, 0u, 0u, 0u, 0u), flags)
        assertEquals(
            setOf(ProtocolCapsFlag.SupportsWatchAppDataBackup),
            ProtocolCapsFlag.fromFlags(flags),
        )
        assertTrue(ProtocolCapsFlag.SupportsWatchAppDataBackup in CommonPhoneCapabilities)
    }
}
