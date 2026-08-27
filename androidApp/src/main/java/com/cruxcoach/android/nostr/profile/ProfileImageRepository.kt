package com.cruxcoach.android.nostr.profile

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the privacy boundary for profile images.
 *
 * A gallery selection is compressed and copied into [Context.getNoBackupFilesDir]; it does not
 * touch Blossom. Only [publish] may turn an owned local reference into a public Blossom URL.
 * Existing HTTPS URLs (for example a previously published profile) pass through unchanged.
 */
@Singleton
class ProfileImageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageProcessor: ImageProcessor,
    private val uploader: ProfileImageUploader,
) {
    enum class Slot(val filePrefix: String, val maxDimension: Int) {
        PICTURE("picture", ImageProcessor.MAX_DIMENSION_PICTURE),
        BANNER("banner", ImageProcessor.MAX_DIMENSION_BANNER),
    }

    /** Store the selected image privately and return a reference suitable for Coil + local DB. */
    suspend fun storeSelection(uri: Uri, slot: Slot): String {
        val bytes = imageProcessor.loadAndCompress(uri, slot.maxDimension)
        return try {
            withContext(Dispatchers.IO) {
                val directory = imageDirectory().apply { mkdirs() }
                check(directory.isDirectory) { "Could not create private profile image directory" }
                val target = File(directory, "${slot.filePrefix}_${UUID.randomUUID()}.jpg")
                val temporary = File(directory, ".${target.name}.tmp")
                try {
                    temporary.outputStream().use { output ->
                        output.write(bytes)
                        output.flush()
                    }
                    if (!temporary.renameTo(target)) {
                        throw IOException("Could not commit private profile image")
                    }
                    target.toURI().toString()
                } finally {
                    temporary.delete()
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Upload an owned local image after public-profile confirmation. Remote URLs and blank values
     * are already publishable and therefore pass through without another network request.
     */
    suspend fun publish(reference: String): ProfileImageUploader.Result {
        if (reference.isBlank()) return ProfileImageUploader.Result.Success(reference)
        val local = ownedFile(reference)
        if (local == null) {
            return if (reference.startsWith("https://")) {
                ProfileImageUploader.Result.Success(reference)
            } else {
                ProfileImageUploader.Result.Failure("unsupported image reference")
            }
        }
        return runCatching {
            val bytes = withContext(Dispatchers.IO) {
                if (!local.isFile) throw IOException("Private profile image is missing")
                local.readBytes()
            }
            try {
                uploader.upload(bytes)
            } finally {
                bytes.fill(0)
            }
        }.getOrElse { error ->
            ProfileImageUploader.Result.Failure(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Delete only files owned by this repository; arbitrary file:// references are ignored. */
    fun deleteIfOwned(reference: String?) {
        ownedFile(reference)?.delete()
    }

    internal fun isOwnedLocalReference(reference: String?): Boolean = ownedFile(reference) != null

    private fun ownedFile(reference: String?): File? {
        if (reference.isNullOrBlank()) return null
        val candidate = runCatching {
            val uri = java.net.URI(reference)
            if (uri.scheme != "file") return null
            File(uri).canonicalFile
        }.getOrNull() ?: return null
        val root = runCatching { imageDirectory().canonicalFile }.getOrNull() ?: return null
        return candidate.takeIf { it.parentFile == root && it.name.endsWith(".jpg") }
    }

    private fun imageDirectory(): File = File(context.noBackupFilesDir, DIRECTORY)

    private companion object {
        const val DIRECTORY = "profile_images"
    }
}
