package com.example.llmchat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llmchat.net.LlmClient
import com.example.llmchat.net.LlmSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ChatMessage(val role: Role, val content: String) {
    enum class Role { User, Assistant, System }
}

data class UiState(
    val settings: LlmSettings,
    val messages: List<ChatMessage> = listOf(ChatMessage(ChatMessage.Role.System,
        "You are a helpful assistant.")),
    val input: String = "",
    val sending: Boolean = false,
    val error: String? = null
)

class ChatViewModel(
    initialSettings: LlmSettings
) : ViewModel() {

    private var client = LlmClient(initialSettings)
    private val _state = MutableStateFlow(UiState(settings = initialSettings))
    val state: StateFlow<UiState> = _state

    fun updateSettings(newSettings: LlmSettings) {
        _state.value = _state.value.copy(settings = newSettings)
        client = LlmClient(newSettings)
    }

    fun onInputChange(text: String) {
        _state.value = _state.value.copy(input = text)
    }

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.sending) return

        val newList = _state.value.messages + ChatMessage(ChatMessage.Role.User, text)
        _state.value = _state.value.copy(messages = newList, input = "", sending = true, error = null)

        viewModelScope.launch {
            try {
                val msgs = _state.value.messages.map {
                    val role = when (it.role) {
                        ChatMessage.Role.User -> "user"
                        ChatMessage.Role.Assistant -> "assistant"
                        ChatMessage.Role.System -> "system"
                    }
                    role to it.content
                }
                val reply = client.complete(msgs)
                _state.value = _state.value.copy(
                    messages = _state.value.messages + ChatMessage(ChatMessage.Role.Assistant, reply),
                    sending = false
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(sending = false, error = t.message ?: "Unknown error")
            }
        }
    }
}
