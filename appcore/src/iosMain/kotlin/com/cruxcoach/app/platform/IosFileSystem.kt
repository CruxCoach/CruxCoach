package com.cruxcoach.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/** [FileSystem] on NSFileManager. Failures are return values; nothing here throws. */
@OptIn(ExperimentalForeignApi::class)
class IosFileSystem : FileSystem {
    private val manager: NSFileManager get() = NSFileManager.defaultManager

    override fun workDirectory(): String {
        val base = (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).firstOrNull() as? NSURL)
            ?.path ?: NSTemporaryDirectory().trimEnd('/')
        val path = "$base/$WORK_DIRECTORY_NAME"
        if (!manager.fileExistsAtPath(path)) {
            manager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        }
        // Downloads and staging are re-creatable and must not reach iCloud/iTunes backups.
        NSURL.fileURLWithPath(path, isDirectory = true)
            .setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
        return path
    }

    override fun exists(path: String): Boolean = manager.fileExistsAtPath(path)

    override fun size(path: String): Long {
        val attributes = manager.attributesOfItemAtPath(path, error = null) ?: return -1L
        return (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: -1L
    }

    override fun delete(path: String): Boolean {
        if (!manager.fileExistsAtPath(path)) return true
        return manager.removeItemAtPath(path, error = null)
    }

    override fun move(from: String, to: String): Boolean {
        if (!manager.fileExistsAtPath(from)) return false
        if (from == to) return true
        if (manager.fileExistsAtPath(to)) {
            // Atomic swap where the volume supports it.
            val replaced = manager.replaceItemAtURL(
                originalItemURL = NSURL.fileURLWithPath(to),
                withItemAtURL = NSURL.fileURLWithPath(from),
                backupItemName = null,
                options = 0u,
                resultingItemURL = null,
                error = null,
            )
            if (replaced) return true
            if (!manager.fileExistsAtPath(from)) return false
            if (!manager.removeItemAtPath(to, error = null)) return false
        }
        return manager.moveItemAtPath(from, toPath = to, error = null)
    }

    override fun readBytes(path: String, maxBytes: Long): ByteArray? {
        if (maxBytes < 0L) return null
        val declared = size(path)
        if (declared < 0L || declared > maxBytes || declared > Int.MAX_VALUE) return null
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        // The file may have grown between the size check and the read.
        if (data.length > maxBytes.toULong()) return null
        return data.toByteArray()
    }

    override fun writeBytes(path: String, data: ByteArray): Boolean =
        data.toNSData().writeToFile(path, atomically = true)

    override fun freeSpaceBytes(): Long {
        val directory = workDirectory()
        val values = NSURL.fileURLWithPath(directory, isDirectory = true)
            .resourceValuesForKeys(listOf(NSURLVolumeAvailableCapacityForImportantUsageKey), error = null)
        val important = (values?.get(NSURLVolumeAvailableCapacityForImportantUsageKey) as? NSNumber)?.longLongValue
        if (important != null && important > 0L) return important
        val attributes = manager.attributesOfFileSystemForPath(directory, error = null) ?: return 0L
        return (attributes[NSFileSystemFreeSize] as? NSNumber)?.longLongValue ?: 0L
    }

    private companion object {
        const val WORK_DIRECTORY_NAME = "CruxCoachWork"
    }
}
