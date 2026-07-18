package me.manga.kira.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.cancel
import me.manga.kira.ui.generated.resources.crash_diagnostics_confirm_action
import me.manga.kira.ui.generated.resources.crash_diagnostics_confirm_message
import me.manga.kira.ui.generated.resources.crash_diagnostics_confirm_title
import me.manga.kira.ui.generated.resources.crash_diagnostics_intro
import me.manga.kira.ui.generated.resources.crash_diagnostics_scenario_arithmetic
import me.manga.kira.ui.generated.resources.crash_diagnostics_scenario_custom
import me.manga.kira.ui.generated.resources.crash_diagnostics_scenario_illegal_argument
import me.manga.kira.ui.generated.resources.crash_diagnostics_scenario_illegal_state
import me.manga.kira.ui.generated.resources.crash_diagnostics_scenario_out_of_bounds
import me.manga.kira.ui.generated.resources.crash_diagnostics_title
import me.manga.kira.ui.generated.resources.crash_diagnostics_warning_body
import me.manga.kira.ui.generated.resources.crash_diagnostics_warning_title
import me.manga.kira.ui.generated.resources.desc_back
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/** Fatal scenarios intentionally exposed only by protected internal-release configuration. */
enum class CrashDiagnosticsScenario {
    ILLEGAL_STATE,
    ILLEGAL_ARGUMENT,
    OUT_OF_BOUNDS,
    ARITHMETIC,
    CUSTOM_KIRA,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashDiagnosticsScreen(
    onBack: () -> Unit,
    onCrash: (CrashDiagnosticsScenario) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingScenario by remember { mutableStateOf<CrashDiagnosticsScenario?>(null) }
    val spacing = LocalSpacing.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.crash_diagnostics_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.desc_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item(key = "warning") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(spacing.md),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(28.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                            Text(
                                text = stringResource(Res.string.crash_diagnostics_warning_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = stringResource(Res.string.crash_diagnostics_warning_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            item(key = "intro") {
                Text(
                    text = stringResource(Res.string.crash_diagnostics_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(
                count = CrashDiagnosticsScenario.entries.size,
                key = { index -> CrashDiagnosticsScenario.entries[index].name },
            ) { index ->
                val scenario = CrashDiagnosticsScenario.entries[index]
                Button(
                    onClick = { pendingScenario = scenario },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.md),
                ) {
                    Text(
                        text = scenarioLabel(scenario),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }

    pendingScenario?.let { scenario ->
        val label = scenarioLabel(scenario)
        AlertDialog(
            onDismissRequest = { pendingScenario = null },
            title = { Text(stringResource(Res.string.crash_diagnostics_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        Res.string.crash_diagnostics_confirm_message,
                        label,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingScenario = null
                        onCrash(scenario)
                    },
                ) {
                    Text(
                        text = stringResource(Res.string.crash_diagnostics_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingScenario = null }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun scenarioLabel(scenario: CrashDiagnosticsScenario): String = when (scenario) {
    CrashDiagnosticsScenario.ILLEGAL_STATE ->
        stringResource(Res.string.crash_diagnostics_scenario_illegal_state)
    CrashDiagnosticsScenario.ILLEGAL_ARGUMENT ->
        stringResource(Res.string.crash_diagnostics_scenario_illegal_argument)
    CrashDiagnosticsScenario.OUT_OF_BOUNDS ->
        stringResource(Res.string.crash_diagnostics_scenario_out_of_bounds)
    CrashDiagnosticsScenario.ARITHMETIC ->
        stringResource(Res.string.crash_diagnostics_scenario_arithmetic)
    CrashDiagnosticsScenario.CUSTOM_KIRA ->
        stringResource(Res.string.crash_diagnostics_scenario_custom)
}
