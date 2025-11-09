package com.example.aichallenge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.aichallenge.chat.ChatScreen
import com.example.aichallenge.server.LocalAiServer
import com.example.aichallenge.ui.theme.AIChallengeTheme
import fi.iki.elonen.NanoHTTPD

class MainActivity : ComponentActivity() {
    private var server: LocalAiServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Prepare local AI server on 127.0.0.1:8080 (Yandex Cloud)
        server = LocalAiServer(port = 8080)
        server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

        setContent {
            AIChallengeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ChatScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        server = null
    }
}
