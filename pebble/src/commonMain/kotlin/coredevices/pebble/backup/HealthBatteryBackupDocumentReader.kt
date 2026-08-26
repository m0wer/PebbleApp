package coredevices.pebble.backup

import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readString

object HealthBatteryBackupDocumentReader {
    const val MAX_DOCUMENT_BYTES = 100L * 1024L * 1024L

    fun readUtf8(source: Source, maxDocumentBytes: Long = MAX_DOCUMENT_BYTES): String {
        require(maxDocumentBytes >= 0) { "Maximum document size must not be negative." }
        val document = Buffer()
        val chunk = Buffer()
        var bytesRead = 0L
        while (true) {
            val read = source.readAtMostTo(chunk, 64L * 1024L)
            if (read == -1L) break
            bytesRead += read
            require(bytesRead <= maxDocumentBytes) { "Backup exceeds the maximum document size." }
            chunk.transferTo(document)
        }
        return document.readString()
    }
}
