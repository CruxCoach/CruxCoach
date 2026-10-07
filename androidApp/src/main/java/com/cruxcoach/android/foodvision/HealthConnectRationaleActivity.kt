package com.cruxcoach.android.foodvision

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R

/**
 * Explains what CruxCoach writes to Health Connect. Health Connect opens it
 * from its permission screen (Android 13 and older: the rationale action;
 * Android 14+: "view permission usage"), so it must not need the rest of the app.
 */
class HealthConnectRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(stringResource(R.string.trf_hc_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.trf_hc_rationale), style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = { finish() }) { Text(stringResource(R.string.trf_intro_got_it)) }
                    }
                }
            }
        }
    }
}
