package com.cruxcoach.android.ui.navigation

import android.content.Context
import androidx.compose.runtime.Composable
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
    context: Context
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
            onSelectKofi = {
                onDismissSheet()
                paymentViewModel.openKofi(context)
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
}
