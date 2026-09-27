package com.example.ondevicetts

data class ChatMessage(
    val sender: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: String,
    var isInterim: Boolean = false
)
