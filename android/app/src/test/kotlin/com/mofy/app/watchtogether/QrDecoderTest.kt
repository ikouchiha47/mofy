package com.mofy.app.watchtogether

import com.google.zxing.BarcodeFormat
import com.google.zxing.LuminanceSource
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Test-first for QrDecoder (ZXing decode side of QrScanScreen). The encode
 * side (QrCode.kt) already uses ZXing's QRCodeWriter - this proves the decode
 * round-trips the exact RoomCode.toDeepLink payload back through
 * RoomCode.parseDeepLink, using only pure-JVM ZXing classes (no ML Kit, no
 * Android framework) so it runs on the desktop test build.
 */
class QrDecoderTest {

    /** Renders a QR for [content] into a ZXing [LuminanceSource] (pure JVM). */
    private fun luminanceFromQr(content: String, sizePx: Int = 256): LuminanceSource {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        return object : LuminanceSource(sizePx, sizePx) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray {
                var r = row
                if (r == null || r.size < width) r = ByteArray(width)
                for (x in 0 until width) r[x] = if (matrix.get(x, y)) 0x00 else 0xFF.toByte()
                return r
            }

            override fun getMatrix(): ByteArray {
                val m = ByteArray(width * height)
                for (y in 0 until height) for (x in 0 until width) {
                    m[y * width + x] = if (matrix.get(x, y)) 0x00 else 0xFF.toByte()
                }
                return m
            }
        }
    }

    private fun blankSource(w: Int = 64, h: Int = 64): LuminanceSource =
        object : LuminanceSource(w, h) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray {
                val r = ByteArray(width)
                r.fill(0xFF.toByte())
                return r
            }

            override fun getMatrix(): ByteArray {
                val m = ByteArray(width * height)
                m.fill(0xFF.toByte())
                return m
            }
        }

    @Test
    fun `decodes a QR generated from a room deep link back to its payload`() {
        // RoomKey alphabet excludes 0/O/1/I - 7FK9Q2 is the canonical valid key.
        val payload = RoomCode.toDeepLink("7FK9Q2")

        val decoded = QrDecoder.decode(luminanceFromQr(payload))

        assertNotNull(decoded, "should decode the QR payload")
        assertEquals(payload, decoded)
        assertEquals("7FK9Q2", RoomCode.parseDeepLink(decoded!!)?.roomKey)
    }

    @Test
    fun `decodes a deep link carrying the LAN signaling url query`() {
        val payload = RoomCode.toDeepLink("XYZ789", "ws://192.168.1.20:8787")

        val decoded = QrDecoder.decode(luminanceFromQr(payload))

        assertEquals(payload, decoded)
        val parsed = RoomCode.parseDeepLink(decoded!!)
        assertEquals("XYZ789", parsed?.roomKey)
        assertEquals("ws://192.168.1.20:8787", parsed?.signalingUrl)
    }

    @Test
    fun `returns null for an image with no QR`() {
        assertNull(QrDecoder.decode(blankSource()), "blank frame must not decode")
    }
}