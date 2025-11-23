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
import com.example.aichallenge.chat.HuggingChatScreen
import com.example.aichallenge.server.HuggingFaceLocal
import com.example.aichallenge.server.LocalAiServer
import com.example.aichallenge.ui.theme.AIChallengeTheme
import com.example.aichallenge.mcp.McpServers
import com.example.aichallenge.mcp.McpServers.Entry
import com.example.aichallenge.mcp.ToolsScreen
import fi.iki.elonen.NanoHTTPD
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private var server: NanoHTTPD? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        McpServers.setAppContext(applicationContext)

        setContent {
            AIChallengeTheme {
                var screen by remember { mutableStateOf("start") }
                var isMcpRunning by remember { mutableStateOf(McpServers.isRunning()) }
                val mcpEntries = remember { McpServers.list() }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (screen) {
                        "chat" -> {
                            BackHandler(enabled = true) {
                                stopServer()
                                screen = "start"
                            }
                            ChatScreen(
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                        "hugging" -> {
                            BackHandler(enabled = true) {
                                stopServer()
                                screen = "start"
                            }
                            HuggingChatScreen(
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                        "tools" -> {
                            BackHandler(enabled = true) {
                                screen = "start"
                            }
                            ToolsScreen(
                                onBack = { screen = "start" },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                        else -> StartScreen(
                            onSelectChat = {
                                switchServer(LocalAiServer(8080, applicationContext))
                                screen = "chat"
                            },
                            onSelectHugging = {
                                switchServer(HuggingFaceLocal(8080))
                                screen = "hugging"
                            },
                            onSelectTools = { screen = "tools" },
                            onToggleMcp = {
                                if (McpServers.isRunning()) {
                                    McpServers.stopAll()
                                } else {
                                    McpServers.startAll()
                                }
                                isMcpRunning = McpServers.isRunning()
                            },
                            mcpRunning = isMcpRunning,
                            mcpServers = mcpEntries,
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        server = null
        McpServers.stopAll()
    }

    private fun switchServer(newServer: NanoHTTPD) {
        server?.stop()
        server = newServer
        server?.start(30000, false)
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }
}

@androidx.compose.runtime.Composable
private fun StartScreen(
    onSelectChat: () -> Unit,
    onSelectHugging: () -> Unit,
    onSelectTools: () -> Unit,
    onToggleMcp: () -> Unit,
    mcpRunning: Boolean,
    mcpServers: List<Entry>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = Modifier.fillMaxSize().then(modifier),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = onSelectChat, modifier = Modifier.padding(8.dp)) {
            Text("Yandex Chat (LocalAiServer)")
        }
        Button(onClick = onSelectHugging, modifier = Modifier.padding(8.dp)) {
            Text("Hugging Chat (HuggingFaceLocal)")
        }
        Button(onClick = onSelectTools, modifier = Modifier.padding(8.dp)) {
            Text("MCP Tools")
        }
        Button(onClick = onToggleMcp, modifier = Modifier.padding(8.dp)) {
            Text(if (mcpRunning) "Stop MCP servers" else "Start MCP servers")
        }
        mcpServers.forEach { server ->
            Text(
                text = "${server.displayName} (${server.id}): ${server.endpoint}",
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
