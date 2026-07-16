package com.cruxcoach.android.ui.navigation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.payment.ui.SendResult
import com.cruxcoach.android.payment.ui.InvoiceDialog
import com.cruxcoach.android.payment.ui.NoWalletDialog
import com.cruxcoach.android.payment.ui.PaymentBottomSheet
import com.cruxcoach.android.payment.ui.PaymentState
import com.cruxcoach.android.payment.ui.PaymentViewModel
import com.cruxcoach.android.payment.ui.ZapAmountDialog

@Composable
internal fun SettingsPaymentDialogs(
    showPaymentSheet: Boolean,
    onDismissSheet: () -> Unit,
    paymentViewModel: PaymentViewModel,
    paymentState: PaymentState,
) {
    if (showPaymentSheet) {
        PaymentBottomSheet(
            onDismiss = onDismissSheet,
            onSelectLightningPrivate = {
                onDismissSheet()
                paymentViewModel.selectLightning(`private` = true)
            },
            onSelectLightningZap = {
                onDismissSheet()
                paymentViewModel.selectLightning(`private` = false)
            },
            isSending = paymentState.isSending
        )
    }

    if (paymentState.showAmountDialog) {
        ZapAmountDialog(
            `private` = paymentState.isPrivate,
            onSend = { amount, message ->
                paymentViewModel.sendLightningPayment(amount, message, paymentState.isPrivate)
            },
            onDismiss = { paymentViewModel.dismissAmountDialog() },
            isSending = paymentState.isSending
        )
    }

    if (paymentState.showNoWalletDialog) {
        NoWalletDialog(onDismiss = { paymentViewModel.dismissNoWalletDialog() })
    }

    val sendResult = paymentState.sendResult
    if (sendResult is SendResult.LightningInvoice) {
        InvoiceDialog(
            bolt11 = sendResult.bolt11,
            onDismiss = { paymentViewModel.dismissResult() }
        )
    }

    if (sendResult is SendResult.Error) {
        AlertDialog(
            onDismissRequest = { paymentViewModel.dismissResult() },
            title = { Text(stringResource(R.string.error_label)) },
            text = { Text(sendResult.message) },
            confirmButton = {
                TextButton(onClick = { paymentViewModel.dismissResult() }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }
}
