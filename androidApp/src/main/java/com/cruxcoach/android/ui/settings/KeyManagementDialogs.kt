package com.cruxcoach.android.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent

@Composable
internal fun NsecWarningDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_dialog_nsec_title)) },
        text = {
            Text(stringResource(R.string.key_dialog_nsec_text), modifier = Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.key_dialog_nsec_confirm), color = OrangeAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}


@Composable
internal fun BiometricUnavailableDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_dialog_biometric_title)) },
        text = {
            Text(stringResource(R.string.key_dialog_biometric_text))
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.key_button_open_settings), color = OrangeAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
internal fun AmberNotInstalledDialog(
    onDismiss: () -> Unit,
    onInstallZapstore: () -> Unit,
    onInstallFdroid: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_dialog_amber_not_installed_title)) },
        text = {
            Text(stringResource(R.string.key_dialog_amber_not_installed_text))
        },
        confirmButton = {
            Column {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(onClick = onInstallZapstore) {
                    Text(stringResource(R.string.key_button_install_zapstore))
                }
                TextButton(onClick = onInstallFdroid) {
                    Text(stringResource(R.string.key_button_install_fdroid), color = OrangeAccent)
                }
            }
        }
    )
}

@Composable
internal fun AmberSuccessDialog(
    onKeepLocalKey: () -> Unit,
    onDeleteLocalKey: () -> Unit,
    hasLocalKey: Boolean = true,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onKeepLocalKey,
        title = { Text(stringResource(if (confirmDelete) R.string.account_delete_copy_title else R.string.key_dialog_amber_success_title)) },
        text = { Text(stringResource(if (confirmDelete) R.string.account_delete_copy_body else R.string.key_dialog_amber_success_text),
            modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = if (confirmDelete) onDeleteLocalKey else onKeepLocalKey) {
                Text(stringResource(if (confirmDelete) R.string.key_button_delete else if (hasLocalKey) R.string.key_button_keep else R.string.account_continue))
            }
        },
        dismissButton = {
            if (hasLocalKey) TextButton(onClick = { confirmDelete = !confirmDelete }) {
                Text(stringResource(if (confirmDelete) R.string.action_back else R.string.account_remove_local_copy))
            }
        },
    )
}

@Composable
internal fun NoSecurityWarningDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onProceedAnyway: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_dialog_no_security_title)) },
        text = {
            Text(stringResource(R.string.key_dialog_no_security_text))
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.key_button_open_settings), color = OrangeAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onProceedAnyway) {
                Text(stringResource(R.string.key_button_proceed_anyway))
            }
        }
    )
}

// --- Biometric & clipboard utilities used by KeyManagementScreen ---

internal fun requestBiometric(
    context: Context,
    onSuccess: () -> Unit,
    onUnavailable: () -> Unit,
    onNoHardware: () -> Unit
) {
    val activity = context as? FragmentActivity ?: return

    val biometricManager = BiometricManager.from(context)
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    when (biometricManager.canAuthenticate(authenticators)) {
        BiometricManager.BIOMETRIC_SUCCESS -> {
            val executor = ContextCompat.getMainExecutor(context)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        Toast.makeText(context, context.getString(R.string.key_toast_auth_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            val prompt = BiometricPrompt(activity, executor, callback)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(context.getString(R.string.key_biometric_title))
                .setSubtitle(context.getString(R.string.key_biometric_subtitle))
                .setAllowedAuthenticators(authenticators)
                .build()
            prompt.authenticate(promptInfo)
        }
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
            onUnavailable()
        }
        else -> {
            // No hardware or unavailable — warn user instead of silently bypassing
            onNoHardware()
        }
    }
}

internal fun copyToClipboard(context: Context, text: String, label: String, sensitive: Boolean) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(
                if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE
                else "android.content.extra.IS_SENSITIVE",
                true
            )
        }
    }
    clipboard.setPrimaryClip(clip)
}


internal fun truncateKey(key: String): String {
    return if (key.length > 14) {
        key.take(8) + "..." + key.takeLast(6)
    } else {
        key
    }
}

/** A method change is not applied until its public target identity has been confirmed. */
@Composable
internal fun AccountAccessConfirmation(
    targetNpub: String,
    sameAccount: Boolean,
    toAmber: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (sameAccount) R.string.account_access_change_title else R.string.key_import_overwrite_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (sameAccount) R.string.account_access_same else R.string.account_access_different))
                Text(targetNpub, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(if (toAmber) R.string.account_access_to_amber else R.string.account_access_to_local))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = targetNpub.isNotBlank()) {
            Text(stringResource(R.string.account_access_confirm))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
