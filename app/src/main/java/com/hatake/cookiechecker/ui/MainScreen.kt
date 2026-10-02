package com.hatake.cookiechecker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hatake.cookiechecker.CookieStatus
import com.hatake.cookiechecker.CookieViewModel
import com.hatake.cookiechecker.ResultFilter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: CookieViewModel) {
    val state by vm.ui.collectAsState()
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var openCookie by remember { mutableStateOf<String?>(null) }
    var webCheck by remember { mutableStateOf(false) }

    openCookie?.let { raw ->
        CookieWebViewScreen(cookie = raw, onClose = { openCookie = null })
        return
    }
    if (webCheck) {
        WebLoginCheckScreen(vm = vm, onClose = { webCheck = false })
        return
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importFile(ctx.contentResolver, it) } }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(vm.exportCsvText().toByteArray())
                }
                vm.markExported(uri.lastPathSegment ?: "results.csv")
            } catch (e: Exception) {
                // surfaced via snackbar below
                scope.launch { snack.showSnackbar("Export failed: ${e.message}") }
            }
        }
    }

    LaunchedEffect(state.errorMessage, state.exportedMessage) {
        state.errorMessage?.let { snack.showSnackbar(it); vm.consumeMessage() }
        state.exportedMessage?.let { snack.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("CookieChecker") }) },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusDashboard(state.total, state.checked, state.live, state.dead, state.errors)
            CheckProgress(state.progress, state.checked, state.total)

            OutlinedTextField(
                value = state.inputText,
                onValueChange = vm::onInputChange,
                label = { Text("Paste cookies (one per line)") },
                modifier = Modifier.fillMaxWidth().height(110.dp),
                maxLines = 6
            )

            OutlinedTextField(
                value = state.targetUrl,
                onValueChange = vm::onTargetChange,
                label = { Text("Target URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                    Text("Import file")
                }
                OutlinedButton(onClick = vm::loadFromInput, modifier = Modifier.weight(1f)) {
                    Text("Parse text")
                }
            }

            // Concurrency + delay controls
            Column {
                Text("Concurrency: ${state.concurrency}  •  Delay: ${state.delayMs}ms", style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Slider(
                        value = state.concurrency.toFloat(), onValueChange = { vm.onConcurrencyChange(it.toInt()) },
                        valueRange = 1f..8f, steps = 6, modifier = Modifier.weight(1f), enabled = !state.isRunning
                    )
                    Slider(
                        value = state.delayMs.toFloat(), onValueChange = { vm.onDelayChange(it.toLong()) },
                        valueRange = 0f..2000f, steps = 19, modifier = Modifier.weight(1f), enabled = !state.isRunning
                    )
                }
            }

            // Start / Pause / Resume / Cancel
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.isRunning) {
                    Button(onClick = vm::start, modifier = Modifier.weight(1f)) { Text("Start check") }
                    OutlinedButton(
                        onClick = {
                            if (vm.ensureQueueFromInput()) {
                                webCheck = true
                            } else {
                                scope.launch { snack.showSnackbar("Paste or import cookies first") }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Web login") }
                } else if (!state.isPaused) {
                    OutlinedButton(onClick = vm::pause, modifier = Modifier.weight(1f)) { Text("Pause") }
                    OutlinedButton(onClick = vm::cancel, modifier = Modifier.weight(1f)) { Text("Stop") }
                } else {
                    Button(onClick = vm::resume, modifier = Modifier.weight(1f)) { Text("Resume") }
                    OutlinedButton(onClick = vm::cancel, modifier = Modifier.weight(1f)) { Text("Stop") }
                }
            }

            // Filters
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.filter == ResultFilter.ALL, onClick = { vm.onFilterChange(ResultFilter.ALL) }, label = { Text("All (${state.total})") })
                FilterChip(selected = state.filter == ResultFilter.LIVE, onClick = { vm.onFilterChange(ResultFilter.LIVE) }, label = { Text("Live (${state.live})") })
                FilterChip(selected = state.filter == ResultFilter.DEAD, onClick = { vm.onFilterChange(ResultFilter.DEAD) }, label = { Text("Dead (${state.dead + state.errors})") })
            }

            // Actions
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val text = vm.liveCookiesText()
                        if (text.isBlank()) scope.launch { snack.showSnackbar("No live cookies to copy") }
                        else {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("live_cookies", text))
                            scope.launch { snack.showSnackbar("Copied ${state.live} live cookies") }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Copy live") }
                OutlinedButton(onClick = { exportLauncher.launch("cookiechecker_results.csv") }, modifier = Modifier.weight(1f)) {
                    Text("Export CSV")
                }
                OutlinedButton(onClick = vm::clear, modifier = Modifier.weight(1f)) { Text("Clear") }
            }

            Spacer(Modifier.height(2.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.visibleItems, key = { it.id }) { item ->
                    CookieRow(
                        item = item,
                        onOpen = if (item.status == CookieStatus.LIVE) {
                            { openCookie = item.raw }
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }
}
