package com.cruxcoach.android.ui.map

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.util.Patterns
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.ui.common.distanceLabel
import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/*
 * Content of the map's bottom sheet for one tapped [MapVenue]. A venue may
 * host several boards (a gym with both a Kilter and a MoonBoard, or Original +
 * Homewall), so the sheet shows shared venue info once, then each board with
 * its own "browse climbs" action.
 *
 * The summary (name, place, boards, the actions) is what the collapsed sheet
 * shows; the details below it appear when the sheet is pulled up. Only facts
 * the data actually has are shown — no "—" rows.
 *
 * Contact details come from the board at the venue that carries the most of
 * them (MoonBoard rows ship none, so a co-located Kilter row wins). All
 * outbound intents go through the hardened safe-launch / input-validation
 * helpers (FEAT-015 security hardening).
 */

/** Compose twin of the map marker: brand colour and letter, a pie for mixed venues. */
@Composable
fun VenueMarkerDot(brands: List<BoardBrand>, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val measurer = rememberTextMeasurer()
    val styles = remember(brands) { brands.map { it.markerStyle() } }
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val inner = r - r * 0.15f
        drawCircle(Color.White, r)
        if (styles.size == 1) {
            val style = styles.single()
            drawCircle(Color(style.color), inner)
            val layout = measurer.measure(
                style.monogram,
                TextStyle(
                    color = Color(style.onColor),
                    fontWeight = FontWeight.Bold,
                    fontSize = (inner * (if (style.monogram.length > 1) 0.78f else 0.95f)).toSp(),
                ),
            )
            drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
        } else if (styles.isNotEmpty()) {
            val sweep = 360f / styles.size
            styles.forEachIndexed { i, style ->
                drawArc(
                    color = Color(style.color),
                    startAngle = -90f + i * sweep,
                    sweepAngle = sweep,
                    useCenter = true,
                    topLeft = Offset(center.x - inner, center.y - inner),
                    size = Size(inner * 2, inner * 2),
                )
            }
            styles.indices.forEach { i ->
                val a = Math.toRadians(-90.0 + i * sweep)
                drawLine(
                    Color.White, center,
                    Offset(center.x + inner * cos(a).toFloat(), center.y + inner * sin(a).toFloat()),
                    strokeWidth = r * 0.09f,
                )
            }
        }
        drawCircle(Color.Black.copy(alpha = 0.08f), r, style = Stroke(width = 1f))
    }
}

/** One row of the "places in view" list. */
@Composable
fun VenueRow(
    venue: MapVenue,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    distanceMeters: Double? = null,
) {
    val brands = venue.markerBrands.joinToString(", ") { it.displayName }
    val place = venueLocationLine(venue)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VenueMarkerDot(venue.markerBrands, size = 32.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                venue.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(brands, place).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        distanceMeters?.let {
            Spacer(Modifier.width(12.dp))
            Text(
                distanceLabel(it),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "München, Deutschland", or "bei Bangor, Vereinigtes Königreich" when the
 *  town was attached by distance rather than taken from the venue's address. */
@Composable
fun venueLocationLine(venue: MapVenue): String {
    val german = Locale.getDefault().language == "de"
    val city = venue.city?.takeIf(String::isNotBlank)?.let {
        if (venue.info.cityIsNearest) {
            val name = venue.info.nearestCityDe?.takeIf { german } ?: it
            stringResource(R.string.map_venue_near_city, name)
        } else it
    }
    val country = venue.countryCode.takeIf { it.length == 2 && it != "??" }
        ?.let { Locale("", it).getDisplayCountry(Locale.getDefault()) }
        ?.takeIf(String::isNotBlank)
    return listOfNotNull(city, country).joinToString(", ")
}

/**
 * What the collapsed sheet shows for a selected venue: who and where, which
 * boards, and the actions people take from a map (directions, website, call).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VenueSummary(
    venue: MapVenue,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    distanceMeters: Double? = null,
) {
    val context = LocalContext.current
    val contact = venue.contactBoard()
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            VenueMarkerDot(venue.markerBrands, Modifier.padding(top = 2.dp), size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    venue.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("map_venue_name"),
                )
                val place = listOfNotNull(
                    venueLocationLine(venue).takeIf(String::isNotBlank),
                    distanceMeters?.let { distanceLabel(it) },
                ).joinToString(" · ")
                if (place.isNotBlank()) {
                    Text(
                        place,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.testTag("map_venue_close")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.map_venue_close))
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(end = 12.dp),
        ) {
            venue.markerBrands.forEach { brand ->
                val count = venue.boards.count { it.boardBrand == brand }
                BrandLabel(brand, if (count > 1) "${brand.displayName} ×$count" else brand.displayName)
            }
            if (venue.boards.any { it.wellpass == true }) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(50)) {
                    Text(
                        stringResource(R.string.map_venue_wellpass_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(end = 12.dp),
        ) {
            Button(
                onClick = {
                    val name = Uri.encode(venue.name)
                    val uri = "geo:${venue.lat},${venue.lng}?q=${venue.lat},${venue.lng}($name)"
                    safeStartActivity(context, Intent(Intent.ACTION_VIEW, uri.toUri()), venue.id, "maps")
                },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.testTag("map_venue_route"),
            ) {
                Icon(Icons.Filled.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.map_venue_route))
            }
            validatedHttpUrlOrNull(contact.url)?.let { url ->
                ActionButton(Icons.Filled.Language, stringResource(R.string.map_venue_website)) {
                    safeStartActivity(context, Intent(Intent.ACTION_VIEW, url), contact.id, "web")
                }
            }
            sanitisedPhoneOrNull(contact.phone)?.let { phone ->
                ActionButton(Icons.Filled.Phone, stringResource(R.string.map_venue_call)) {
                    safeStartActivity(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)), contact.id, "dial")
                }
            }
            instagramUrlOrNull(contact.instagram)?.let { url ->
                ActionButton(Icons.Filled.PhotoCamera, stringResource(R.string.map_venue_instagram)) {
                    safeStartActivity(context, Intent(Intent.ACTION_VIEW, url), contact.id, "instagram")
                }
            }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun BrandLabel(brand: BoardBrand, text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(50)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(Color(brand.markerStyle().color), CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** What the sheet adds when pulled up: every board, the opening hours and the contact rows. */
@Composable
fun VenueDetails(
    venue: MapVenue,
    boardInfo: Map<String, MapBoardInfo>,
    onBrowseClimbs: (brand: BoardBrand, layoutId: Int, productSizeId: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val contact = venue.contactBoard()
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider()
        SectionTitle(stringResource(R.string.map_venue_section_boards))
        venue.boards.forEach { board ->
            BoardCard(board, boardInfo[board.id], onBrowseClimbs)
        }

        venue.info.hours?.let { hours ->
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            HoursSection(hours, venue.info.hoursSource, contact.url, venue.id)
        }

        val email = validatedEmailOrNull(contact.email)
        if (!contact.address.isNullOrBlank() || sanitisedPhoneOrNull(contact.phone) != null || email != null) {
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            SectionTitle(stringResource(R.string.map_venue_section_contact))
            contact.address?.takeIf(String::isNotBlank)?.let { DetailRow(Icons.Filled.LocationOn, it) }
            sanitisedPhoneOrNull(contact.phone)?.let { phone ->
                DetailRow(Icons.Filled.Phone, contact.phone!!.trim()) {
                    safeStartActivity(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)), contact.id, "dial")
                }
            }
            email?.let {
                DetailRow(Icons.Filled.Email, it) {
                    safeStartActivity(
                        context,
                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(it)),
                        contact.id,
                        "email",
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun BoardCard(
    board: BoardLocation,
    info: MapBoardInfo?,
    onBrowseClimbs: (brand: BoardBrand, layoutId: Int, productSizeId: Int?) -> Unit,
) {
    val title = board.boardTitle(stringResource(R.string.map_marker_moonboard_variant_unknown))
    val facts = boardFacts(board, info)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(Color(board.boardBrand.markerStyle().color))
            )
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                facts.forEach { fact ->
                    Text(
                        fact,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // "Browse climbs" only for families CruxCoach has a catalogue
                // for. Foreign info-layer brands are map-only — no catalogue
                // to deep-link into.
                val layoutId = board.layoutId
                if (layoutId != null && board.boardBrand.isInteractive) {
                    TextButton(
                        onClick = { onBrowseClimbs(board.boardBrand, layoutId, board.productSizeId) },
                        // Flush with the card's text instead of the button's usual inset.
                        contentPadding = PaddingValues(start = 0.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Text(stringResource(R.string.map_marker_browse_climbs))
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The facts one board card lists, in reading order; only what the data has. */
@Composable
private fun boardFacts(board: BoardLocation, info: MapBoardInfo?): List<String> {
    val out = mutableListOf<String>()
    when (board.boardBrand) {
        BoardBrand.MOONBOARD -> {
            when (board.accessType) {
                AccessType.PUBLIC -> out += stringResource(R.string.map_board_commercial)
                AccessType.PRIVATE -> out += stringResource(R.string.map_board_home_setup)
                AccessType.MEMBERS -> out += stringResource(R.string.map_access_members)
                AccessType.UNKNOWN -> Unit
            }
            when (board.hasLed) {
                true -> out += stringResource(R.string.map_board_led)
                false -> out += stringResource(R.string.map_board_no_led)
                null -> Unit
            }
            (board.fixedAngle ?: info?.angle)?.let { out += stringResource(R.string.map_board_angle, it) }
            return listOf(out.joinToString(" · ")).filter(String::isNotBlank)
        }
        else -> Unit
    }
    val size = board.productSizeId?.let { id ->
        BoardConstants.sizeLabel(id.toLong(), board.sizeLabel.orEmpty(), board.boardBrand)
    } ?: board.sizeLabel
    val layout = board.layoutName
    size?.takeIf(String::isNotBlank)
        ?.let { if (layout != null && it.startsWith("$layout ", ignoreCase = true)) it.substring(layout.length + 1) else it }
        ?.let { out += it }
    angleFact(board.adjustability, info, board.fixedAngle)?.let { out += angleFactText(it) }
    when (board.accessType) {
        AccessType.PUBLIC -> out += stringResource(R.string.map_access_public)
        AccessType.PRIVATE -> out += stringResource(R.string.map_access_private)
        AccessType.MEMBERS -> out += stringResource(R.string.map_access_members)
        AccessType.UNKNOWN -> Unit
    }
    val line = out.joinToString(" · ")
    val lines = mutableListOf<String>()
    if (line.isNotBlank()) lines += line
    info?.wallName?.takeIf { it.isNotBlank() && !it.equals(board.layoutName, ignoreCase = true) }
        ?.let { lines += stringResource(R.string.map_wall_name, it) }
    board.frameMaker?.takeIf(String::isNotBlank)?.let { lines += stringResource(R.string.map_board_frame, it) }
    info?.models?.let { lines += it }
    return lines
}

/** How a board's angle reads, mirroring the cruxcoach.org board map. */
sealed interface AngleFact {
    data class Range(val min: Int, val max: Int, val step: Int?) : AngleFact
    data class SetTo(val angle: Int) : AngleFact
    data object Adjustable : AngleFact
    data class FixedAt(val angle: Int) : AngleFact
    data object Fixed : AngleFact
}

fun angleFact(adjustability: Adjustability, info: MapBoardInfo?, fixedAngle: Int?): AngleFact? = when (adjustability) {
    Adjustability.ADJUSTABLE, Adjustability.FULL, Adjustability.LIMITED -> {
        val min = info?.minAngle
        val max = info?.maxAngle
        when {
            min != null && max != null && max > min -> AngleFact.Range(min, max, info.angleStep)
            info?.angle != null -> AngleFact.SetTo(info.angle)
            else -> AngleFact.Adjustable
        }
    }
    Adjustability.FIXED -> (fixedAngle ?: info?.angle)?.let { AngleFact.FixedAt(it) } ?: AngleFact.Fixed
    Adjustability.UNKNOWN -> null
}

@Composable
private fun angleFactText(fact: AngleFact): String = when (fact) {
    is AngleFact.Range -> if (fact.step != null) {
        stringResource(R.string.map_wall_adjustable_range_step, fact.min, fact.max, fact.step)
    } else {
        stringResource(R.string.map_wall_adjustable_range, fact.min, fact.max)
    }
    is AngleFact.SetTo -> stringResource(R.string.map_wall_adjustable_set, fact.angle)
    AngleFact.Adjustable -> stringResource(R.string.map_wall_adjustable)
    is AngleFact.FixedAt -> stringResource(R.string.map_wall_fixed_at, fact.angle)
    AngleFact.Fixed -> stringResource(R.string.map_wall_fixed)
}

@Composable
private fun HoursSection(hours: VenueHours, source: String?, website: String?, venueId: String) {
    val context = LocalContext.current
    val days = stringArrayResource(R.array.map_hours_days).toList()
    val closed = stringResource(R.string.map_hours_closed)
    val allDay = stringResource(R.string.map_hours_all_day)
    val nextDay = stringResource(R.string.map_hours_next_day)
    SectionTitle(stringResource(R.string.map_hours_title))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("map_venue_hours")) {
        hours.groups().forEach { group ->
            Row {
                Text(
                    formatHoursDays(group, days),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(76.dp),
                )
                Text(
                    formatHoursRanges(group.ranges, closed, allDay, nextDay),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (group.ranges.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
    Text(
        stringResource(R.string.map_hours_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // The source is linked only when it is a different page from the website
    // the summary already offers; otherwise that link is the source.
    val sourceUri = validatedHttpUrlOrNull(source)
    if (sourceUri != null && source != website) {
        TextButton(
            onClick = { safeStartActivity(context, Intent(Intent.ACTION_VIEW, sourceUri), venueId, "hours-source") },
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) { Text(stringResource(R.string.map_hours_source), fontSize = 13.sp) }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private const val TAG_SHEET = "BoardLocationSheet"

/** The board at the venue with the most contact detail. */
internal fun MapVenue.contactBoard(): BoardLocation =
    boards.maxByOrNull { listOf(it.phone, it.email, it.url, it.address).count { v -> !v.isNullOrBlank() } }
        ?: boards.first()

/** Human label for one board within a venue card. MoonBoard → variant name;
 *  Kilter → "Kilter · <layout>"; every other family → its brand name. */
private fun BoardLocation.boardTitle(unknownMoonVariant: String): String = when (boardBrand) {
    BoardBrand.MOONBOARD ->
        layoutId?.toLong()?.let { MoonBoardVariant.fromLayoutId(it)?.displayName }
            ?: layoutName ?: unknownMoonVariant
    BoardBrand.KILTER -> {
        val name = layoutName ?: when (layoutId) {
            BoardConstants.KILTER_ORIGINAL_LAYOUT -> "Original"
            BoardConstants.KILTER_HOMEWALL_LAYOUT -> "Homewall"
            else -> null
        }
        if (name == null) BoardBrand.KILTER.displayName else "${BoardBrand.KILTER.displayName} · $name"
    }
    // Every other family — Tension, Grasshopper, Decoy, So iLL, Touchstone,
    // Quantum plus the map-only Aurora / 12 Climb — uses its brand display
    // name (a Tension+Grasshopper venue once rendered as two "Kilter" rows).
    else -> boardBrand.displayName
}

/**
 * Strip the input down to dialer-safe characters. Returns null when the
 * cleaned value is empty so the action stays hidden instead of launching a
 * no-op `tel:` intent.
 */
internal fun sanitisedPhoneOrNull(raw: String?): String? {
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

/** An Instagram handle ("@gym", "gym") or profile URL as a validated https link. */
internal fun instagramUrlOrNull(raw: String?): Uri? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val handle = trimmed
        .substringAfter("instagram.com/", trimmed)
        .removePrefix("@")
        .substringBefore('/')
        .substringBefore('?')
    if (!Regex("^[A-Za-z0-9._]{1,30}$").matches(handle)) return null
    return Uri.parse("https://www.instagram.com/$handle/")
}

/** Accept the URL only if its parsed scheme is http(s) AND its host is not a
 *  private / loopback / link-local address. The gym website comes from the
 *  third-party locations dataset, so a crafted record could otherwise point
 *  the browser at an internal host (e.g. a router admin page). */
internal fun validatedHttpUrlOrNull(raw: String?): Uri? {
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

internal fun safeStartActivity(
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
