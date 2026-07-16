package com.cruxcoach.android.payment.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.R

private data class WalletRecommendation(
    val name: String,
    val url: String
)

private val RECOMMENDED_WALLETS = listOf(
    WalletRecommendation("Phoenix", "https://phoenix.acinq.co"),
    WalletRecommendation("Wallet of Satoshi", "https://www.walletofsatoshi.com"),
    WalletRecommendation("Zeus", "https://zeusln.com"),
    WalletRecommendation("Alby Go", "https://alby.com/go")
)

@Composable
internal fun NoWalletDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.payment_no_wallet_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.payment_no_wallet_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    stringResource(R.string.payment_recommended_wallets),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RECOMMENDED_WALLETS.forEach { wallet ->
                        WalletRow(
                            name = wallet.name,
                            onClick = { openUrl(context, wallet.url) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = OrangeAccent)
            ) {
                Text(stringResource(R.string.action_ok))
            }
        }
    )
}

@Composable
private fun WalletRow(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = OrangeAccent,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Default.OpenInNew,
            contentDescription = stringResource(R.string.payment_open_wallet),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        android.util.Log.w("NoWalletDialog", "Failed to open URL: $url", e)
    }
}
