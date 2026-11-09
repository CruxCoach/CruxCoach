package com.cruxcoach.android.data.shinywall

/** In-memory [ShinyWallTokenStorage] for tests. */
class InMemoryShinyWallTokenStorage(
    private var current: ShinyWallSession? = null,
) : ShinyWallTokenStorage {
    private var lastImport: Long? = null
    private val watermarks = mutableMapOf<String, Long>()
    private val placed = mutableMapOf<String, Map<String, Long>>()
    private val written = mutableMapOf<String, Map<String, Long>>()
    private val lists = mutableMapOf<String, Set<String>>()
    var now: () -> Long = System::currentTimeMillis

    override fun session(): ShinyWallSession? = current
    override fun storeSession(session: ShinyWallSession) { current = session }
    override fun updateTokens(uid: String, idToken: String, refreshToken: String, expiresInSeconds: Long) {
        val session = current?.takeIf { it.uid == uid } ?: return
        current = session.copy(
            idToken = idToken,
            refreshToken = refreshToken,
            idTokenExpiresAtMs = now() + expiresInSeconds * 1000,
        )
    }
    override fun updateDisplayName(uid: String, displayName: String) {
        current = current?.let { if (it.uid == uid) it.copy(displayName = displayName) else it }
    }
    override fun lastImportAtMs(): Long? = lastImport
    override fun historyWatermarkMs(uid: String): Long? = watermarks[uid]
    override fun placedLogs(uid: String): Map<String, Long> = placed[uid].orEmpty()
    override fun writtenCounts(uid: String): Map<String, Long> = written[uid].orEmpty()
    override fun recordImport(
        uid: String, atMs: Long, watermarkMs: Long?, placedLogs: Map<String, Long>, writtenCounts: Map<String, Long>,
    ) {
        if (current?.uid == uid) lastImport = atMs
        if (watermarkMs != null) watermarks[uid] = watermarkMs
        placed[uid] = placedLogs
        written[uid] = writtenCounts.filterKeys { it in placedLogs }
    }
    override fun importedLists(uid: String): Set<String> = lists[uid].orEmpty()
    override fun recordImportedLists(uid: String, externalIds: Set<String>) { lists[uid] = externalIds }
    override fun resetImportRecords() { watermarks.clear(); placed.clear(); written.clear(); lists.clear() }
    override fun clearSession() { current = null; lastImport = null }
}
