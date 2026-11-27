package com.cruxcoach.android.ui.paintbox

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PaintBoxStorageTest {
    @Test fun `save reopen overwrite delete and retain corrupt files`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val repository = PaintRepository(context)
        val picture = PaintDocument(name = "Test", board = PaintBoard("kilter", 1, 10), colors = mapOf(12 to 28))
        repository.save(picture)
        assertEquals(picture, PaintRepository(context).list().pictures.single())
        val edited = picture.copy(colors = mapOf(15 to 224))
        repository.save(edited)
        assertEquals(edited, repository.list().pictures.single())
        val corrupt = File(context.filesDir, "paintbox/00000000-0000-0000-0000-000000000001.json")
        corrupt.writeText("not json")
        assertEquals(1, repository.list().unreadable)
        assertTrue(corrupt.exists())
        repository.delete(picture.id)
        assertTrue(repository.list().pictures.isEmpty())
    }
}
