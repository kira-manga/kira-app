package me.manga.kira.ui.complaint

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.complaint_backend_unavailable_back
import me.manga.kira.ui.generated.resources.complaint_backend_unavailable_message
import me.manga.kira.ui.generated.resources.complaint_backend_unavailable_title
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/** Explicit refused complaint destination. The caller owns Back; no ViewModel or data access occurs. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
fun ComplaintUnavailableScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalSpacing.current.lg),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(Res.string.complaint_backend_unavailable_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(stringResource(Res.string.complaint_backend_unavailable_message), textAlign = TextAlign.Center)
        TextButton(onClick = onBack) { Text(stringResource(Res.string.complaint_backend_unavailable_back)) }
    }
}

/** Keeps Settings/Language/Sources usable while their non-null complaint callbacks explicitly refuse. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
fun ComplaintUnavailableDialog(onBack: () -> Unit) {
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(stringResource(Res.string.complaint_backend_unavailable_title)) },
        text = { Text(stringResource(Res.string.complaint_backend_unavailable_message)) },
        confirmButton = {
            TextButton(onClick = onBack) { Text(stringResource(Res.string.complaint_backend_unavailable_back)) }
        },
    )
}
