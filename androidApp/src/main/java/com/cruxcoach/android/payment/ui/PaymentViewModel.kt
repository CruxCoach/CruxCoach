package com.cruxcoach.android.payment.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.nostr.NostrMessageSending
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.nostr.model.MessageType
import com.cruxcoach.android.R
import com.cruxcoach.android.payment.PaymentRepository
import com.cruxcoach.android.payment.ZapManager
import com.cruxcoach.android.payment.model.PaymentChannel
import com.cruxcoach.android.payment.model.ZapResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

data class PaymentState(
    val showAmountDialog: Boolean = false,
    val showProfileSetup: Boolean = false,
    val showNoWalletDialog: Boolean = false,
    val selectedChannel: PaymentChannel? = null,
    val isPrivate: Boolean = true,
    val recipientPubkey: String = "",
    val isSending: Boolean = false,
    val sendResult: SendResult? = null,
    val lightningAvailable: Boolean = true
)

sealed class SendResult {
    data class LightningInvoice(val bolt11: String) : SendResult()
    data object Success : SendResult()
    data class Error(val message: String) : SendResult()
}

@HiltViewModel
class PaymentViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val zapManager: ZapManager,
    private val paymentRepository: PaymentRepository,
    private val nostrSigner: NostrSigner,
    private val messageSender: NostrMessageSending,
    private val deliveryCoordinator: com.cruxcoach.android.nostr.MessageDeliveryCoordinator
) : ViewModel() {

    private val _state = MutableStateFlow(PaymentState())
    val state: StateFlow<PaymentState> = _state.asStateFlow()

    fun initForDonation(recipientPubkey: String) {
        _state.update { it.copy(recipientPubkey = recipientPubkey) }
    }

    fun selectLightning(private: Boolean) {
        _state.update {
            it.copy(
                selectedChannel = PaymentChannel.LIGHTNING,
                isPrivate = `private`,
                showAmountDialog = true,
                sendResult = null
            )
        }
    }

    fun sendLightningPayment(amountSats: Long, message: String, private: Boolean) {
        _state.update { it.copy(isSending = true, sendResult = null) }
        viewModelScope.launch {
            try {
                val amountMilliSats = amountSats * 1000
                val result = zapManager.createPaymentRequest(
                    recipientPubkey = _state.value.recipientPubkey,
                    amountMilliSats = amountMilliSats,
                    message = message,
                    `private` = `private`
                )

                when (result) {
                    is ZapResult.Invoice -> {
                        withContext(Dispatchers.IO) {
                            paymentRepository.insert(
                                id = UUID.randomUUID().toString(),
                                type = if (`private`) "LIGHTNING_PRIVATE" else PaymentChannel.LIGHTNING.name,
                                direction = "sent",
                                senderPubkey = nostrSigner.getPublicKeyHex(),
                                recipientPubkey = _state.value.recipientPubkey,
                                eventId = null,
                                amountSats = amountSats,
                                message = message.ifBlank { null },
                                createdAt = System.currentTimeMillis()
                            )
                        }

                        // For private donations with a message, send an encrypted NIP-17 DM
                        if (`private` && message.isNotBlank()) {
                            sendDonationMessage(amountSats, message)
                        }

                        _state.update {
                            it.copy(
                                isSending = false,
                                showAmountDialog = false,
                                sendResult = SendResult.LightningInvoice(result.bolt11)
                            )
                        }
                    }
                    is ZapResult.Error -> {
                        _state.update {
                            it.copy(
                                isSending = false,
                                sendResult = SendResult.Error(result.message)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send Lightning payment", e)
                _state.update {
                    it.copy(
                        isSending = false,
                        sendResult = SendResult.Error(
                            context.getString(R.string.error_send_failed, e.message ?: context.getString(R.string.error_unknown))
                        )
                    )
                }
            }
        }
    }

    private suspend fun sendDonationMessage(amountSats: Long, message: String) {
        try {
            val content = "[DONATION] $amountSats sats — $message"
            val buildResult = messageSender.buildMessage(
                content = content,
                type = MessageType.CHAT,
                subject = "Spende"
            )
            if (buildResult is com.cruxcoach.android.nostr.SendResult.Queued) {
                // Fire-and-forget delivery — donation UI doesn't wait. App-scoped
                // so closing the payment screen inside the send delay can't
                // cancel it (the DM is not queued anywhere for retry).
                deliveryCoordinator.deliver(null, buildResult.eventJsons)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send donation DM (payment still proceeds)", e)
        }
    }

    fun openLightningInvoice(context: Context, bolt11: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("lightning:$bolt11"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "No lightning wallet app found", e)
            _state.update { it.copy(showNoWalletDialog = true) }
        }
    }

    fun openKofi(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(NostrConfig.KOFI_URL))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Ko-fi", e)
            _state.update {
                it.copy(sendResult = SendResult.Error("Ko-fi konnte nicht geöffnet werden."))
            }
        }
    }

    fun dismissResult() {
        _state.update { it.copy(sendResult = null) }
    }

    fun dismissAmountDialog() {
        _state.update {
            it.copy(showAmountDialog = false, selectedChannel = null, sendResult = null)
        }
    }

    fun dismissNoWalletDialog() {
        _state.update { it.copy(showNoWalletDialog = false) }
    }

    companion object {
        private const val TAG = "PaymentViewModel"
    }
}
