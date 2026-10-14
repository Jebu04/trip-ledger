package app.tripledger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Receipt photos. They live in the app's private storage as down-scaled JPEGs, so they need no
 * storage permission and never leave the device. An expense stores only the file name.
 */
class Receipts(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "receipts").apply { mkdirs() }

    fun file(name: String): File = File(dir, name)

    fun exists(name: String): Boolean = name.isNotBlank() && file(name).isFile

    /**
     * Where the camera app should write a new picture: a temporary file name and the content URI
     * to hand to the camera. Call [adoptCapture] with the name once the picture has been taken.
     */
    fun newCaptureTarget(): Pair<String, Uri> {
        val name = "capture-${UUID.randomUUID()}.jpg"
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file(name))
        return name to uri
    }

    /** Shrinks a full-size camera capture into a stored receipt. Returns its file name, or null on failure. */
    suspend fun adoptCapture(tempName: String): String? = withContext(Dispatchers.IO) {
        val temp = file(tempName)
        try {
            if (!temp.isFile || temp.length() == 0L) return@withContext null
            store(decode({ temp.inputStream() }) ?: return@withContext null)
        } finally {
            temp.delete()
        }
    }

    /** Copies a picture chosen in the photo picker into the app's storage. */
    suspend fun importFrom(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            store(decode({ context.contentResolver.openInputStream(uri) ?: error("cannot open") }) ?: return@runCatching null)
        }.getOrNull()
    }

    fun delete(name: String) {
        if (name.isNotBlank()) file(name).delete()
    }

    /**
     * Removes photos that no expense refers to any more. Files newer than a day are left alone:
     * they may belong to an expense that is still being typed, or to a deletion that can be undone.
     */
    suspend fun cleanup(inUse: Set<String>) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        dir.listFiles()?.forEach { f -> if (f.name !in inUse && f.lastModified() < cutoff) f.delete() }
    }

    /** Loads a stored photo scaled down so its longer side is roughly [maxSize] pixels. */
    suspend fun load(name: String, maxSize: Int): Bitmap? = withContext(Dispatchers.IO) {
        val f = file(name)
        if (!f.isFile) return@withContext null
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize) }
            BitmapFactory.decodeFile(f.path, options)
        }.getOrNull()
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** Decodes a picture at a sensible size and turns it upright according to its EXIF data. */
    private fun decode(open: () -> java.io.InputStream): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE) }
        var bitmap = open().use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / longest
            bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        }
        val degrees = runCatching {
            open().use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrDefault(0f)
        if (degrees != 0f) {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
        }
        return bitmap
    }

    private fun store(bitmap: Bitmap): String {
        val name = "receipt-${UUID.randomUUID()}.jpg"
        file(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return name
    }

    companion object {
        const val AUTHORITY = "app.tripledger.files"
        private const val MAX_SIDE = 1600
    }
}
