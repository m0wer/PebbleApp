package io.rebble.libpebblecommon.services

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.packets.OutgoingVoicePacket
import io.rebble.libpebblecommon.packets.SessionSetupCommand
import io.rebble.libpebblecommon.packets.SessionType
import io.rebble.libpebblecommon.packets.VoiceAttribute
import io.rebble.libpebblecommon.packets.VoiceAttributeType
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.mapNotNull
import kotlin.uuid.Uuid

class VoiceService(private val protocolHandler: PebbleProtocolHandler) : ProtocolService {
    private val logger = Logger.withTag("VoiceService")

    val sessionSetupRequests = protocolHandler.inboundMessages
        .filterIsInstance<SessionSetupCommand>()
        .mapNotNull {
            val sessionType = SessionType.entries.firstOrNull { type ->
                type.value == it.sessionType.get()
            }
            if (sessionType == null) {
                logger.w { "Ignoring voice setup with unknown session type ${it.sessionType.get()}" }
                return@mapNotNull null
            }

            val uuidData = it.attributes.firstOrNull { attr ->
                attr.id.get() == VoiceAttributeType.AppUuid.value
            }?.content?.get()

            val uuid = if (uuidData != null) {
                val uuidAttr = VoiceAttribute.AppUuid()
                uuidAttr.fromBytes(DataBuffer(uuidData))
                uuidAttr.uuid.get()
            } else {
                Uuid.NIL
            }
            SessionSetupRequest(
                appUuid = uuid,
                sessionId = it.sessionId.get().toInt(),
                sessionType = sessionType,
                encoderInfo = VoiceEncoderInfo.fromProtocol(it.attributes)
            )
        }

    suspend fun send(packet: OutgoingVoicePacket) {
        protocolHandler.send(packet)
    }

    data class SessionSetupRequest(
        val appUuid: Uuid,
        val sessionId: Int,
        val sessionType: SessionType,
        val encoderInfo: VoiceEncoderInfo?,
    )
}
