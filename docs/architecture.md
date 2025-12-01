# Архитектура AIChallenge

## Назначение проекта
AIChallenge — демонстрационное Android-приложение на Jetpack Compose, которое объединяет три сценария работы с LLM: локальный REST-сервер на базе Yandex GPT (`LocalAiServer`), локальный прокси к Router API Hugging Face (`HuggingFaceLocal`) и два встроенных MCP-сервера с инструментами для DnD-сценариев. Главная Activity (`app/src/main/java/com/example/aichallenge/MainActivity.kt`) поднимает выбранный сервер, подключает MCP и отображает нужный экран.

## Состав модулей
- `app/src/main/java/com/example/aichallenge/server/*` — HTTP/Ws-серверы (Yandex и Hugging Face), модели и реестры.
- `app/src/main/java/com/example/aichallenge/chat/*` — Compose-экраны переписки и `ChatViewModel`, который ходит к локальному REST API и слушает WebSocket /issueSummary.
- `app/src/main/java/com/example/aichallenge/api/*` — Retrofit-интерфейсы (`LocalApi`) и конфигурация клиента (`ApiModule`).
- `app/src/main/java/com/example/aichallenge/mcp/*` — встроенные MCP-серверы, клиент и UI-экран инструментов.
- `app/src/main/assets/mcp/*` — статические JSON-компендиумы, которые отдаются MCP-инструментами.

## Основные процессы
1. **Локальный чат (Yandex GPT)**: `MainActivity` создаёт `LocalAiServer`, который слушает `http://127.0.0.1:8080`. `ChatViewModel` отправляет `POST /chat`, читает `GET /history`, чистит `POST /clear`.
2. **Hugging Face чат**: аналогично, но с `HuggingFaceLocal` и выбором модели через `HuggingServerRegistry`.
3. **MCP инструменты**: `McpServerManager` и `LocalKnowledgeMcpServerManager` стартуют два Ktor SSE-сервера (`:11020` и `:11030`). `McpClient` подключается через `SseClientTransport`, а `ToolsScreen` выводит их список.
4. **Issue summary**: опциональная задача `LocalAiServer` регулярно вызывает MCP-инструмент `github_issue_comments`, обрабатывает ответы через Yandex GPT и пушит сводки в WebSocket `/issueSummary`.

## Внешние зависимости
- Retrofit/OkHttp — сетевые вызовы и WebSocket-клиент (`ChatViewModel`, `LocalAiServer`, `HuggingFaceLocal`).
- NanoHTTPD/NanoWSD — лёгкий HTTP+WS сервер внутри процесса.
- Ktor server (CIO, SSE) — транспорт MCP.
- MCP Kotlin SDK — реализация серверов и клиента (tools/resources/prompts API).
- Jetpack Compose — UI.

## Конфигурация окружения
Секреты подтягиваются из `local.properties` (см. `README.md` и `BuildConfig`):
- `YANDEX_API_KEY`, `YC_FOLDER_ID` — доступ к Yandex GPT 5.1.
- `HF_TOKEN` — токен Hugging Face Router API.
- `GITHUB_TOKEN` — для инструментов `github_repos` и `github_issue_comments`.
Переменные инжектируются в `BuildConfig` и используются серверными классами.

## Порты и эндпоинты
- `127.0.0.1:8080` — REST (`/chat`, `/history`, `/clear`) и WS `/issueSummary`.
- `127.0.0.1:11020/mcp` — основной MCP сервер (кампании ДнД, мастера, GitHub).
- `127.0.0.1:11030/mcp` — MCP сервер знаний (персонажи и заклинания).

## Поток данных чата
1. Пользователь вводит текст в `ChatScreen` → `ChatViewModel.sendMessage()`.
2. ViewModel вызывает `LocalApi.chatWithRestrictions()` → HTTP запрос к локальному серверу.
3. Сервер (`LocalAiServer` или `HuggingFaceLocal`) дополняет историю, вызывает внешнее LLM API и возвращает текст + статистику токенов.
4. Ответ показывается в UI. Запросы `clearHistory()` и `loadHistory()` синхронизируют персистентное состояние.

## Дополнительные сценарии
- **Сжатие истории** — `LocalAiServer` при превышении `MAX_HISTORY_MESSAGES` вызывает дополнительный промпт к Yandex GPT, сохраняет summary и очищает историю.
- **Подсказки MCP_TOOL** — сервер добавляет в system-подсказку описание доступных инструментов и парсит `MCP_TOOL:{...}` маркеры в ответе модели, чтобы вызывать `McpClient.callTool()`.
- **Выбор модели HF** — `HuggingChatScreen` отображает меню из `Model.values()` и сообщает выбору серверу через `HuggingServerRegistry`.

## Как расширять
- Новый экран → добавить Composable в `MainActivity` и при необходимости ViewModel.
- Новый MCP инструмент → поместить данные в assets и описать его в `McpServerManager` или `LocalKnowledgeMcpServerManager`.
- Новую LLM интеграцию → реализовать `NanoHTTPD`-сервер по аналогии и подключить его на `StartScreen`.
