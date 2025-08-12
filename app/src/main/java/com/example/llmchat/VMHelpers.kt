package com.example.llmchat

import androidx.compose.runtime.*
import com.example.llmchat.net.LlmSettings
import com.example.llmchat.ui.ChatViewModel

@Composable
fun rememberViewModel(initialSettings: LlmSettings): ChatViewModel {
    var vm by remember { mutableStateOf<ChatViewModel?>(null) }
    val v = vm ?: ChatViewModel(initialSettings).also { vm = it }
    // при изменении сохранённых настроек — обновляем VM
    LaunchedEffect(initialSettings) { v.updateSettings(initialSettings) }
    return v
}
