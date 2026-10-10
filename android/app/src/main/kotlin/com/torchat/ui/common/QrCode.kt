package com.torchat.ui.common

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

/** A contact code as a QR image, and a way to hand that image to another app. */
object QrCode {

    /**
     * Encode [text] at one pixel per module; the ImageView scales it up with
     * filtering off so the squares stay hard-edged.
     *
     * Error correction is deliberately the lowest level: a card is around 580
     * bytes, close to what a QR can hold, and every step up in correction costs
     * capacity that a longer display name would then push past. The code is
     * read off a screen held a few inches away, not off a poster, so there is
     * nothing for the redundancy to recover from.
     */
    fun encode(text: String, sizePx: Int = 0): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            val row = y * matrix.width
            for (x in 0 until matrix.width) {
                pixels[row + x] = if (matrix[x, y]) BLACK else WHITE
            }
        }
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        }
    }.getOrNull()

    /**
     * Share a QR as an image through the app's FileProvider: the other app gets
     * a temporary read grant on one content:// URI, not a path into our files.
     */
    fun shareIntent(context: Context, text: String, title: String): Intent? {
        val bitmap = encode(text, SHARE_PX) ?: return null
        val directory = File(context.cacheDir, "qr").apply { mkdirs() }
        val file = File(directory, "torchat-code.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(title, uri)
        return Intent.createChooser(send, title)
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val SHARE_PX = 720
}
