package com.visus.app.data

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object StreamingUiState {
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    private val _connectionStatus = MutableStateFlow("已停止")
    val connectionStatus: StateFlow<String> = _connectionStatus

    private val _partialText = MutableStateFlow("等待语音输入")
    val partialText: StateFlow<String> = _partialText

    private val _finalMessages = MutableStateFlow<List<String>>(emptyList())
    val finalMessages: StateFlow<List<String>> = _finalMessages

    private val _latestFrame = MutableStateFlow<Bitmap?>(null)
    val latestFrame: StateFlow<Bitmap?> = _latestFrame

    fun setStreaming(value: Boolean) {
        _isStreaming.value = value
        if (!value) {
            _connectionStatus.value = "已停止"
            _partialText.value = "等待语音输入"
        }
    }

    fun setConnectionStatus(value: String) {
        _connectionStatus.value = value
    }

    fun setPartialText(value: String) {
        _partialText.value = value.ifBlank { "等待语音输入" }
    }

    fun addFinalMessage(value: String) {
        val text = value.trim()
        if (text.isEmpty()) return
        val last = _finalMessages.value.lastOrNull()?.trim()
        if (last == text) return
        _finalMessages.value = (_finalMessages.value + text).takeLast(30)
    }

    fun setLatestFrame(value: Bitmap?) {
        _latestFrame.value = value
    }

    fun clearMessages() {
        _partialText.value = "等待语音输入"
        _finalMessages.value = emptyList()
    }
}
