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
        val permissions = """[
            {"type":"sign_event","kind":14},
            {"type":"sign_event","kind":13},
            {"type":"sign_event","kind":1059},
            {"type":"nip44_encrypt"},
            {"type":"nip44_decrypt"}
        ]"""
        intent.putExtra("permissions", permissions)
        return intent
    }
}
