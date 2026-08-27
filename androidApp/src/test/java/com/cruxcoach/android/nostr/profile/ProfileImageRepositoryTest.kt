package com.cruxcoach.android.nostr.profile

import android.content.Context
import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileImageRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = mockk<Context>()
    private val processor = mockk<ImageProcessor>()
    private val uploader = mockk<ProfileImageUploader>()

    @Test
    fun `selection stays in no-backup storage until publish`() = runTest {
        val noBackup = temporaryFolder.newFolder("no_backup")
        val selectedUri = mockk<Uri>()
        val jpeg = byteArrayOf(1, 2, 3, 4)
        every { context.noBackupFilesDir } returns noBackup
        coEvery {
            processor.loadAndCompress(
                selectedUri,
                ImageProcessor.MAX_DIMENSION_PICTURE,
                any(),
            )
        } returns jpeg.copyOf()
        coEvery { uploader.upload(any(), any()) } answers {
            assertArrayEquals(jpeg, firstArg())
            ProfileImageUploader.Result.Success("https://blossom.example/hash")
        }
        val repository = ProfileImageRepository(context, processor, uploader)

        val localReference = repository.storeSelection(
            selectedUri,
            ProfileImageRepository.Slot.PICTURE,
        )

        assertTrue(repository.isOwnedLocalReference(localReference))
        assertTrue(File(java.net.URI(localReference)).isFile)
        coVerify(exactly = 0) { uploader.upload(any(), any()) }

        val published = repository.publish(localReference)

        assertEquals(
            "https://blossom.example/hash",
            (published as ProfileImageUploader.Result.Success).url,
        )
        coVerify(exactly = 1) { uploader.upload(any(), any()) }
    }

    @Test
    fun `remote image passes through without upload`() = runTest {
        every { context.noBackupFilesDir } returns temporaryFolder.newFolder("no_backup")
        val repository = ProfileImageRepository(context, processor, uploader)

        val result = repository.publish("https://images.example/already-public.jpg")

        assertEquals(
            "https://images.example/already-public.jpg",
            (result as ProfileImageUploader.Result.Success).url,
        )
        coVerify(exactly = 0) { uploader.upload(any(), any()) }
    }

    @Test
    fun `foreign local file is never uploaded`() = runTest {
        every { context.noBackupFilesDir } returns temporaryFolder.newFolder("no_backup")
        val foreign = temporaryFolder.newFile("foreign.jpg")
        foreign.writeBytes(byteArrayOf(9, 8, 7))
        val repository = ProfileImageRepository(context, processor, uploader)

        val result = repository.publish(foreign.toURI().toString())

        assertTrue(result is ProfileImageUploader.Result.Failure)
        assertTrue(foreign.exists())
        repository.deleteIfOwned(foreign.toURI().toString())
        assertTrue(foreign.exists())
        coVerify(exactly = 0) { uploader.upload(any(), any()) }
    }

    @Test
    fun `selections remain independent until the profile commits one`() = runTest {
        val noBackup = temporaryFolder.newFolder("no_backup")
        val firstUri = mockk<Uri>()
        val secondUri = mockk<Uri>()
        every { context.noBackupFilesDir } returns noBackup
        coEvery { processor.loadAndCompress(any(), any(), any()) } returns byteArrayOf(1)
        val repository = ProfileImageRepository(context, processor, uploader)
        val first = repository.storeSelection(firstUri, ProfileImageRepository.Slot.BANNER)

        val second = repository.storeSelection(secondUri, ProfileImageRepository.Slot.BANNER)

        assertTrue(File(java.net.URI(first)).exists())
        assertTrue(File(java.net.URI(second)).exists())
    }
}
