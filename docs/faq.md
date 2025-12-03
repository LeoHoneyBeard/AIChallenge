# FAQ по AIChallenge

## Как собрать и запустить приложение?
Запустите `./gradlew installDebug` из корня или используйте кнопку Run в Android Studio. Первый экран (`StartScreen`) позволяет выбрать сценарий (Yandex Chat, Hugging Chat, MCP Tools). После выбора нужной конфигурации MainActivity автоматически поднимет соответствующий сервер и откроет экран чата.

## Какие ключи и токены нужно добавить?
Создайте/обновите `local.properties`, добавив `YANDEX_API_KEY`, `YC_FOLDER_ID`, `HF_TOKEN` и, при необходимости MCP GitHub-инструментов, `GITHUB_TOKEN`. Без этих переменных `LocalAiServer` вернёт 400, `HuggingFaceLocal` не сможет проксировать Router API, а GitHub-тулы будут отвечать 401.

## Чем отличаются LocalAiServer и HuggingFaceLocal?
`LocalAiServer` — локальный NanoHTTPD/NanoWSD сервер c REST (`/chat`, `/history`, `/clear`) и WebSocketом issue summary; он обращается к Yandex GPT 5.1 и поддерживает MCP_TOOL-инструкции. `HuggingFaceLocal` — тонкий прокси к `https://router.huggingface.co/v1/chat/completions`, использующий выбранную модель из enum `Model` и не хранит постоянную историю по умолчанию.

## Где хранится история переписки и как работает лимит?
История лежит в `filesDir/local_ai_history.json` и в `permanentHistory` внутри `LocalAiServer`. По умолчанию действует лимит `MAX_HISTORY_MESSAGES = 10` — старые записи удаляются перед передачей в LLM. Кнопка Clear в UI вызывает `POST /clear`, что очищает обе структуры и файл.

## Как включить веб-сводки (issue summary) через WebSocket?
Установите `ISSUE_SUMMARY_FEATURE_ENABLED = true` и задайте корректный `GITHUB_TOKEN`. Тогда `ChatViewModel` подключится к `ws://127.0.0.1:8080/issueSummary`, будет получать агрегированные комментарии GitHub и показывать их блоком под диалогом. Без heartbeat (10 секунд) соединение перезапускается автоматически.

## Для чего нужен переключатель MCP Tools на StartScreen?
Он запускает оба встроенных MCP-сервера (`core` на 11020 и `knowledge` на 11030) через `McpServers.startAll()`. После старта ToolsScreen подтянет список доступных инструментов (`dnd_campaigns`, `dnd_spells_for_class`, `github_issue_comments` и т.д.) и позволит тестировать их вызовы прямо из приложения.

## Что делать, если порт 8080 занят или сервер не стартует?
NanoHTTPD использует `127.0.0.1:8080`. Если предыдущий процесс не освободил порт (например, приложение было убито), остановите его через `adb shell am force-stop com.example.aichallenge` или перезапустите эмулятор. Также убедитесь, что не осталось других сервисов на 8080.

## Где смотреть логи и отладочную информацию?
Основные события (`sendMessage`, ответы REST/MCP, подключения WS) логируются через `Logcat` в Android Studio. Для серверной части смотрите вывод `LocalAiServer.kt`/`HuggingFaceLocal.kt`, а также содержимое `local_ai_history.json`, чтобы воспроизвести сценарии с историей.
