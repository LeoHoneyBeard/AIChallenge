# LocalAiServer (Yandex GPT 5.1)

`app/src/main/java/com/example/aichallenge/server/LocalAiServer.kt` — встроенный HTTP+WebSocket сервер, который оборачивает API Yandex GPT 5.1 и умеет:
- Хранить и сжимать историю переписки на диске (`filesDir/local_ai_history.json`).
- Проксировать запросы `/chat`, `/history`, `/clear` для `ChatViewModel`.
- Подмешивать в system prompt описание MCP-инструментов, автоматически обрабатывать ответы вида `MCP_TOOL:{...}` и подставлять результаты вызова обратно в диалог.
- Запускать дополнительный пайплайн issue summary через MCP-инструмент GitHub.

## REST API
| Метод | URI | Назначение |
|-------|-----|------------|
| `POST` | `/chat` | Принимает JSON `{ "prompt": "..." }`, собирает полный список сообщений (system + история + новый пользователь), вызывает Yandex GPT и возвращает текст вместе со статистикой токенов и временем ответа. |
| `GET` | `/history` | Возвращает `{ "messages": [ {"role":"user|assistant", "text":"..."}, ... ] }`, чтобы ViewModel могла восстановить UI. |
| `POST` | `/clear` | Очищает историю и персистентный файл. |

Все ответы дополняются CORS-заголовками и кодами ошибок (400 при пропущенных ключах, 500 для внутренних ошибок). Валидация гарантирует, что `YANDEX_API_KEY` и `YC_FOLDER_ID` присутствуют.

## Работа с историей
- История хранится в `permanentHistory` и синхронно сохраняется в `local_ai_history.json` при каждом запросе.
- После каждого диалога сервер проверяет размер списка. Если сообщений > `MAX_HISTORY_MESSAGES` (10), запускается задача `maybeScheduleCompression()`, которая обращается к Yandex GPT с промптом `COMPRESSION_SYSTEM_PROMPT` и заменяет историю одним summary.
- `ChatViewModel.loadHistory()` подтягивает JSON и строит `ChatMessage` с ролями USER/BOT.

## MCP-интеграция и tool calling
1. `buildToolSummary()` вызывает `McpClient.fetchAllTools()` и формирует текстовую вставку с описанием серверов, их tool'ов и схем входа.
2. System prompt дополняется инструкциями «как вызвать MCP_TOOL». Пример: `MCP_TOOL: {"server":"core","name":"github_issue_comments","parameters":{"per_page":10}}`.
3. После ответа модели `parseToolRequest()` ищет маркер `MCP_TOOL:` и парсит JSON. Сервер добавляет сообщение с ответом инструмента и повторяет запрос к Yandex GPT, пока модель не перестанет просить инструменты.
4. Для вызова MCP используется `McpClient.callTool()` (SSE) и `McpServers` для выбора endpoint'а. Ошибки инструментов возвращаются в виде текста и тоже становятся частью истории, чтобы модель могла отреагировать.

## Issue summary pipeline (опционально)
Флаги:
- `ISSUE_SUMMARY_FEATURE_ENABLED` — включает периодический запуск `startIssueSummaryJob()`.
- `ISSUE_SUMMARY_WS_ENABLED` (в `ChatViewModel`) — включает клиентскую подписку на WebSocket.

Поток:
1. `fetchIssueCommentsFromMcp()` вызывает `github_issue_comments` на сервере `core` и получает последние комментарии к issue.
2. Текст аккуратно нормализуется и передаётся в Yandex GPT с системным промптом `ISSUE_SUMMARY_SYSTEM_PROMPT`.
3. Ответ сохраняется в историю диалога и транслируется всем WebSocket-подписчикам (`IssueSummaryWebSocket`).
4. Каждые `ISSUE_SUMMARY_HEARTBEAT_INTERVAL_MS` сервер шлёт heartbeat. WebSocket доступен по `ws://127.0.0.1:8080/issueSummary`.

## Потоки/корутины
- Сервер создаёт `serverScope` (SupervisorJob + IO). Через него управляются задачи сжатия и issue summary, а также heartbeat.
- WebSocket клиенты хранятся в `issueSummarySockets` и получают обновления посредством `broadcastWebSocketPayload()`.
- `waitForCompressionIfNeeded()` блокирует входящие HTTP запросы, пока старая задача сжатия не завершится, чтобы не использовать устаревшее состояние.

## Ошибки и логирование
- Все сетевые вызовы (`callYandex`) логируются по уровню `TAG=LocalAiServer` с payload и ответами.
- Ошибки MCP или GitHub сопровождаются `Log.e`, но не валят сервер — пользователю вернётся текст с описанием проблемы.
- При записи истории на диск ошибки логируются, но не пробрасываются наружу.

## Расширение
- Можно добавить новые роли (`Role.kt`) и научить `ChatScreen` переключать их через `LocalServerRegistry`.
- Дополнительные REST endpoints добавляются внутри `serveHttp()` — NanoHTTPD крайне лёгкий.
- MCP инструменты расширяются через `McpServerManager`/`LocalKnowledgeMcpServerManager`: сервер автоматически увидит их и добавит в подсказку.
