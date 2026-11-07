package com.cruxcoach.android.data.shinywall

/** In-memory [ShinyWallTokenStorage] for tests. */
class InMemoryShinyWallTokenStorage(
    private var current: ShinyWallSession? = null,
) : ShinyWallTokenStorage {
    private var lastImport: Long? = null
    private val watermarks = mutableMapOf<String, Long>()
    private val placed = mutableMapOf<String, Map<String, Long>>()
    var now: () -> Long = System::currentTimeMillis

    override fun session(): ShinyWallSession? = current
    override fun storeSession(session: ShinyWallSession) { current = session }
    override fun updateTokens(idToken: String, refreshToken: String, expiresInSeconds: Long) {
        current = current?.copy(
            idToken = idToken,
            refreshToken = refreshToken,
            idTokenExpiresAtMs = now() + expiresInSeconds * 1000,
        )
    }
    override fun updateDisplayName(displayName: String) { current = current?.copy(displayName = displayName) }
    override fun lastImportAtMs(): Long? = lastImport
    override fun historyWatermarkMs(uid: String): Long? = watermarks[uid]
    override fun placedLogs(uid: String): Map<String, Long> = placed[uid].orEmpty()
    override fun recordImport(uid: String, atMs: Long, watermarkMs: Long?, placedLogs: Map<String, Long>) {
        lastImport = atMs
        if (watermarkMs != null) watermarks[uid] = watermarkMs
        placed[uid] = placedLogs
    }
    override fun clearSession() { current = null; lastImport = null }
}
