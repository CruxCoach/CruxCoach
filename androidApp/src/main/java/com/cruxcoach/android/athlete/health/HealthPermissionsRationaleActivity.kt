package com.cruxcoach.android.athlete.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachTheme

/**
 * Privacy rationale Health Connect shows from its permission screen
 * (Android 9–13) and Android 14+ from the permission-usage view: what the
 * coach reads, what nutrition writes, why, and that nothing is uploaded.
 */
class HealthPermissionsRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CruxCoachTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
                            .padding(24.dp).testTag("health_rationale"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.trd_rationale_title), style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.trd_rationale_intro), style = MaterialTheme.typography.bodyLarge)
                        Point(stringResource(R.string.trd_rationale_sleep))
                        Point(stringResource(R.string.trd_rationale_exercise))
                        Point(stringResource(R.string.trd_rationale_nutrition))
                        Point(stringResource(R.string.trd_rationale_scope))
                        Point(stringResource(R.string.trd_rationale_local))
                        Point(stringResource(R.string.trd_rationale_revoke))
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.trd_rationale_close))
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Point(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
