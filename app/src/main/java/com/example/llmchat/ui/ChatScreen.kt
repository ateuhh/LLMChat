package com.example.llmchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.llmchat.net.LlmSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: UiState,
    onSend: () -> Unit,
    onInputChange: (String) -> Unit,
    onOpenSettings: (Boolean) -> Unit,
    onSaveSettings: (LlmSettings) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LLM Chat") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = onInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Напишите сообщение…") },
                        enabled = !state.sending,
                        singleLine = false,
                        maxLines = 4
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(onClick = onSend, enabled = !state.sending) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.error != null) {
                Text(
                    text = state.error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(8.dp),
                    textAlign = TextAlign.Center
                )
            }
            MessagesList(messages = state.messages)
        }

        if (showSettings) {
            SettingsSheet(
                initial = state.settings,
                onDismiss = { showSettings = false },
                onSave = {
                    onSaveSettings(it)
                    showSettings = false
                }
            )
        }
    }
}

@Composable
private fun MessagesList(messages: List<ChatMessage>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        reverseLayout = false
    ) {
        items(messages) { msg ->
            val isUser = msg.role == ChatMessage.Role.User
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
            ) {
                Surface(
                    color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 1.dp,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = msg.content,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    initial: LlmSettings,
    onDismiss: () -> Unit,
    onSave: (LlmSettings) -> Unit
) {
    var provider by remember { mutableStateOf(initial.provider) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            // Provider
            Text("Provider", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            ProviderChips(provider) { provider = it }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model") },
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    onSave(LlmSettings(provider, baseUrl.trim(), apiKey.trim(), model.trim()))
                }) { Text("Save") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ProviderChips(selected: LlmSettings.Provider, onChange: (LlmSettings.Provider) -> Unit) {
    FlowRowMainAxis {
        FilterChip(
            selected = selected == LlmSettings.Provider.OpenAI,
            onClick = {
                onChange(LlmSettings.Provider.OpenAI)
            },
            label = { Text("OpenAI") }
        )
        FilterChip(
            selected = selected == LlmSettings.Provider.OpenRouter,
            onClick = {
                onChange(LlmSettings.Provider.OpenRouter)
            },
            label = { Text("OpenRouter") }
        )
        FilterChip(
            selected = selected == LlmSettings.Provider.Custom,
            onClick = { onChange(LlmSettings.Provider.Custom) },
            label = { Text("Custom") }
        )
    }
}

// маленький helper без зависимости на Accompanist
@Composable
private fun FlowRowMainAxis(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}
