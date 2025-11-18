package com.example.aichallenge.chat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ChatScreen(modifier: Modifier = Modifier, vm: ChatViewModel = viewModel(), onBack: () -> Unit = {}) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) {
            listState.animateScrollToItem(vm.messages.lastIndex)
        }
    }

    // Show errors as toasts
    LaunchedEffect(Unit) {
        vm.errors.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().then(modifier)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = vm::clearHistory, enabled = !vm.isSending) {
                Text("Очистить чат")
            }
        }
        // Messages list pinned to bottom when few
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
            contentPadding = PaddingValues(top = WindowInsets.ime.asPaddingValues().calculateBottomPadding(), bottom = 8.dp)
        ) {
            items(vm.messages, key = { it.id }) { msg ->
                val isUser = msg.role == ChatMessage.Role.USER
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    val shape = if (isUser) {
                        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
                    } else {
                        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
                    }
                    Surface(
                        color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = shape,
                        shadowElevation = 1.dp,
                        tonalElevation = 1.dp,
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .fillMaxWidth(0.9f)
                            .clickable {
                                clipboard.setText(AnnotatedString(msg.text))
                                Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Text(
                            text = msg.text,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = if (isUser) TextAlign.End else TextAlign.Start
                        )
                    }
                }
            }
        }

        // Input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = vm.input,
                onValueChange = vm::updateInput,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                placeholder = { Text("Введите сообщение...") },
                singleLine = true
            )
            Button(onClick = vm::sendMessage, enabled = !vm.isSending) {
                if (vm.isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Отправить")
                }
            }
        }
    }
}
