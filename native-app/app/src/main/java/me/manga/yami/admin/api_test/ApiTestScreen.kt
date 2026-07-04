package me.manga.yamiapk.admin.api_test

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

data class KeyValuePair(val key: String = "", val value: String = "")

@Composable
fun ApiTestScreen(
    api: IMangaDataApiServices,
    context: Context,
    coroutineScope: CoroutineScope
) {
    var url by remember { mutableStateOf("https://batcave.biz/33234-marvel-rivals-infinity-comic-2024.html") }
    var requestType by remember { mutableStateOf("GET") }
    var headerPairs by remember { mutableStateOf(listOf(KeyValuePair())) }
    var bodyPairs by remember { mutableStateOf(listOf(KeyValuePair())) }
    var responseText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var fileName by remember { mutableStateOf("response.txt") }
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            // Header
            Text(
                text = "API Testing Tool",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Test your API endpoints easily",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))

            // URL Input
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL") },
                placeholder = { Text("Enter endpoint URL") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "URL"
                    )
                },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Request Type Toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Request Method",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = requestType == "GET",
                            onClick = { requestType = "GET" },
                            label = { Text("GET") },
                            modifier = Modifier.weight(1f),
                            leadingIcon = if (requestType == "GET") {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null
                        )
                        FilterChip(
                            selected = requestType == "POST",
                            onClick = { requestType = "POST" },
                            label = { Text("POST") },
                            modifier = Modifier.weight(1f),
                            leadingIcon = if (requestType == "POST") {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Headers Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Headers",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = { headerPairs = headerPairs + KeyValuePair() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Header",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    headerPairs.forEachIndexed { index, pair ->
                        KeyValueRow(
                            pair = pair,
                            onKeyChange = { newKey ->
                                headerPairs = headerPairs.toMutableList().apply {
                                    this[index] = pair.copy(key = newKey)
                                }
                            },
                            onValueChange = { newValue ->
                                headerPairs = headerPairs.toMutableList().apply {
                                    this[index] = pair.copy(value = newValue)
                                }
                            },
                            onDelete = {
                                headerPairs = headerPairs.filterIndexed { i, _ -> i != index }
                            }
                        )
                        if (index < headerPairs.lastIndex) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Body Section (only for POST)
            AnimatedVisibility(visible = requestType == "POST") {
                Column {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Body (Form Data)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                IconButton(
                                    onClick = { bodyPairs = bodyPairs + KeyValuePair() }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Add Body Field",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            bodyPairs.forEachIndexed { index, pair ->
                                KeyValueRow(
                                    pair = pair,
                                    onKeyChange = { newKey ->
                                        bodyPairs = bodyPairs.toMutableList().apply {
                                            this[index] = pair.copy(key = newKey)
                                        }
                                    },
                                    onValueChange = { newValue ->
                                        bodyPairs = bodyPairs.toMutableList().apply {
                                            this[index] = pair.copy(value = newValue)
                                        }
                                    },
                                    onDelete = {
                                        bodyPairs = bodyPairs.filterIndexed { i, _ -> i != index }
                                    }
                                )
                                if (index < bodyPairs.lastIndex) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Send Button
            Button(
                onClick = {
                    isLoading = true
                    coroutineScope.launch {
                        try {
                            // Build headers map from key-value pairs
                            val headerMap = headerPairs
                                .filter { it.key.isNotBlank() && it.value.isNotBlank() }
                                .associate { it.key to it.value }

                            val response = withContext(Dispatchers.IO) {
                                if (requestType == "GET") {
                                    api.get(url = url, headers = headerMap)
                                } else {
                                    // Build form body from key-value pairs
                                    val formBodyBuilder = FormBody.Builder()
                                    bodyPairs
                                        .filter { it.key.isNotBlank() }
                                        .forEach { formBodyBuilder.add(it.key, it.value) }
                                    val requestBody = formBodyBuilder.build()

                                    api.normalPost(url = url, body = requestBody, headers = headerMap)
                                }
                            }

                            withContext(Dispatchers.Main) {
                                if (response.isSuccessful) {
                                    responseText = response.body() ?: "Empty response"
                                } else {
                                    responseText = "Error ${response.code()}: ${response.errorBody()?.string() ?: "Unknown error"}"
                                }
                                isLoading = false
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                responseText = "Request failed: ${e.message}"
                                isLoading = false
                            }
                            Log.e("ApiTest", "Request error", e)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !isLoading && url.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send Request", fontSize = 16.sp)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))

            // Response Section
            if (responseText.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Response",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = { showRenameDialog = true }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = responseText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Rename Dialog
        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Share Response") },
                text = {
                    Column {
                        Text("Enter file name:")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            singleLine = true,
                            placeholder = { Text("response.txt") }
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showRenameDialog = false
                            try {
                                shareTextFile(context, fileName, responseText)
                            } catch (e: Exception) {
                                Log.e("ApiTest", "Failed to share", e)
                            }
                        }
                    ) {
                        Text("Share")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun KeyValueRow(
    pair: KeyValuePair,
    onKeyChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = pair.key,
            onValueChange = onKeyChange,
            label = { Text("Key", style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        OutlinedTextField(
            value = pair.value,
            onValueChange = onValueChange,
            label = { Text("Value", style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}


fun shareTextFile(context: Context, filename: String, text: String) {
    // create file in app cache (overwrites if exists)
    val file = File(context.cacheDir, filename)
    file.writeText(text) // may throw -> will be caught by caller

    // get Uri via FileProvider
    val authority = "${context.packageName}.fileprovider"
    val uri = FileProvider.getUriForFile(context, authority, file)

    // build chooser intent
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // If context is an Activity you can call startActivity directly.
    // If context is application context, add NEW_TASK.
    val launchIntent = Intent.createChooser(shareIntent, "Share file")
    if (context !is Activity) {
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(launchIntent)
}
// Helper function to share text file
