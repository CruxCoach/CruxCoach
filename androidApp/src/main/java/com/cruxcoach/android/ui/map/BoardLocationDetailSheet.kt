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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
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
import com.cruxcoach.domain.board.MoonBoardVariant

/**
 * Bottom sheet for a tapped [MapVenue]. A venue may host several boards
 * (a gym with both a Kilter and a MoonBoard, or Original + Homewall), so the
 * sheet shows shared venue info once — name, location, contact — then lists
 * each board with its own "browse climbs" action.
 *
 * Contact rows are sourced only from PUBLIC boards. Private, members-only,
 * and unclassified installations retain their board metadata but expose no
 * direct contact channel. All outbound intents go through the hardened
 * safe-launch / input-validation helpers (FEAT-015 security hardening).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardLocationDetailSheet(
    venue: MapVenue,
    onDismiss: () -> Unit,
    onBrowseClimbs: (brand: BoardBrand, layoutId: Int, productSizeId: Int?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val context = LocalContext.current
    val contact = contactBoardFor(venue)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                venue.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val subtitle = listOfNotNull(venue.city, venue.countryCode.takeIf { it.isNotBlank() && it != "??" })
                .joinToString(separator = ", ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (venue.boards.any { it.wellpass == true }) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        stringResource(R.string.map_venue_wellpass_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            if (contact != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Shared contact rows. Each clickable row is gated by an input
                // validator. Non-public venues omit this entire block.
                DetailRow(icon = Icons.Filled.LocationOn, value = contact.address)
                DetailRow(
                    icon = Icons.Filled.Phone,
                    value = contact.phone,
                    onClick = sanitisedPhoneOrNull(contact.phone)?.let { phone ->
                        {
                            safeStartActivity(
                                context,
                                Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)),
                                gymId = contact.id,
                                action = "dial",
                            )
                        }
                    },
                )
                DetailRow(
                    icon = Icons.Filled.Email,
                    value = contact.email,
                    onClick = validatedEmailOrNull(contact.email)?.let { email ->
                        {
                            safeStartActivity(
                                context,
                                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                                    .putExtra(Intent.EXTRA_EMAIL, arrayOf(email)),
                                gymId = contact.id,
                                action = "email",
                            )
                        }
                    },
                )
                DetailRow(
                    icon = Icons.Filled.Language,
                    value = contact.url,
                    onClick = validatedHttpUrlOrNull(contact.url)?.let { url ->
                        {
                            safeStartActivity(
                                context,
                                Intent(Intent.ACTION_VIEW, url),
                                gymId = contact.id,
                                action = "web",
                            )
                        }
                    },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            // One card per board at this venue. A single-board venue shows
            // exactly one card; a mixed venue lists each, brand-ordered.
            Text(
                stringResource(R.string.map_venue_section_boards),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            venue.boards.forEach { board ->
                BoardCard(board = board, onBrowseClimbs = onBrowseClimbs)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    val name = Uri.encode(venue.name)
                    val uri = "geo:${venue.lat},${venue.lng}?q=${venue.lat},${venue.lng}($name)"
                    safeStartActivity(
                        context,
                        Intent(Intent.ACTION_VIEW, uri.toUri()),
                        gymId = venue.id,
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
private fun BoardCard(
    board: BoardLocation,
    onBrowseClimbs: (brand: BoardBrand, layoutId: Int, productSizeId: Int?) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                board.boardTitle(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            LabelValueRow(stringResource(R.string.map_marker_access), accessDisplay(board.accessType))
            LabelValueRow(
                stringResource(R.string.map_marker_adjustability),
                adjustabilityDisplay(board.adjustability, board.fixedAngle),
            )
            if (board.boardBrand == BoardBrand.KILTER && !board.frameMaker.isNullOrBlank()) {
                LabelValueRow(stringResource(R.string.map_marker_frame), board.frameMaker!!)
            }
            // "Browse climbs" only for families CruxCoach has a catalogue
            // for (Kilter / MoonBoard). Foreign info-layer brands are
            // map-only — no catalogue to deep-link into.
            val layoutId = board.layoutId
            if (layoutId != null && board.boardBrand.isInteractive) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { onBrowseClimbs(board.boardBrand, layoutId, board.productSizeId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.map_marker_browse_climbs))
                }
            }
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
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val TAG_SHEET = "BoardLocationSheet"

/** How many contact fields this board carries — used to pick the venue's
 *  representative contact row. */
private fun BoardLocation.contactScore(): Int =
    listOf(phone, email, url, address).count { !it.isNullOrBlank() }

/** Select contact data only from a publicly accessible board. This remains
 *  fail-closed even when callers construct a [MapVenue] without first running
 *  [MapFilters]. */
internal fun contactBoardFor(venue: MapVenue): BoardLocation? =
    venue.boards
        .asSequence()
        .filter { it.accessType == AccessType.PUBLIC }
        .maxByOrNull { it.contactScore() }

/** Human label for one board within a venue card. MoonBoard → variant name;
 *  foreign info-layer brands → brand name; Kilter → layout + size. */
private fun BoardLocation.boardTitle(): String = when (boardBrand) {
    BoardBrand.MOONBOARD ->
        layoutId?.toLong()?.let { MoonBoardVariant.fromLayoutId(it)?.displayName }
            ?: layoutName ?: BoardBrand.MOONBOARD.displayName
    // Kilter is the only family whose location feed carries layout + size
    // geometry, so it gets the richer "Kilter — <layout> (<size>)" label.
    BoardBrand.KILTER -> {
        val name = layoutName
        val size = sizeLabel
        when {
            name == null -> BoardBrand.KILTER.displayName
            size.isNullOrBlank() -> "${BoardBrand.KILTER.displayName} — $name"
            else -> "${BoardBrand.KILTER.displayName} — $name ($size)"
        }
    }
    // Every other family — Tension, Grasshopper, Decoy, So iLL, Touchstone
    // (all Aurora-protocol, so isInteractive=true) plus the map-only Aurora /
    // 12 Climb — uses its proper brand display name. The previous `else`
    // branch keyed on `!isInteractive`, which assumed "interactive but not
    // MoonBoard == Kilter" — only true before FEAT-031 added the other Aurora
    // boards. That's why a Tension+Grasshopper venue (e.g. E4 Nürnberg)
    // rendered as two "Kilter" rows. displayName also fixes "So iLL"/"12 Climb"
    // casing the old wireValue-uppercase hack got wrong.
    else -> boardBrand.displayName
}

/**
 * Strip the input down to dialer-safe characters. Returns null when the
 * cleaned value is empty so the calling row stays non-clickable instead of
 * launching a no-op `tel:` intent.
 */
private fun sanitisedPhoneOrNull(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val cleaned = trimmed.filter { it == '+' || it == '-' || it == '(' || it == ')' || it == ' ' || it.isDigit() }
    return cleaned.takeIf { it.any(Char::isDigit) }
}

/** Pass only RFC-compliant addresses to ACTION_SENDTO. */
private fun validatedEmailOrNull(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return trimmed.takeIf { Patterns.EMAIL_ADDRESS.matcher(it).matches() }
}

/** Accept the URL only if its parsed scheme is http(s) AND its host is not a
 *  private / loopback / link-local address. The gym website comes from the
 *  third-party locations dataset, so a crafted record could otherwise point
 *  the browser at an internal host (e.g. a router admin page). */
private fun validatedHttpUrlOrNull(raw: String?): Uri? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (isPrivateOrLoopbackHost(uri.host)) return null
    return uri
}

/** True for hosts that should never be opened from third-party dataset URLs:
 *  localhost, IPv4 loopback/private/link-local ranges, and IPv6 loopback /
 *  unique-local / link-local. Non-IP public hostnames pass. */
internal fun isPrivateOrLoopbackHost(host: String?): Boolean {
    val h = host?.trim()?.lowercase()?.removeSurrounding("[", "]") ?: return true
    if (h.isEmpty() || h == "localhost") return true
    if (':' in h) {
        // IPv4-mapped IPv6: ::ffff:a.b.c.d  or  ::ffff:hhhh:hhhh → check the
        // embedded IPv4. Otherwise loopback / unique-local / link-local.
        val mapped = h.substringAfterLast("::ffff:", "")
        if (mapped.isNotEmpty()) {
            ipv4LiteralToInt(mapped)?.let { return isPrivateOrLoopbackIpv4(it) }
            val g = mapped.split(":")
            if (g.size == 2) {
                val hi = g[0].toLongOrNull(16)
                val lo = g[1].toLongOrNull(16)
                if (hi != null && lo != null && hi <= 0xFFFF && lo <= 0xFFFF) {
                    return isPrivateOrLoopbackIpv4((hi shl 16) or lo)
                }
            }
        }
        return h == "::1" || h == "::" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
    }
    // IPv4: accept every inet_aton-style encoding the OS/browser actually
    // resolves (decimal/hex/octal dword + short forms), not just the canonical
    // dotted-quad — else 0x7f000001 / 2130706433 / 0177.0.0.1 bypass the check.
    val ipv4 = ipv4LiteralToInt(h) ?: return false // not numeric → real (public) hostname
    return isPrivateOrLoopbackIpv4(ipv4)
}

private fun isPrivateOrLoopbackIpv4(v: Long): Boolean {
    val a = (v ushr 24) and 0xFF
    val b = (v ushr 16) and 0xFF
    return a == 127L ||                  // loopback 127.0.0.0/8
        a == 10L ||                      // private 10.0.0.0/8
        a == 0L ||                       // 0.0.0.0/8
        (a == 172L && b in 16L..31L) ||  // private 172.16.0.0/12
        (a == 192L && b == 168L) ||      // private 192.168.0.0/16
        (a == 169L && b == 254L)         // link-local 169.254.0.0/16
}

/** Parse an IPv4 literal in any inet_aton form — 1-4 dot-parts, each decimal /
 *  0x-hex / leading-0 octal, with the legacy short-form packing — to its 32-bit
 *  value. Returns null when [s] is not a numeric IPv4 literal (a real hostname). */
private fun ipv4LiteralToInt(s: String): Long? {
    if (s.isEmpty() || s.any { it !in "0123456789abcdefx." }) return null
    val parts = s.split(".")
    if (parts.size > 4) return null
    val nums = parts.map { p -> parseIpv4Part(p) ?: return null }
    return when (nums.size) {
        1 -> nums[0].takeIf { it in 0..0xFFFFFFFFL }
        2 -> if (nums[0] <= 0xFF && nums[1] <= 0xFFFFFF) (nums[0] shl 24) or nums[1] else null
        3 -> if (nums[0] <= 0xFF && nums[1] <= 0xFF && nums[2] <= 0xFFFF)
            (nums[0] shl 24) or (nums[1] shl 16) or nums[2] else null
        4 -> if (nums.all { it <= 0xFF })
            (nums[0] shl 24) or (nums[1] shl 16) or (nums[2] shl 8) or nums[3] else null
        else -> null
    }
}

private fun parseIpv4Part(p: String): Long? = when {
    p.isEmpty() -> null
    p.startsWith("0x") -> p.drop(2).ifEmpty { null }?.toLongOrNull(16)
    p.length > 1 && p[0] == '0' -> p.toLongOrNull(8)
    else -> p.toLongOrNull(10)
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
