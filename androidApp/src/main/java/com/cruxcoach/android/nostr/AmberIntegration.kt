package com.cruxcoach.android.nostr

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

object AmberIntegration {
    private const val TAG = "AmberIntegration"
    const val AMBER_PACKAGE = "com.greenart7c3.nostrsigner"

    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(AMBER_PACKAGE, 0)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Amber package not found", e)
            false
        }
    }

    fun buildGetPubkeyIntent(): Intent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        intent.`package` = AMBER_PACKAGE
        intent.putExtra("type", "get_public_key")
        // Kinds requested at login time, so Amber pre-approves them and
        // subsequent background backups / periodic workers hit the
        // ContentResolver fast path instead of triggering an Intent
        // approval dialog that has no foreground Activity to attach to.
        //  - 14 / 13 / 1059   NIP-17 DM envelope + gift-wrap (chat)
        //  - 30078            FEAT-002 backup pointer + wrapped key
        //  - 24242            Blossom BUD-01 upload / delete auth
        //  - 5                deletion events (active opt-out §20.2)
        //  - nip44_encrypt/decrypt — pointer + key-event encryption
        val permissions = """[
            {"type":"sign_event","kind":14},
            {"type":"sign_event","kind":13},
            {"type":"sign_event","kind":1059},
            {"type":"sign_event","kind":30078},
            {"type":"sign_event","kind":24242},
            {"type":"sign_event","kind":5},
            {"type":"nip44_encrypt"},
            {"type":"nip44_decrypt"}
        ]"""
        intent.putExtra("permissions", permissions)
        return intent
    }
}
