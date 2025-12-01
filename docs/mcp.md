# Встроенные MCP серверы и клиент

## Обзор
Приложение поднимает два локальных MCP сервера поверх Ktor SSE:
1. `McpServerManager` (`core`) — кампании, мастера и (опционально) GitHub-инструменты.
2. `LocalKnowledgeMcpServerManager` (`knowledge`) — персонажи и заклинания DnD.

`McpServers` описывает оба сервера единым списком `Entry`: идентификатор, отображаемое имя, описание, функции `start/stop/isRunning` и `endpointProvider`. `MainActivity` показывает их в `StartScreen` и позволяет запускать/останавливать одной кнопкой.

## MCP Server Manager (core)
Файл: `app/src/main/java/com/example/aichallenge/mcp/McpServerManager.kt`

- Использует `embeddedServer(CIO)` и модуль `SSE`. SSE endpoint — `GET /mcp`, POST канал — `/mcp/message?sessionId=...` (требование MCP-плагина).
- При подключении создаёт `ServerSession` через MCP Kotlin SDK (`Server`, `SseServerTransport`). Сессии складываются в `ConcurrentHashMap`, чтобы POST handler знал, куда доставить сообщение.
- `instructions` объясняют клиенту, как работать с SSE endpoint.
- **Prompts**: `local_capabilities` (информирует о доступных ресурсах и инструментах).
- **Resources**: `local://ai-challenge/status` отдаёт текстовый статус (endpoint, время запуска, версия сборки).
- **Tools**:
  - `dnd_campaigns` — читает `app/src/main/assets/mcp/my_dnd_campaigns.json` и возвращает JSON `{ "campaigns": [...] }`.
  - `dungeon_masters` — аналогично, но для `dungeons_masters.json`.
  - (Закомментировано) `github_repos` и `github_issue_comments` — пример того, как обратиться к GitHub API с токеном `GITHUB_TOKEN`.
- Данные загружаются из assets через `Context.assets` (нужно вызвать `McpServerManager.setAppContext()` до старта — делает `McpServers.setAppContext()` в `MainActivity`).

## LocalKnowledgeMcpServerManager (knowledge)
Файл: `app/src/main/java/com/example/aichallenge/mcp/LocalKnowledgeMcpServerManager.kt`

- Тот же шаблон (SSE + POST). Порт `11030`.
- Инструменты:
  - `dnd_characters` — JSON с ключом `characters` из `dnd_characters.json`.
  - `dnd_spells_for_class` — фильтрует `dnd_spells.json` по списку классов (`class_names`). Возвращает либо массив заклинаний, либо объект с сообщением об отсутствии совпадений.
- Валидация входных аргументов делается через `jsonArray`/`jsonPrimitive` и выдаёт ошибку с текстом, если список классов пустой.

## MCP клиент
Файл: `app/src/main/java/com/example/aichallenge/mcp/McpClient.kt`

- Использует `io.modelcontextprotocol.kotlin.sdk.client.Client` и `SseClientTransport` для подключения к серверу.
- `fetchTools(serverId)` — запускает сервер при необходимости (`McpServers.ensureRunning()`), создаёт HttpClient(CIO+SSE), подключается и вызывает `listTools()`. Есть простой механизм ретраев (`connectWithRetry`).
- `fetchAllTools()` — итерирует все зарегистрированные серверы.
- `callTool()` — открывает сессию, вызывает `client.callTool()`, собирает `TextContent` в обычную строку.
- Результаты и ошибки логируются через `Log.w(TAG, ...)`.

## UI для инструментов
Файлы: `app/src/main/java/com/example/aichallenge/mcp/ToolsScreen.kt`, `ToolsViewModel.kt`.

- ViewModel хранит `ToolsState` с признаками загрузки, ошибкой и списком `ServerToolsState`.
- `loadTools()` вызывает `McpClient.fetchAllTools()` в IO-dispatcher, затем соединяет данные с метаданными `McpServers.list()`.
- `ToolsScreen` отображает кнопку Reload, список серверов (название, endpoint, описание и каждое средство) и кнопку Back.
- Этот экран позволяет быстро проверить, что оба MCP сервера поднялись и что список инструментов совпадает с ожидаемым.

## Связь с LocalAiServer
`LocalAiServer` обращается к MCP через `McpClient` для двух задач:
1. Вызов инструментов, которые модель запрашивает (через `MCP_TOOL:`).
2. Регулярное получение GitHub issue comments для подготовки summary.

Таким образом MCP слой используется как универсальный источник локальных инструментов, который подходит как человеку (через ToolsScreen), так и LLM (через подсказки).
