package com.cruxcoach.android.ui.navigation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R

/**
 * The first-level menu behind the CruxCoach logo — FEAT-058.
 *
 * First-level destinations only. Everything that used to be reachable
 * from the browser's action bar stays exactly where it was: this drawer adds a
 * level above the app, it does not reorganise it.
 */
enum class MainDestination { BOARD_CATALOG, FIPS_MESH }

@Composable
fun MainDrawerSheet(
    selected: MainDestination,
    onSelect: (MainDestination) -> Unit,
) {
    val menuLabel = stringResource(R.string.main_menu_title)
    ModalDrawerSheet(modifier = Modifier.semantics { contentDescription = menuLabel }) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.app_name),
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        NavigationDrawerItem(
            icon = { Icon(Icons.Default.DeveloperBoard, contentDescription = null) },
            label = { Text(stringResource(R.string.main_menu_board_catalog)) },
            selected = selected == MainDestination.BOARD_CATALOG,
            onClick = { onSelect(MainDestination.BOARD_CATALOG) },
            modifier = Modifier
                .padding(NavigationDrawerItemDefaults.ItemPadding)
                .testTag("menu_board_catalog"),
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Hub, contentDescription = null) },
            label = { Text(stringResource(R.string.main_menu_fips_mesh)) },
            selected = selected == MainDestination.FIPS_MESH,
            onClick = { onSelect(MainDestination.FIPS_MESH) },
            modifier = Modifier
                .padding(NavigationDrawerItemDefaults.ItemPadding)
                .testTag("menu_fips_mesh"),
        )
    }
}
