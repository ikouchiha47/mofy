package com.mofy.app.watchtogether

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * ZXing-backed QR decode - the decode side of QrScanScreen's join-by-scan,
 * replacing ML Kit (proprietary Google binary, F-Droid rejection). Pure
 * ZXing classes only (same library QrCode.kt already uses for encoding), so
 * JVM unit tests can drive it directly from a [LuminanceSource] with no
 * Android framework. The camera frame is converted to a LuminanceSource by
 * the screen.
 */
object QrDecoder {

    /** Decodes QR payload text from a [LuminanceSource]; null when no QR found. */
    fun decode(source: LuminanceSource): String? = try {
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        QRCodeReader().decode(bitmap).text
    } catch (e: NotFoundException) {
        // No QR in this frame - scan continues.
        null
    }
}