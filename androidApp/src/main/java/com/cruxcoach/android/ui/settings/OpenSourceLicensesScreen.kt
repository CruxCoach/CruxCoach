package com.cruxcoach.android.ui.settings

import android.content.res.AssetManager
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private data class LegalDocument(
    @StringRes val title: Int,
    val assetPaths: List<String>,
    val dependencyReport: Boolean = false,
)

private val LEGAL_DOCUMENTS = listOf(
    LegalDocument(R.string.open_source_licenses_notices, listOf("licenses/NOTICE")),
    LegalDocument(
        R.string.open_source_licenses_inventory,
        listOf("licenses/THIRD_PARTY_LICENSES.md"),
    ),
    LegalDocument(
        R.string.open_source_licenses_dependencies,
        listOf("app/cash/licensee/artifacts.json"),
        dependencyReport = true,
    ),
    LegalDocument(
        R.string.open_source_licenses_project_license,
        listOf("licenses/CruxCoach-GPL-3.0-only.txt"),
    ),
    LegalDocument(
        R.string.open_source_licenses_policy,
        listOf("licenses/LEGAL.md", "licenses/TRADEMARK.md"),
    ),
    LegalDocument(
        R.string.open_source_licenses_texts,
        listOf(
            "licenses/Apache-2.0.txt",
            "licenses/BoardLib-MIT.txt",
            "licenses/JNA-dual-license.txt",
            "licenses/Kilter.jl-MIT.txt",
            "licenses/LazySodium-MPL-2.0.txt",
            "licenses/libsodium-ISC.txt",
            "licenses/libsecp256k1-MIT.txt",
            "licenses/MapLibre-BSD-2-Clause.txt",
            "licenses/Quartz-MIT.txt",
            "licenses/SQLCipher-Community.txt",
            "licenses/zstd-BSD-3-Clause.txt",
            "licenses/zstd-GPL-2.0.txt",
        ),
    ),
)

internal object LegalAssetReader {
    fun read(assetManager: AssetManager, document: List<String>): String =
        document.joinToString(separator = "\n\n") { path ->
            val text = assetManager.open(path).bufferedReader().use { it.readText() }
            if (document.size == 1) text else "===== ${path.substringAfterLast('/')} =====\n\n$text"
        }

    fun formatLicenseeReport(rawJson: String): String {
        val artifacts = Json.parseToJsonElement(rawJson) as JsonArray
        return artifacts
            .map { it as JsonObject }
            .sortedWith(compareBy({ it.text("groupId") }, { it.text("artifactId") }))
            .joinToString(separator = "\n\n") { artifact ->
                val coordinate = listOf(
                    artifact.text("groupId"),
                    artifact.text("artifactId"),
                    artifact.text("version"),
                ).joinToString(":")
                val licenses = artifact.licenses().ifEmpty { listOf("Unknown licence metadata") }
                buildString {
                    append(coordinate)
                    artifact.textOrNull("name")
                        ?.takeUnless { it == artifact.text("artifactId") }
                        ?.let { append("\n").append(it) }
                    append("\n").append(licenses.joinToString("\n"))
                    (artifact["scm"] as? JsonObject)?.textOrNull("url")
                        ?.let { append("\nSource: ").append(it) }
                }
            }
    }

    private fun JsonObject.licenses(): List<String> {
        val spdx = (this["spdxLicenses"] as? JsonArray).orEmpty().mapNotNull { value ->
            val license = value as? JsonObject ?: return@mapNotNull null
            val name = license.textOrNull("identifier") ?: license.textOrNull("name")
            val url = license.textOrNull("url")
            name?.let { if (url == null) it else "$it — $url" }
        }
        val unknown = (this["unknownLicenses"] as? JsonArray).orEmpty().mapNotNull { value ->
            val license = value as? JsonObject ?: return@mapNotNull null
            val name = license.textOrNull("name") ?: "Licence URL"
            val url = license.textOrNull("url")
            if (url == null) name else "$name — $url"
        }
        return (spdx + unknown).distinct()
    }

    private fun JsonObject.text(key: String): String = textOrNull(key).orEmpty()

    private fun JsonObject.textOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList<JsonElement>())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenSourceLicensesScreen(onNavigateBack: () -> Unit) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    val document = LEGAL_DOCUMENTS[selectedIndex]
    val assetManager = LocalContext.current.assets
    val loadError = stringResource(R.string.open_source_licenses_load_error)
    val content by produceState<Result<String>?>(
        initialValue = null,
        key1 = document,
        key2 = assetManager,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val raw = LegalAssetReader.read(assetManager, document.assetPaths)
                if (document.dependencyReport) LegalAssetReader.formatLicenseeReport(raw) else raw
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.open_source_licenses_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.open_source_licenses_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.legal_trademark_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LEGAL_DOCUMENTS.forEachIndexed { index, item ->
                    FilterChip(
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                        label = { Text(stringResource(item.title)) },
                    )
                }
            }
            when (val result = content) {
                null -> CircularProgressIndicator()
                else -> Text(
                    text = result.getOrElse { loadError },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (document.dependencyReport) FontFamily.Monospace else null,
                )
            }
        }
    }
}
