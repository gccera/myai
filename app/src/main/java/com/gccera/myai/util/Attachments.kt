package com.gccera.myai.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Copies user-picked photos into app-private storage (downscaled JPEG) for vision chat. */
object Attachments {
    suspend fun saveImage(context: Context, uri: Uri, maxSide: Int = 1024): String = withContext(Dispatchers.IO) {
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > maxSide) { val s = maxSide.toFloat() / longest; d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt()) }
            }
        } else {
            @Suppress("DEPRECATION") val full = MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            val longest = maxOf(full.width, full.height)
            if (longest > maxSide) { val s = maxSide.toFloat() / longest; Bitmap.createScaledBitmap(full, (full.width * s).toInt(), (full.height * s).toInt(), true) } else full
        }
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        file.path
    }
}
