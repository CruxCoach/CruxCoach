package com.cruxcoach.app.map

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance (haversine), good to a few metres for sorting. Port of Android `distanceMeters`. */
fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = radians(lat2 - lat1)
    val dLng = radians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
        cos(radians(lat1)) * cos(radians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

private fun radians(degrees: Double): Double = degrees * PI / 180.0

/**
 * Search form of a name: accents folded, lower case, every run of
 * non-letters/digits one space. Android does this with `java.text.Normalizer`
 * (NFKD, marks dropped); common code has no Unicode normalizer, so the Latin
 * letters the board dataset actually contains are folded by table. Letters
 * NFKD would not decompose either (ß, ø, ł) stay as they are, as on Android.
 */
fun normalizeMapSearchText(value: String): String {
    val out = StringBuilder(value.length)
    var pendingSpace = false
    for (raw in value) {
        if (raw in COMBINING_MARKS) continue
        val folded = foldLatin(raw).lowercase()
        if (folded.length == 1 && !folded[0].isLetterOrDigit()) {
            pendingSpace = out.isNotEmpty()
            continue
        }
        if (pendingSpace) out.append(' ')
        pendingSpace = false
        out.append(folded)
    }
    return out.toString().trim()
}

private val COMBINING_MARKS = '̀'..'ͯ'

private const val FOLD_FROM =
    "ÀÁÂÃÄÅàáâãäåĀāĂăĄąÇçĆćĈĉĊċČčĎďÈÉÊËèéêëĒēĔĕĖėĘęĚěĜĝĞğĠġĢģĤĥÌÍÎÏìíîïĨĩĪīĬĭĮįİ" +
        "ĴĵĶķĹĺĻļĽľÑñŃńŅņŇňÒÓÔÕÖòóôõöŌōŎŏŐőŔŕŖŗŘřŚśŜŝŞşŠšŢţŤťÙÚÛÜùúûüŨũŪūŬŭŮůŰűŲų" +
        "ŴŵÝýÿŶŷŸŹźŻżŽžȘșȚț"
private const val FOLD_TO =
    "AAAAAAaaaaaaAaAaAaCcCcCcCcCcDdEEEEeeeeEeEeEeEeEeGgGgGgGgHhIIIIiiiiIiIiIiIiI" +
        "JjKkLlLlLlNnNnNnNnOOOOOoooooOoOoOoRrRrRrSsSsSsSsTtTtUUUUuuuuUuUuUuUuUuUu" +
        "WwYyyYyYZzZzZzSsTt"

private fun foldLatin(c: Char): String {
    if (c.code < 0xC0) return c.toString()
    val index = FOLD_FROM.indexOf(c)
    return if (index >= 0) FOLD_TO[index].toString() else c.toString()
}
