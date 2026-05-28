package com.cruxcoach.android.ui.map

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.util.Patterns
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.cruxcoach.android.R
import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.domain.board.BoardBrand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardLocationDetailSheet(
    location: BoardLocation,
    onDismiss: () -> Unit,
    onBrowseClimbs: (layoutId: Int, productSizeId: Int?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                location.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            // MoonBoard gyms carry no country ("??") — drop the placeholder
            // and tag the brand instead so the header still reads cleanly.
            val geoParts = listOfNotNull(
                location.city?.takeIf { it.isNotBlank() },
                location.countryCode.takeIf { it.isNotBlank() && it != "??" },
            )
            val subtitle = if (location.boardBrand == BoardBrand.MOONBOARD) {
                (listOf(stringResource(R.string.board_selection_brand_moonboard)) + geoParts)
                    .joinToString(separator = " · ")
            } else {
                geoParts.joinToString(separator = ", ")
            }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Contact rows always shown with "—" placeholder when missing —
            // gives the user a predictable layout.
            DetailRow(icon = Icons.Filled.LocationOn, value = location.address)
            DetailRow(
                icon = Icons.Filled.Phone,
                value = location.phone,
                onClick = sanitisedPhoneOrNull(location.phone)?.let { phone ->
                    {
                        // Uri.fromParts builds an opaque tel: URI — the SSP
                        // is not parsed for `?key=value` parameters, so a
                        // crafted phone string can't inject extra dialer
                        // semantics. sanitisedPhoneOrNull strips anything
                        // outside the dialer-safe character set first.
                        safeStartActivity(
                            context,
                            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)),
                            gymId = location.id,
                            action = "dial",
                        )
                    }
                },
            )
            DetailRow(
                icon = Icons.Filled.Email,
                value = location.email,
                onClick = validatedEmailOrNull(location.email)?.let { email ->
                    {
                        // ACTION_SENDTO + EXTRA_EMAIL keeps the recipient
                        // out of the URI's query component, so a malicious
                        // "?subject=…&bcc=…" suffix in the email field
                        // can't pre-compose the user's mail client. The
                        // recipient itself was validated by Patterns.EMAIL_ADDRESS.
                        safeStartActivity(
                            context,
                            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                                .putExtra(Intent.EXTRA_EMAIL, arrayOf(email)),
                            gymId = location.id,
                            action = "email",
                        )
                    }
                },
            )
            DetailRow(
                icon = Icons.Filled.Language,
                value = location.url,
                onClick = validatedHttpUrlOrNull(location.url)?.let { url ->
                    {
                        // validatedHttpUrlOrNull guarantees scheme is
                        // http or https (after a possible https:// upgrade
                        // for scheme-less inputs), so ACTION_VIEW cannot
                        // be hijacked into a custom-scheme handler that
                        // happens to be installed on the device.
                        safeStartActivity(
                            context,
                            Intent(Intent.ACTION_VIEW, url),
                            gymId = location.id,
                            action = "web",
                        )
                    }
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            val placeholder = stringResource(R.string.map_marker_field_unknown)
            LabelValueRow(stringResource(R.string.map_marker_layout), location.layoutDisplay(placeholder))
            LabelValueRow(stringResource(R.string.map_marker_access), accessDisplay(location.accessType))
            LabelValueRow(
                stringResource(R.string.map_marker_adjustability),
                adjustabilityDisplay(location.adjustability, location.fixedAngle),
            )
            LabelValueRow(stringResource(R.string.map_marker_frame), location.frameMaker.placeholderIfMissing(placeholder))

            Spacer(modifier = Modifier.height(8.dp))

            // "Browse climbs" only when we successfully mapped the layout.
            val resolvedLayoutId = location.layoutId
            if (resolvedLayoutId != null) {
                Button(
                    onClick = { onBrowseClimbs(resolvedLayoutId, location.productSizeId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.map_marker_browse_climbs))
                }
            }
            OutlinedButton(
                onClick = {
                    val name = Uri.encode(location.name)
                    val uri = "geo:${location.lat},${location.lng}?q=${location.lat},${location.lng}($name)"
                    safeStartActivity(
                        context,
                        Intent(Intent.ACTION_VIEW, uri.toUri()),
                        gymId = location.id,
                        action = "maps",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Map, contentDescription = null)
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Text(stringResource(R.string.map_marker_open_in_maps))
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    value: String?,
    onClick: (() -> Unit)? = null,
) {
    val placeholder = stringResource(R.string.map_marker_field_unknown)
    val display = if (value.isNullOrBlank()) placeholder else value
    val clickable = onClick != null && !value.isNullOrBlank()

    val rowModifier = Modifier
        .fillMaxWidth()
        .let { if (clickable && onClick != null) it.clickable { onClick() } else it }
        .padding(vertical = 6.dp)

    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = display,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                value.isNullOrBlank() -> MaterialTheme.colorScheme.onSurfaceVariant
                clickable -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun LabelValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private const val TAG_SHEET = "BoardLocationSheet"

/**
 * Strip the input down to dialer-safe characters. Returns null when the
 * cleaned value is empty so the calling row stays non-clickable instead
 * of launching a no-op `tel:` intent. This is the input-sanitisation
 * arm of the FEAT-015 untrusted-data defence — see CHANGELOG 0.1.5
 * "Security".
 */
private fun sanitisedPhoneOrNull(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val cleaned = trimmed.filter { it == '+' || it == '-' || it == '(' || it == ')' || it == ' ' || it.isDigit() }
    return cleaned.takeIf { it.any(Char::isDigit) }
}

/**
 * Pass only RFC-compliant addresses to ACTION_SENDTO. A malformed entry
 * (e.g. a value with `?subject=…&bcc=…` injected for mailto-header
 * abuse) fails this check and the row becomes non-clickable.
 */
private fun validatedEmailOrNull(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return trimmed.takeIf { Patterns.EMAIL_ADDRESS.matcher(it).matches() }
}

/**
 * Accept the URL only if its parsed scheme is `http` or `https`. A
 * raw `httpx://` or custom-scheme value cannot bypass via a leading
 * `http`-prefix substring match (the previous startsWith("http") guard
 * accepted those). Scheme-less inputs are upgraded to `https://`
 * before re-parsing.
 */
private fun validatedHttpUrlOrNull(raw: String?): Uri? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    return if (scheme == "http" || scheme == "https") uri else null
}

private fun safeStartActivity(
    context: Context,
    intent: Intent,
    gymId: String,
    action: String,
) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG_SHEET, "gym=$gymId action=$action: no handler for ${intent.action}", e)
        Toast.makeText(context, R.string.map_marker_intent_failed, Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Log.w(TAG_SHEET, "gym=$gymId action=$action: security exception", e)
        Toast.makeText(context, R.string.map_marker_intent_failed, Toast.LENGTH_SHORT).show()
    }
}

private fun String?.placeholderIfMissing(placeholder: String): String =
    if (isNullOrBlank()) placeholder else this

private fun BoardLocation.layoutDisplay(placeholder: String): String {
    val name = layoutName ?: return placeholder
    val size = sizeLabel
    return if (size.isNullOrBlank()) name else "$name ($size)"
}

@Composable
private fun accessDisplay(access: AccessType): String = when (access) {
    AccessType.PUBLIC -> stringResource(R.string.map_access_public)
    AccessType.PRIVATE -> stringResource(R.string.map_access_private)
    AccessType.MEMBERS -> stringResource(R.string.map_access_members)
    AccessType.UNKNOWN -> stringResource(R.string.map_marker_field_unknown)
}

@Composable
private fun adjustabilityDisplay(adj: Adjustability, fixedAngle: Int?): String = when (adj) {
    Adjustability.FIXED -> if (fixedAngle != null)
        stringResource(R.string.map_adjustability_fixed_angle, fixedAngle)
    else stringResource(R.string.map_adjustability_fixed)
    Adjustability.ADJUSTABLE -> stringResource(R.string.map_adjustability_adjustable)
    Adjustability.LIMITED -> stringResource(R.string.map_adjustability_limited)
    Adjustability.FULL -> stringResource(R.string.map_adjustability_full)
    Adjustability.UNKNOWN -> stringResource(R.string.map_marker_field_unknown)
}
