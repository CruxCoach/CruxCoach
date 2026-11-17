package com.cruxcoach.android.ui.devcontact

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.openUriOrToast

/**
 * The text of a message, announcement or report that people can copy: a long
 * press selects it, with the system's Copy and Select all, on a link too. A
 * quick tap on an http or https link opens it in the browser; no other kind of
 * link is opened. A tap anywhere else goes to what holds the text, so a card
 * still opens its thread or marks an announcement read, while the release of
 * a long press does not. Screen readers offer each link as an action.
 * [onLinkOpened] runs when a link is opened. Text on the orange of a sent
 * bubble passes [onAccentSelectionColors], since the theme's own selection
 * colours are that orange too.
 *
 * The links are drawn and hit-tested here instead of as `LinkAnnotation`s:
 * those take the press, so a long press on a link opened it instead of
 * selecting it, and copying a link is what people most want here.
 */
@Composable
internal fun MessageText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    selectionColors: TextSelectionColors = LocalTextSelectionColors.current,
    onLinkOpened: () -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val afterLink by rememberUpdatedState(onLinkOpened)
    val links = remember(text) { messageLinks(text) }
    val shown = remember(text, links) { underlined(text, links) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val open = { link: MessageLink ->
        uriHandler.openUriOrToast(context, link.url)
        afterLink()
    }
    val actionLabels = links.map { stringResource(R.string.message_open_link, it.url) }
    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        SelectionContainer(
            modifier
                .semantics {
                    if (links.isNotEmpty()) {
                        customActions = links.mapIndexed { i, link ->
                            CustomAccessibilityAction(actionLabels[i]) { open(link); true }
                        }
                    }
                }
                .pointerInput(links) { detectMessageTaps(linkAt = { layout?.linkAt(it, links) }, open = open) },
        ) {
            Text(
                text = shown,
                style = style,
                color = color,
                fontWeight = fontWeight,
                fontFamily = fontFamily,
                maxLines = maxLines,
                overflow = overflow,
                onTextLayout = { layout = it },
            )
        }
    }
}

/**
 * Selection colours for [textColor] on the brand orange: the highlight is a veil of
 * the text colour, the handles take the colour of text on the screen, because they
 * hang out of the bubble onto the background.
 */
@Composable
internal fun onAccentSelectionColors(textColor: Color) = TextSelectionColors(
    handleColor = MaterialTheme.colorScheme.onSurface,
    backgroundColor = textColor.copy(alpha = 0.3f),
)

/**
 * Taps on the text. This sits around the text, so the selection inside sees
 * every press first and the card outside sees it last.
 */
private suspend fun PointerInputScope.detectMessageTaps(
    linkAt: (Offset) -> MessageLink?,
    open: (MessageLink) -> Unit,
) = awaitEachGesture {
    awaitFirstDown()
    var cancelled = false
    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        waitForUpOrCancellation().also { cancelled = it == null }
    }
    when {
        // A quick tap: a link opens, anything else stays for the card.
        up != null -> linkAt(up.position)?.let { link ->
            up.consume()
            open(link)
        }
        // Scrolled or taken elsewhere.
        cancelled -> Unit
        // A long press selects the text; letting go is no tap on the card.
        else -> do {
            val event = awaitPointerEvent()
            event.changes.forEach { if (it.changedToUpIgnoreConsumed()) it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/** The link drawn under [position], if any: on its glyphs, not in the empty space beside a line. */
private fun TextLayoutResult.linkAt(position: Offset, links: List<MessageLink>): MessageLink? {
    if (links.isEmpty()) return null
    val offset = getOffsetForPosition(position)
    // The offset is the nearest caret position, so the character can stand on either side of it.
    return listOf(offset - 1, offset).firstNotNullOfOrNull { at ->
        links.firstOrNull { at in it.range }?.takeIf { getBoundingBox(at).contains(position) }
    }
}

private fun underlined(text: String, links: List<MessageLink>): AnnotatedString {
    if (links.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var at = 0
        for (link in links) {
            append(text, at, link.range.first)
            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                append(text, link.range.first, link.range.last + 1)
            }
            at = link.range.last + 1
        }
        append(text, at, text.length)
    }
}

/** A web link in a message: where it stands in the text, and the address to open. */
internal data class MessageLink(val range: IntRange, val url: String)

// \p{Z} also ends a link at a no-break space, which \s does not match on the JVM.
private val webLink = Regex("""(?i)(?<![\p{L}\p{N}])https?://[^\s\p{Z}<>"]+""")

/** Characters that end a sentence or close a quote rather than belong to the link before them. */
private const val TRAILING = ".,;:!?'\"*_`…“”‘’„«»‹›"

private const val OPENING = "([{"
private const val CLOSING = ")]}"

/**
 * The http and https links in [text], in order. Punctuation after a link (a full
 * stop, a closing quote, a bracket the link did not open) stays out of it, and
 * the scheme is written in lower case so the browser takes "HTTPS://…" too.
 */
internal fun messageLinks(text: String): List<MessageLink> = webLink.findAll(text).mapNotNull { match ->
    val start = match.range.first
    var end = match.range.last + 1
    while (end > start) {
        val last = text[end - 1]
        val close = CLOSING.indexOf(last)
        end = when {
            last in TRAILING -> end - 1
            close >= 0 && text.subSequence(start, end).let { it.count { c -> c == last } > it.count { c -> c == OPENING[close] } } -> end - 1
            else -> break
        }
    }
    val raw = text.substring(start, end)
    val schemeEnd = raw.indexOf("://") + 3
    // "https://" alone, or followed only by punctuation, is not a link.
    if (end - start <= schemeEnd) return@mapNotNull null
    MessageLink(start until end, raw.substring(0, schemeEnd).lowercase() + raw.substring(schemeEnd))
}.toList()
