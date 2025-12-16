package com.example.aichallenge.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

@Composable
fun ChatScreen(modifier: Modifier = Modifier, vm: ChatViewModel = viewModel(), onBack: () -> Unit = {}) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    DisposableEffect(vm) {
        vm.startIssueSummaryUpdates()
        onDispose { vm.stopIssueSummaryUpdates() }
    }

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

    var pendingVoice by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    val speechRecognizer = remember(context) {
        SpeechRecognizer.createSpeechRecognizer(context)
    }

    DisposableEffect(speechRecognizer) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                isListening = false
                Toast.makeText(context, "Ошибка распознавания ($error)", Toast.LENGTH_SHORT).show()
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                if (spoken.isNullOrEmpty()) {
                    Toast.makeText(context, "Не удалось распознать речь", Toast.LENGTH_SHORT).show()
                    return
                }
                vm.updateInput(spoken)
                vm.sendMessage()
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        speechRecognizer.setRecognitionListener(listener)
        onDispose {
            isListening = false
            speechRecognizer.cancel()
            speechRecognizer.destroy()
        }
    }

    val startVoiceRecognition: () -> Unit = voiceStart@{
        if (isListening) return@voiceStart
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, "Распознавание речи недоступно", Toast.LENGTH_LONG).show()
            return@voiceStart
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите сообщение")
        }
        runCatching {
            speechRecognizer.startListening(intent)
            isListening = true
        }.onFailure {
            isListening = false
            Toast.makeText(context, "Не удалось запустить распознавание", Toast.LENGTH_LONG).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingVoice) {
            pendingVoice = false
            startVoiceRecognition()
        } else {
            if (!granted) {
                Toast.makeText(context, "Нужно разрешение на микрофон", Toast.LENGTH_LONG).show()
            }
            pendingVoice = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().then(modifier)) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                        .weight(1f),
                    placeholder = { Text("Введите сообщение...") },
                    minLines = 1,
                    maxLines = 4,
                    singleLine = false
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        val permissionState = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        )
                        if (permissionState == PackageManager.PERMISSION_GRANTED) {
                            startVoiceRecognition()
                        } else {
                            pendingVoice = true
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !vm.isSending && !isListening
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Голосовой ввод"
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                FilledIconButton(
                    onClick = vm::sendMessage,
                    enabled = !vm.isSending
                ) {
                    if (vm.isSending) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Filled.ArrowUpward,
                            contentDescription = "Отправить сообщение"
                        )
                    }
                }
            }
        }

        if (isListening) {
            val pulse by rememberInfiniteTransition(label = "voicePulse")
                .animateFloat(
                    initialValue = 0.9f,
                    targetValue = 1.1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "voicePulseAnim"
                )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .size(96.dp)
                            .graphicsLayer {
                                scaleX = pulse
                                scaleY = pulse
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .padding(24.dp)
                                .size(48.dp)
                        )
                    }
                    Text(
                        text = "Говорите...",
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }
    }
}
