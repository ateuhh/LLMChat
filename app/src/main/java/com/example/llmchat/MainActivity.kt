package com.example.llmchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.example.llmchat.data.SettingsStore
import com.example.llmchat.net.LlmSettings
import com.example.llmchat.ui.ChatScreen
import com.example.llmchat.ui.ChatViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsStore = SettingsStore(this)

        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val saved by settingsStore.flow.collectAsState(initial = LlmSettings())
                var vm by remember { mutableStateOf<ChatViewModel?>(null) }
                val viewModel = vm ?: run {
                    val m = ChatViewModel(saved)
                    vm = m
                    m
                }

                // реагируем на апдейты настроек из DataStore
                LaunchedEffect(saved) {
                    viewModel.updateSettings(saved)
                }

                ChatScreen(
                    state = viewModel.state.collectAsState().value,
                    onSend = { viewModel.send() },
                    onInputChange = { viewModel.onInputChange(it) },
                    onOpenSettings = { showing ->
                        // no-op here; handled in ChatScreen via state
                    },
                    onSaveSettings = { new ->
                        scope.launch { settingsStore.save(new) }
                    }
                )
            }
        }
    }
}
