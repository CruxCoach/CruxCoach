package com.cruxcoach.android.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.foodvision.OffRepository
import com.cruxcoach.android.foodvision.VisionModelStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Large nutrition files on the phone (FEAT-069): the unpacked product
 * database and the photo model. Both can go; the database is rebuilt from
 * the APK on the next product search, the model is downloaded again on request.
 */
@HiltViewModel
class FoodDataStorageViewModel @Inject constructor(
    private val products: OffRepository,
    private val models: VisionModelStore,
) : ViewModel() {
    data class Sizes(val productsBytes: Long = 0, val modelBytes: Long = 0)

    private val _sizes = MutableStateFlow(Sizes())
    val sizes: StateFlow<Sizes> = _sizes.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { _sizes.value = Sizes(products.bytesOnDisk(), models.bytesOnDisk()) }
    }

    fun removeProducts() {
        viewModelScope.launch(Dispatchers.IO) { products.remove(); _sizes.value = _sizes.value.copy(productsBytes = products.bytesOnDisk()) }
    }

    fun removeModel() {
        viewModelScope.launch(Dispatchers.IO) { models.remove(); _sizes.value = _sizes.value.copy(modelBytes = models.bytesOnDisk()) }
    }
}

@Composable
internal fun FoodDataStorageSection(viewModel: FoodDataStorageViewModel) {
    val sizes by viewModel.sizes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("settings_food_storage")) {
        Text(stringResource(R.string.settings_food_data_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        StorageRow(
            label = stringResource(R.string.settings_food_products, size(sizes.productsBytes)),
            hint = stringResource(R.string.settings_food_products_hint),
            enabled = sizes.productsBytes > 0,
            tag = "settings_food_products_remove",
            onRemove = viewModel::removeProducts,
        )
        // The photo model only takes space once downloaded; on phones that cannot run it, it never is.
        if (sizes.modelBytes > 0) StorageRow(
            label = stringResource(R.string.settings_food_model, size(sizes.modelBytes)),
            hint = stringResource(R.string.settings_food_model_hint),
            enabled = sizes.modelBytes > 0,
            tag = "settings_food_model_remove",
            onRemove = viewModel::removeModel,
        )
    }
}

@Composable
private fun StorageRow(label: String, hint: String, enabled: Boolean, tag: String, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onRemove, enabled = enabled, modifier = Modifier.testTag(tag)) {
            Text(stringResource(R.string.settings_food_remove))
        }
    }
}

