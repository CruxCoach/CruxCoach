package com.cruxcoach.android.data

import android.content.Context
import java.io.File

/**
 * The last imported Shiny Wall catalogue snapshot, kept so an offline share can
 * pass it on unchanged (see LocalShareProtocol.V2_SHINYWALL_PATH). It is the
 * file the import read: the Blossom chunk, the debug import or a peer's copy.
 * The catalogue's climbs never travel inside the board snapshot, because
 * receivers released before the family read an unknown brand as Kilter.
 */
object ShinyWallCatalogueStore {
    const val FORMAT = ShinyWallCatalogueSync.CHUNK_NAME

    fun file(context: Context): File = File(context.filesDir, "board_families/$FORMAT.sqlite3")

    /** Keeps a copy of [imported] as the current snapshot (replaced atomically). */
    fun keep(context: Context, imported: File) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        imported.copyTo(partial, overwrite = true)
        if (!partial.renameTo(target)) {
            partial.delete()
            throw java.io.IOException("Could not keep the Shiny Wall catalogue snapshot")
        }
    }

    fun clear(context: Context) {
        file(context).delete()
        File(file(context).path + ".part").delete()
    }
}
