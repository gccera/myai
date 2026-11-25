package com.gccera.myai.data.repo

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.gccera.myai.R
import com.gccera.myai.data.db.ImageDao
import com.gccera.myai.data.db.ImageEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Generated images live in app-private storage (files/images) and in the Room `images` table. */
@Singleton
class ImageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ImageDao,
) {
    private val dir = File(context.filesDir, "images").apply { mkdirs() }

    val images = dao.observeAll()
    fun image(id: Long) = dao.observe(id)

    suspend fun save(bitmap: Bitmap, entity: ImageEntity): Long = withContext(Dispatchers.IO) {
        val file = File(dir, "${UUID.randomUUID()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        dao.insert(entity.copy(filePath = file.path))
    }

    suspend fun pathOf(id: Long): String = dao.get(id)?.filePath ?: ""

    suspend fun get(id: Long) = dao.get(id)

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dao.get(id)?.let { File(it.filePath).delete() }
        dao.delete(id)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Needed only on Android 8–9 (API < 29) to write to the public Pictures folder. */
    fun needsLegacyStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /** Copies an image to the phone gallery (Pictures/<App name>) — only when the user asks. */
    suspend fun exportToGallery(img: ImageEntity): Unit = withContext(Dispatchers.IO) {
        val appName = context.getString(R.string.app_name)
        val name = "${appName}_${img.id}_${img.createdAt}.png"
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$appName")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create gallery entry")
            resolver.openOutputStream(uri)?.use { out -> File(img.filePath).inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val pictures = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), appName)
            pictures.mkdirs()
            val dest = File(pictures, name)
            File(img.filePath).copyTo(dest, overwrite = true)
            @Suppress("DEPRECATION")
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DATA, dest.path)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            }
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }
    }

    fun loadBitmap(path: String): Bitmap? = BitmapFactory.decodeFile(path)
}
