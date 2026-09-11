package com.cruxcoach.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.util.PerfLogger

internal enum class SettingsPage(
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    @param:StringRes val group: Int,
    val icon: ImageVector,
) {
    DISPLAY(R.string.settings_page_display, R.string.settings_summary_display, R.string.settings_group_climbing, Icons.Outlined.Palette),
    BOARD(R.string.settings_page_board, R.string.settings_summary_board, R.string.settings_group_climbing, Icons.Outlined.Bluetooth),
    TIMERS(R.string.settings_page_timers, R.string.settings_summary_timers, R.string.settings_group_climbing, Icons.Outlined.Timer),
    SHARING(R.string.settings_page_sharing, R.string.settings_summary_sharing, R.string.settings_group_climbing, Icons.Outlined.PeopleOutline),
    ACCOUNT(R.string.settings_page_account, R.string.settings_summary_account, R.string.settings_section_accounts_data, Icons.Outlined.Person),
    IMPORTS(R.string.settings_page_imports, R.string.settings_summary_imports, R.string.settings_section_accounts_data, Icons.Outlined.MoveToInbox),
    CATALOGUES(R.string.settings_group_board_catalogs, R.string.settings_summary_catalogues, R.string.settings_section_accounts_data, Icons.Outlined.CloudDownload),
    BACKUP(R.string.settings_page_backup, R.string.settings_summary_backup, R.string.settings_section_accounts_data, Icons.Outlined.Backup),
    DELETE(R.string.settings_page_delete, R.string.settings_summary_delete, R.string.settings_section_accounts_data, Icons.Outlined.DeleteOutline),
    UPDATES(R.string.updater_settings_title, R.string.settings_summary_updates, R.string.settings_group_app_help, Icons.Outlined.SystemUpdate),
    SUPPORT(R.string.settings_page_support, R.string.settings_summary_support, R.string.settings_group_app_help, Icons.AutoMirrored.Outlined.HelpOutline),
    ABOUT(R.string.settings_page_about, R.string.settings_summary_about, R.string.settings_group_app_help, Icons.Outlined.Info),
}

/** A single settings destination owns its pages, including external-screen returns. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsLayout(
    isLoading: Boolean,
    openUpdates: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateToAppShare: () -> Unit,
    banners: @Composable () -> Unit = {},
    content: @Composable ColumnScope.(SettingsPage) -> Unit,
) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val savedPages = rememberSaveableStateHolder()
    val back = { if (page == null) onNavigateBack() else page = null }
    BackHandler(enabled = page != null) { page = null }
    // Compose the existing confirmation flow even when the request arrives
    // while another settings page is open or the preferences are loading.
    LaunchedEffect(openUpdates) { if (openUpdates) page = SettingsPage.UPDATES }
    LaunchedEffect(page, isLoading) {
        if (!isLoading) PerfLogger.milestone("SETTINGS_PAGE ${page?.name ?: "OVERVIEW"} composable entered")
    }
    val title = stringResource(page?.title ?: R.string.settings_title)
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = back, modifier = Modifier.testTag("settings_back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateToAppShare) {
                            Icon(Icons.Outlined.Share, stringResource(R.string.cd_app_share))
                        }
                    },
                )
                banners()
            }
        },
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            savedPages.SaveableStateProvider(page?.name ?: "overview") {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding)
                        .semantics { paneTitle = title }
                        .testTag("settings_content")
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val currentPage = page
                    if (currentPage == null) {
                        SettingsPage.entries.groupBy { it.group }.forEach { (group, pages) ->
                            SettingsGroupHeader(stringResource(group))
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                pages.forEachIndexed { index, item ->
                                    SettingsDestinationRow(
                                        title = stringResource(item.title),
                                        summary = stringResource(item.summary),
                                        icon = item.icon,
                                        modifier = Modifier.testTag("settings_open_${item.name}"),
                                        onClick = { page = item },
                                    )
                                    if (index < pages.lastIndex) {
                                        HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp))
                                    }
                                }
                            }
                        }
                    } else {
                        content(currentPage)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
internal fun SettingsGroupHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
internal fun SettingsSectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun SettingsDestinationRow(
    title: String,
    summary: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.heightIn(min = 72.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One accessible switch target, with space reserved for its control at any width. */
@Composable
internal fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotEmpty()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Choices wrap with German labels and large fonts; no option lives off-screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> SettingsChoices(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                leadingIcon = if (selected == value) {
                    { Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp)) }
                } else null,
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}
