package com.cruxcoach.android.ui.map

import android.content.Intent
import android.net.Uri
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
            val subtitle = listOfNotNull(location.city, location.countryCode)
                .joinToString(separator = ", ")
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
                onClick = location.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                    {
                        context.startActivity(
                            Intent(Intent.ACTION_DIAL, "tel:$phone".toUri())
                        )
                    }
                },
            )
            DetailRow(
                icon = Icons.Filled.Email,
                value = location.email,
                onClick = location.email?.takeIf { it.isNotBlank() }?.let { email ->
                    {
                        context.startActivity(
                            Intent(Intent.ACTION_SENDTO, "mailto:$email".toUri())
                        )
                    }
                },
            )
            DetailRow(
                icon = Icons.Filled.Language,
                value = location.url,
                onClick = location.url?.takeIf { it.isNotBlank() }?.let { url ->
                    {
                        val safe = if (url.startsWith("http")) url else "https://$url"
                        context.startActivity(Intent(Intent.ACTION_VIEW, safe.toUri()))
                    }
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            LabelValueRow(stringResource(R.string.map_marker_layout), location.layoutDisplay())
            LabelValueRow(stringResource(R.string.map_marker_access), accessDisplay(location.accessType))
            LabelValueRow(
                stringResource(R.string.map_marker_adjustability),
                adjustabilityDisplay(location.adjustability, location.fixedAngle),
            )
            LabelValueRow(stringResource(R.string.map_marker_frame), location.frameMaker.placeholderIfMissing())

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
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
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

private fun String?.placeholderIfMissing(): String =
    if (isNullOrBlank()) "—" else this

private fun BoardLocation.layoutDisplay(): String {
    val name = layoutName ?: return "—"
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
