package com.cruxcoach.android.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
            Text(stringResource(R.string.key_dialog_nsec_text))
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
internal fun BackupPasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val passwordsMatch = password.isNotBlank() && password == confirmPassword
    val passwordTooShort = password.isNotBlank() && password.length < 8

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_dialog_backup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.key_dialog_backup_text),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.key_label_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = passwordTooShort,
                    supportingText = if (passwordTooShort) {
                        { Text(stringResource(R.string.key_label_password_too_short)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text(stringResource(R.string.key_label_password_confirm)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = confirmPassword.isNotBlank() && !passwordsMatch,
                    supportingText = if (confirmPassword.isNotBlank() && !passwordsMatch) {
                        { Text(stringResource(R.string.key_label_password_mismatch)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = passwordsMatch && password.length >= 8
            ) {
                Text(stringResource(R.string.key_button_create_backup), color = if (passwordsMatch && password.length >= 8) OrangeAccent else Color.Gray)
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
internal fun BackupResultDialog(
    ncryptsec: String,
    qrBitmap: Bitmap?,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_dialog_backup_result_title)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.key_dialog_backup_result_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.key_label_qr_content_desc),
                            modifier = Modifier.size(204.dp)
                        )
                    }
                }

                Text(
                    text = ncryptsec.take(20) + "..." + ncryptsec.takeLast(8),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(" " + stringResource(R.string.key_button_copy_ncryptsec), maxLines = 1)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done), color = OrangeAccent)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    onDeleteLocalKey: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onKeepLocalKey,
        title = { Text(stringResource(R.string.key_dialog_amber_success_title)) },
        text = {
            Text(stringResource(R.string.key_dialog_amber_success_text))
        },
        confirmButton = {
            TextButton(onClick = onDeleteLocalKey) {
                Text(stringResource(R.string.key_button_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepLocalKey) {
                Text(stringResource(R.string.key_button_keep))
            }
        }
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
