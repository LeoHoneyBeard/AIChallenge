# AIChallenge

AIChallenge — Android‑приложение на Jetpack Compose, которое запускает локальные HTTP/WS‑серверы и связывает их с LLM (Yandex GPT 5.1 и Hugging Face) и встроенными MCP‑инструментами. Приложение разворачивает полностью офлайн‑совместимый UI, умеет сохранять историю диалога, вызывать MCP‑tools и отображать их список прямо на устройстве.

## Возможности
- Два режима чата: `LocalAiServer` (Yandex Cloud) и `HuggingFaceLocal` (Router API) под тем же REST‑контрактом (`/chat`, `/history`, `/clear`).
- Локальный сервер умеет добавлять в системный промпт список доступных MCP‑инструментов и повторно вызывать модель, пока ассистент не завершит цепочку MCP_TOOL.
- История диалога сохраняется в `filesDir/local_ai_history.json`, автоматически сжимается после 10 сообщений и может быть очищена из UI.
- Встроенные MCP‑серверы (на Ktor SSE) публикуют данные кампаний DnD и демонстрируют протокол через экран Tools.
- Хуки для потоковых обновлений issue summary через WebSocket и GitHub MCP‑tool (по умолчанию отключены, см. ниже).

## Секреты и подготовка окружения
Все чувствительные ключи читаются из `local.properties` (см. `app/build.gradle.kts`):

```
YANDEX_API_KEY=<серверный API-ключ YandexGPT>
YC_FOLDER_ID=<ID каталога в Yandex Cloud>
HF_TOKEN=<Personal Access Token Hugging Face>
GITHUB_TOKEN=<опционально, нужен для github_issue_comments и github_repos>
```

- Без `YANDEX_API_KEY`/`YC_FOLDER_ID` `LocalAiServer` вернёт 400.
- Без `HF_TOKEN` `HuggingFaceLocal` не сможет вызвать Router API.
- `GITHUB_TOKEN` требуется только если хочется включить GitHub‑инструменты в `McpServerManager` и/или автоматическое issue summary.

## Как запустить
1. Синхронизируйте проект и соберите приложение (`./gradlew installDebug` либо Run в Android Studio).
2. При запуске появится `StartScreen` (`MainActivity`): отсюда можно
   - запустить чат с Yandex (`Yandex Chat (LocalAiServer)`), 
   - запустить чат с HuggingFace (`Hugging Chat`),
   - открыть список MCP‑инструментов (`MCP Tools`),
   - стартовать/остановить оба MCP‑сервера одной кнопкой.
3. Кнопка запуска чата стартует соответствующий NanoHTTPD‑сервер на `127.0.0.1:8080` и переключает UI на Compose‑чат.
4. Для Hugging Face можно выбрать модель через выпадающий список (`Model` enum). История чата общая, поэтому после смены сервера её лучше очистить.

## UI и клиентская часть
- `StartScreen` (в `MainActivity.kt`) показывает состояние MCP‑серверов и их endpoint‑ы, а также отвечает за life‑cycle локальных HTTP‑серверов.
- `ChatScreen` (`chat/ChatScreen.kt`) — UI для любого локального сервера: автоскролл, копирование сообщения по тапу, очистка истории, индикатор отправки.
- `HuggingChatScreen` добавляет выбор модели Hugging Face, но использует тот же `ChatViewModel`.
- `ChatViewModel` (`chat/ChatViewModel.kt`) работает с `LocalApi` (Retrofit, `api/LocalApi.kt`) и:
  - подгружает историю через `GET /history`,
  - отправляет сообщения `POST /chat`,
  - очищает историю `POST /clear`,
  - умеет держать WebSocket `ws://127.0.0.1:8080/issueSummary`, но константа `ISSUE_SUMMARY_WS_ENABLED` сейчас `false`.
- `ApiModule` фиксирует базовый URL `http://127.0.0.1:8080/`, поэтому любой сервер должен соблюдать контракт `/chat`/`/history`/`/clear`.

### История и issue summary
- `LocalAiServer` хранит «перманентную» историю (для моделей, которые должны помнить всё) и чистую историю, используя файл `local_ai_history.json`.
- После каждой реплики проверяется лимит `MAX_HISTORY_MESSAGES` (10), и при превышении запускается запрос к LLM для сжатия истории (см. `requestHistoryCompression`).
- WebSocket `/issueSummary` и фоновый job включаются флагом `ISSUE_SUMMARY_FEATURE_ENABLED`. Чтобы поток заработал, нужно:
  1. включить флаги в `LocalAiServer` и `ChatViewModel`,
  2. указать `GITHUB_TOKEN`,
  3. запустить MCP‑сервер с инструментом `github_issue_comments`.

## Локальные LLM‑серверы

### LocalAiServer (Yandex GPT 5.1)
Реализован в `server/LocalAiServer.kt` и наследует `NanoWSD`, поэтому объединяет REST и WebSocket:

- **REST API:** `POST /chat`, `GET /history`, `POST /clear`. В ответе сервера добавляются счётчики токенов и `request_time_ms`.
- **Сбор промпта:** система (`Role.DEFAULT`), описание инструментов (`buildToolSummary()` вызывает `McpClient.fetchAllTools()`), сохранённая история и новое сообщение пользователя.
- **Вызов MCP tools:** сервер ищет маркер `MCP_TOOL:` в ответе LLM. Если он есть, JSON парсится в (`server`, `name`, `parameters`), вызывается `McpClient.callTool`, а результат возвращается модели в виде новой user‑реплики. Цикл продолжается, пока модель не перестанет запрашивать инструменты.
- **Персистентность:** история дублируется в `permanentHistory` и на диск. Фоновая компрессия держится в `CoroutineScope` с `SupervisorJob`.
- **Issue summary:** при включении `ISSUE_SUMMARY_FEATURE_ENABLED` каждые `ISSUE_SUMMARY_REFRESH_MS` миллисекунд подтягиваются комментарии через MCP‑tool `github_issue_comments`, summary генерится моделью и рассылается через WebSocket (плюс heartbeat каждые 10 секунд).

### HuggingFaceLocal
(`server/HuggingFaceLocal.kt`)
- Работает с Router API (`https://router.huggingface.co/v1/chat/completions`) и принимает те же запросы `/chat`.
- Список моделей задаётся enum `Model` (`server/Model.kt`). Переход на модель с `isPermanentHistoryNeeded = true` переключает использование «перманентной» истории.
- В ответ также добавляются счётчики токенов Hugging Face.
- Сервер регистрируется в `HuggingServerRegistry`, чтобы UI мог менять модель на лету.

## MCP‑слой

### McpServers и ToolsScreen
- `McpServers` (`mcp/McpServers.kt`) описывает все встроенные MCP‑серверы: `core` и `knowledge`. Каждый знает, как запуститься и какой endpoint (`http://127.0.0.1:11020/mcp` и `http://127.0.0.1:11030/mcp`).
- `McpServers.startAll()` вызывается с главного экрана. `McpClient` перед каждым запросом убеждается, что сервер запущен.
- `ToolsScreen` + `ToolsViewModel` (`mcp/ToolsScreen.kt`, `mcp/ToolsViewModel.kt`) показывают список всех серверов и их инструментов, используя `McpClient.fetchAllTools()`.

### McpServerManager (core)
- Реализует SSE‑сервер на порту `11020` (`mcp/McpServerManager.kt`).
- Сейчас активны инструменты:
  - `dnd_campaigns` — читает `app/src/main/assets/mcp/my_dnd_campaigns.json`.
  - `dungeon_masters` — читает `app/src/main/assets/mcp/dungeons_masters.json`.
- Код GitHub‑инструментов (`github_repos`, `github_issue_comments`) оставлен в файле, но отключён многострочным комментарием. Чтобы их вернуть, раскомментируйте блок и убедитесь, что `GITHUB_TOKEN` задан.
- Сервер также добавляет ресурс `local://ai-challenge/status` и prompt `local_capabilities`, чтобы MCP‑клиент мог узнать об окружении.

### LocalKnowledgeMcpServerManager (knowledge)
- SSE‑сервер на `11030` (`mcp/LocalKnowledgeMcpServerManager.kt`) с двумя инструментами:
  - `dnd_characters` — структура персонажей из `app/src/main/assets/mcp/dnd_characters.json`.
  - `dnd_spells_for_class` — фильтрует `dnd_spells.json` по полю `class_names` (массив строк) или устаревшему `class_name`.
- Эти данные не требуют токенов и доступны офлайн.

### MCP_TOOL формат
LocalAiServer ожидает текстовые вставки вида:

```
MCP_TOOL: {"server":"core","name":"github_issue_comments","parameters":{"per_page":10}}
```

- `server` необязателен — по умолчанию используется `McpServers.primaryServerId` (`core`).
- После вызова инструмента его результат добавляется в роль `user` и модель должна продолжить ответ, учитывая новые данные.

## Поток данных end-to-end
1. Пользователь отправляет сообщение из `ChatScreen`, `ChatViewModel.sendMessage()` делает `POST /chat`.
2. `LocalAiServer` (или `HuggingFaceLocal`) собирает полный список сообщений и вызывает облачную LLM.
3. Если модель запросила MCP_TOOL, сервер вызывает нужный инструмент через `McpClient`, добавляет результат и повторяет запрос к LLM.
4. Ответ (плюс метаданные) возвращается клиенту, сохраняется в истории и сразу отображается в UI.
5. (Опционально) WebSocket `/issueSummary` пушит свежие summary, как только фоновые задания их сгенерировали.

## Основные файлы
- `app/src/main/java/com/example/aichallenge/MainActivity.kt` — стартовый экран и управление жизненным циклом локальных серверов.
- `app/src/main/java/com/example/aichallenge/chat/ChatViewModel.kt` и `ChatScreen.kt` — основная клиентская логика.
- `app/src/main/java/com/example/aichallenge/server/LocalAiServer.kt` — локальный сервер с поддержкой MCP_TOOL, истории и WebSocket.
- `app/src/main/java/com/example/aichallenge/server/HuggingFaceLocal.kt` — альтернатива на Router API.
- `app/src/main/java/com/example/aichallenge/mcp/*` — инфраструктура MCP (серверы, клиент, экран инструментов).

Документ обновлён, чтобы отразить актуальное состояние кодовой базы: два чат‑сервера, отключённые по умолчанию GitHub‑инструменты, сжатие истории и новые экраны управления MCP.
