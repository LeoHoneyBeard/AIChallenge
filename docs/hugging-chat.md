# HuggingFaceLocal и экран Hugging Chat

HuggingChat сценарий использует всё ту же клиентскую логику (`ChatViewModel`), но сервер `HuggingFaceLocal` подключается к Router API Hugging Face и позволяет переключать модели на лету.

## Сервер HuggingFaceLocal
Файл: `app/src/main/java/com/example/aichallenge/server/HuggingFaceLocal.kt`

Особенности:
- Наследует `NanoHTTPD` и слушает `127.0.0.1:8080` (порт общий, поэтому серверы запускаются попеременно через `MainActivity`).
- Принимает только `POST /chat` с телом `{ "prompt": "..." }`. Валидация проверяет наличие `HF_TOKEN`.
- Формирует OpenAI-совместимый payload (`model`, `messages`, `stream=false`) и отправляет его в `https://router.huggingface.co/v1/chat/completions`.
- История хранится в двух списках: `history` (сбрасывается при смене модели) и `permanentHistory` (копит все сообщения, чтобы их можно было переиспользовать, если конкретная модель требует полный контекст).
- Использует перечисление `Model` для выбора LLM. У моделей есть флаги `isPermanentHistoryNeeded`, которые переключают используемый список истории. Примеры: `meta-llama/Llama-3.1-8B-Instruct`, `Sao10K/L3-8B-Stheno-v3.2`.
- Ответ дополняется статистикой `total_tokens`, `completion_tokens`, `prompt_tokens` и `request_time_ms`, чтобы UI показывал прозрачность стоимости.

## Переключение моделей
`HuggingServerRegistry` хранит единственный экземпляр сервера и даёт Composable доступ к методам `setModel()/getModel()`.
- При выборе модели список `history` очищается, чтобы не мешать прогреву контекста.
- Если модель помечена как `isPermanentHistoryNeeded`, сервер всегда использует `permanentHistory`, иначе — текущую волатильную историю.

## Экран `HuggingChatScreen`
Файл: `app/src/main/java/com/example/aichallenge/chat/HuggingChatScreen.kt`

UI повторяет `ChatScreen`, но добавляет тулбар с выпадающим меню моделей:
1. `TextButton` показывает текущую модель (`Model.label`).
2. `DropdownMenu` перебирает `Model.values()` и вызывает `HuggingServerRegistry.instance?.setModel(m)` по клику.
3. История сообщений берётся из того же `ChatViewModel`, поэтому пользователь может переключаться между сценарием Yandex и Hugging без потери UI-состояния.

Остальные элементы (копирование сообщения в буфер, автоматическая прокрутка, кнопка отправки, очистка истории) работают так же, как на основном экране.

## Когда использовать HuggingChat
- Для теста альтернативных open-source моделей без изменения основной цепочки `ChatViewModel`.
- Для сценариев, где нужно гибко управлять сохранением истории (через `Model.isPermanentHistoryNeeded`).
- Как шаблон для интеграции других OpenAI-совместимых API — достаточно добавить новую модель в enum и при необходимости подкрутить таймауты.
