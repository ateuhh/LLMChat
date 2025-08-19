package com.example.llmchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.example.llmchat.data.SettingsStore
import com.example.llmchat.net.LlmSettings
import com.example.llmchat.ui.ChatScreen
import com.example.llmchat.ui.ChatViewModel
import com.example.llmchat.ui.CommitChartScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsStore = SettingsStore(this)

        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val saved by settingsStore.flow.collectAsState(initial = LlmSettings())
                // простой manual ViewModel без Hilt/VM factory
                val viewModel = rememberViewModel(saved)

//                ChatScreen(
//                    state = viewModel.state.collectAsState().value,
//                    onSend = { viewModel.send() },
//                    onInputChange = { viewModel.onInputChange(it) },
//                    onOpenSettings = { /* no-op */ },
//                    onSaveSettings = { new -> scope.launch { settingsStore.save(new) } },
//                    onNewChat = { viewModel.newChat() }
//                )
                CommitChartScreen()
            }
        }
    }
}
