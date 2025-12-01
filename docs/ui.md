# UI и поток данных в Compose

## Главное Activity
`MainActivity` отвечает за:
- Включение edge-to-edge и установку темы `AIChallengeTheme`.
- Хранение `NanoHTTPD` сервера (Yandex или Hugging) и переключение по клику на `StartScreen`.
- Запуск/остановку MCP серверов через `McpServers` и вывод статуса (идентификатор, endpoint, описание).
- Навигацию между тремя экранами: `ChatScreen`, `HuggingChatScreen`, `ToolsScreen` через простую `remember { mutableStateOf("start") }`.
- Обработку `BackHandler` на каждом дочернем экране, чтобы корректно остановить сервер и вернуться на старт.

## StartScreen
Composable внутри `MainActivity`. Кнопки:
1. **Yandex Chat** — создаёт `LocalAiServer(8080, context)` и открывает экран `ChatScreen`.
2. **Hugging Chat** — создаёт `HuggingFaceLocal(8080)` и открывает `HuggingChatScreen`.
3. **MCP Tools** — открывает `ToolsScreen` без сервера.
4. **Start/Stop MCP servers** — вызывает `McpServers.startAll()/stopAll()`.
Ниже отображается список MCP серверов с человекочитаемыми названиями.

## ChatScreen
Файл: `app/src/main/java/com/example/aichallenge/chat/ChatScreen.kt`

- Подписывается на `ChatViewModel`: состояние текста (`input`), список сообщений, признак отправки (`isSending`).
- При каждом изменении `vm.messages` автоматически прокручивает `LazyColumn` к последнему элементу.
- Через `LaunchedEffect(Unit)` собирает `vm.errors` и показывает Toast.
- Заголовок содержит кнопку очистки истории `vm.clearHistory()`.
- Список сообщений отображает пузыри с разной формой/цветом для USER/ BOT и даёт возможность скопировать текст в буфер.
- Нижняя панель: однострочное `OutlinedTextField` и кнопка «Отправить», которая показывает `CircularProgressIndicator`, когда `vm.isSending=true`.
- В `DisposableEffect` ViewModel запускает/останавливает WebSocket для issue summary (фича выключена константой).

## ChatViewModel
Файл: `app/src/main/java/com/example/aichallenge/chat/ChatViewModel.kt`

- Сразу загружает историю (`loadHistory()`).
- `sendMessage()` добавляет сообщение пользователя, очищает инпут и вызывает `LocalApi.chatWithRestrictions()` в IO-диспетчере. Ответ добавляется в `messages` на главном потоке.
- Ошибки складываются в `MutableSharedFlow` — UI показывает Toast.
- `clearHistory()` вызывает `POST /clear` и очищает UI.
- WebSocket `/issueSummary` управляется методами `startIssueSummaryUpdates()` и `stopIssueSummaryUpdates()`: создаётся `OkHttpClient`, сообщения `issue_summary` добавляются в чат как сообщения бота.

## HuggingChatScreen
Повторяет ChatScreen, но добавляет тулбар с выбором модели через `DropdownMenu`. Кнопки «Очистить» нет (история очищается автоматически при выборе другой модели через `HuggingServerRegistry`).

## ToolsScreen
Отдельный UI для списка MCP инструментов (см. `docs/mcp.md`). Отображает состояние загрузки, ошибки, список серверов и кнопку Back.

## Общие UX-детали
- Все экраны используют `Modifier.fillMaxSize()` и паддинги, прокладка под системную IME рассчитывается через `WindowInsets.ime`.
- Компоненты Material3: `Scaffold`, `Surface`, `OutlinedTextField`, `Button`, `TextButton`, `CircularProgressIndicator`.
- Все сетевые операции происходят во ViewModel, UI остаётся декларативным и не блокирует поток.

## Расширение UI
- Новые сценарии можно добавить в `MainActivity` как новый `when (screen)` кейс и кнопку на `StartScreen`.
- Для сложной навигации можно подключить `Navigation Compose`, но текущая архитектура намеренно плоская, чтобы проще дебажить локальные серверы.
- Дополнительные индикаторы статуса (например, выбор роли LocalAiServer) легко добавить в верхнюю панель `ChatScreen`, используя `LocalServerRegistry`.
