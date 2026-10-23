package com.mqclient.topology

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * 把画布录制层拿到的 [ImageBitmap] 编码成 PNG 字节。
 *
 * Compose Desktop 的 Skia 后端直接 `asSkiaBitmap()` 交给 `Image.makeFromBitmap`，
 * 避免绕 AWT BufferedImage（EDT / headless 兼容性都更稳）。编码失败返回异常，
 * 由调用方转成 Snackbar 文案。
 */
internal fun ImageBitmap.toPngByteArray(): ByteArray {
    val skiaImage = Image.makeFromBitmap(asSkiaBitmap())
    return try {
        skiaImage.encodeToData(EncodedImageFormat.PNG)?.bytes
            ?: error("PNG encoding produced no data")
    } finally {
        skiaImage.close()
    }
}
