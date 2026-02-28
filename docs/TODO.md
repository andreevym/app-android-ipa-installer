# IPA Installer: Сценарный анализ и задачи

**Текущая готовность: ~80%** — Pipeline функционален (USB -> пейринг -> TLS -> AFC -> установка), 13 unit-тестов, полный UI с Material 3, Room DB история, уведомления, локализация (EN/RU).

**Что работает:** обнаружение устройства, получение USB-разрешения, пейринг с сохранением PairRecord, TLS-сессия, загрузка IPA через AFC (streaming), установка через installation_proxy, отображение прогресса, Reconnect.

**Что не работает:** обработка ошибок и edge cases, таймауты на blocking-операции, отмена mid-operation, валидация ответов протоколов, тестовое покрытие ViewModel и интеграционных сценариев.

---

## Легенда приоритетов

| Приоритет | Описание | Критерий |
|-----------|----------|----------|
| **P0 (Блокер)** | App hang, crash, OOM, бесконечный цикл | Прямое влияние на главный поток; приложение становится неработоспособным |
| **P1 (Критичный)** | Нет восстановления после ошибок, пользователь застревает | Pipeline не завершается, нет обратной связи, требуется kill app |
| **P2 (Важный)** | Ненадёжность, плохой UX, тихие ошибки | Работает, но неудобно/ненадёжно; пользователь путается |
| **P3 (Улучшение)** | Новые фичи, оптимизации, расширенные тесты | Не влияет на основной поток; nice-to-have |

---

## Сводная таблица задач (по приоритету)

### P0 — Блокеры

| ID | Сценарий | Задача | Файлы |
|----|----------|--------|-------|
| T-04 | S2: Пользователь не нажимает «Trust» | Таймаут на pair request (60с) + UI-индикатор ожидания + кнопка отмены | `DeviceConnectionManager.kt`, `MainScreen.kt` |
| T-09 | S3: TLS handshake зависает | Таймаут на `performHandshake()` (10с) с корректным abort | `TlsTransport.kt`, `DeviceConnectionManager.kt` |
| T-14 | S5: `installIpa()` вызывает `readBytes()` на большом файле | OOM: заменить на streaming через `ContentResolver.openInputStream()` | `MainViewModel.kt` |
| T-18 | S6: install proxy `Status=""` -> бесконечный цикл | Обработать пустой/отсутствующий Status, добавить timeout на цикл чтения | `InstallationProxyClient.kt` |
| T-19 | S6: Устройство перестаёт отвечать mid-install | Таймаут на чтение progress updates (30с без ответа -> error) | `InstallationProxyClient.kt`, `DeviceConnectionManager.kt` |

### P1 — Критичные

| ID | Сценарий | Задача | Файлы |
|----|----------|--------|-------|
| T-01 | S1: Пользователь отклоняет USB permission | Показать понятное сообщение + кнопку «Запросить снова» | `MainViewModel.kt`, `MainScreen.kt` |
| T-05 | S2: Пользователь нажимает «Don't Trust» | Обработать `UserDeniedPairing` в ответе lockdownd, показать инструкции | `DeviceConnectionManager.kt`, `LockdownClient.kt` |
| T-06 | S2: Сохранённый PairRecord отвергнут устройством | Удалить старый record, запросить re-pair автоматически | `DeviceConnectionManager.kt`, `PairRecordStorage.kt` |
| T-10 | S3: TLS upgrade fail — pipeline продолжает без TLS | Если `upgradeTls()` выбросила исключение — abort pipeline, не продолжать | `DeviceConnectionManager.kt` |
| T-15 | S5: AFC upload прерван (USB disconnect) | Graceful abort + очистка частично загруженного файла на устройстве | `AfcClient.kt`, `DeviceConnectionManager.kt` |
| T-16 | S5: AFC EOF раньше totalSize — тихий «успех» | Проверять `bytesWritten == totalSize`, иначе ошибка | `AfcClient.kt` |
| T-20 | S6: IPA не подписан для этого устройства | Парсить error message от install proxy, показать понятное описание | `DeviceConnectionManager.kt`, `MainViewModel.kt` |
| T-22 | S7: Очистка /PublicStaging после установки | AFC `removeFile()` после завершения (success или fail) | `DeviceConnectionManager.kt`, `AfcClient.kt` |
| T-25 | S8: Reconnect не работает (старый UsbTransport закрыт) | Полный reset state при reconnect: новый transport, новая сессия | `MainViewModel.kt`, `DeviceConnectionManager.kt` |
| T-26 | S8: Пользователь застрял в Pairing state без Cancel | Добавить Cancel кнопку во все blocking states (Pairing, Connecting, Uploading) | `MainScreen.kt`, `MainViewModel.kt` |
| T-32 | S10: Нет тестов ViewModel | Unit-тесты MainViewModel (state transitions, error handling) | Новый `MainViewModelTest.kt` |

### P2 — Важные

| ID | Сценарий | Задача | Файлы |
|----|----------|--------|-------|
| T-02 | S1: USB кабель отключён mid-operation | Ловить USB disconnect, abort pipeline, показать ошибку | `UsbTransport.kt`, `DeviceConnectionManager.kt` |
| T-03 | S1: Устройство — не iPhone (Apple keyboard) | Добавить unit-тест на фильтрацию (уже исправлено в B9) | Новый `AppleDeviceDetectorTest.kt` |
| T-07 | S2: Устройство с паролем (PasswordProtected) | Показать «Разблокируйте устройство и попробуйте снова» | `DeviceConnectionManager.kt` |
| T-08 | S2: PairRecordStorage проглатывает ошибки | Логировать ошибки десериализации, не молча возвращать null | `PairRecordStorage.kt` |
| T-11 | S3: StartService возвращает невалидный порт | Валидация port из ответа StartService перед connect | `DeviceConnectionManager.kt` |
| T-12 | S4: IPA повреждён / не содержит Info.plist | Показать ошибку вместо crash | `MainViewModel.kt` |
| T-17 | S5: Отмена загрузки пользователем | CancellationToken для `uploadFile()`, кнопка Cancel в UI | `AfcClient.kt`, `MainScreen.kt`, `MainViewModel.kt` |
| T-21 | S6: Отмена установки пользователем | Кнопка Cancel + отправка abort в install proxy | `MainScreen.kt`, `MainViewModel.kt` |
| T-23 | S7: Уведомление отправлено до завершения установки | Проверить, что notification идёт только после финального Complete | `InstallNotificationHelper.kt`, `MainViewModel.kt` |
| T-24 | S7: POST_NOTIFICATIONS не запрошен runtime (Android 13+) | Запросить permission при первом запуске или перед установкой | `MainActivity.kt` |
| T-27 | S8: Binary v0 fallback никогда не активируется | Автоматический fallback: если plist v1 Connect fail -> retry с v0 | `UsbMuxConnection.kt`, `DeviceConnectionManager.kt` |
| T-33 | S10: Нет интеграционных тестов | Integration-тест DeviceConnectionManager с mock transport | Новый `DeviceConnectionManagerTest.kt` |
| T-34 | S10: Нет тестов PairRecordStorage | Round-trip тест: save -> load -> verify | Новый `PairRecordStorageTest.kt` |
| T-35 | S10: Нет тестов TlsTransport | Unit-тест с mock handshake | Новый `TlsTransportTest.kt` |
| T-36 | S10: Accessibility | `contentDescription` для всех иконок и интерактивных элементов | `MainScreen.kt` |

### P3 — Улучшения

| ID | Сценарий | Задача | Файлы |
|----|----------|--------|-------|
| T-13 | S4: Показать signing info | Отобразить тип подписи (dev/adhoc/enterprise) в IpaInfoCard | `IpaInfo.kt`, `MainScreen.kt` |
| T-28 | S9: iOS 17+ устройство | Поддержка RemoteXPC протокола | Новые файлы |
| T-29 | S9: Несколько устройств | UI выбора устройства, список подключённых | `MainScreen.kt`, `MainViewModel.kt` |
| T-30 | S9: Список/удаление приложений | Browse + Uninstall через installation_proxy | `InstallationProxyClient.kt`, новый UI |
| T-31 | S9: Экспорт логов | Кнопка Share logs -> файл/Intent | `MainViewModel.kt`, `MainScreen.kt` |
| T-37 | S10: Compose UI тесты | Snapshot-тесты для разных состояний экрана | Новые instrumented-тесты |
| T-38 | S10: Оптимизации O1-O4 | Буферы, batching GetValue, настраиваемые таймауты, ProGuard | Различные файлы |

---

## Сценарные группы

### S1: USB-подключение

Обнаружение Apple-устройства через Android USB Host API, получение разрешения, открытие mux-интерфейса.

**Positive flow:**
1. Android обнаруживает USB-устройство с VID `0x05AC`
2. Фильтрация: `hasMuxInterface()` (subclass 0xFE, protocol 2) отсеивает клавиатуры/мыши
3. Пользователь даёт USB permission
4. `UsbTransport.open()` — `SET_CONFIGURATION` + `claimInterface()` на mux-интерфейсе

**Negative flows:**

| ID | Сценарий | Текущее поведение | Ожидаемое поведение |
|----|----------|-------------------|---------------------|
| T-01 | Пользователь отклоняет permission | Тихая ошибка, UI не обновляется | Сообщение + кнопка «Запросить снова» |
| T-02 | Кабель отключён mid-operation | Crash/hang на следующем `bulkTransfer` | Graceful abort pipeline + ошибка в UI |
| T-03 | Устройство не iPhone (уже исправлено B9) | — | Unit-тест для подтверждения |

---

### S2: Пейринг

Первичный пейринг с генерацией сертификатов, повторное подключение с сохранённым PairRecord.

**Positive flow:**
1. Проверяем `PairRecordStorage.load(udid)` — если есть, пропускаем пейринг
2. Если нет — `lockdownd.pair(pairRecord)` -> iOS показывает «Trust This Computer?»
3. Пользователь нажимает «Trust» -> lockdownd возвращает Success
4. `PairRecordStorage.save(udid, pairRecord)`

**Negative flows:**

| ID | Сценарий | Текущее поведение | Ожидаемое поведение |
|----|----------|-------------------|---------------------|
| T-04 | Пользователь не нажимает «Trust» | **P0:** Бесконечное ожидание, app hang | Таймаут 60с + UI «Нажмите Trust на устройстве» + Cancel |
| T-05 | Пользователь нажимает «Don't Trust» | Исключение без объяснения | «Нажмите «Доверять» на iOS-устройстве» + Retry |
| T-06 | Старый PairRecord отвергнут | Ошибка, pipeline стоит | Удалить record, автоматический re-pair |
| T-07 | Устройство с паролем | Непонятная ошибка | «Разблокируйте устройство и попробуйте снова» |
| T-08 | Ошибка десериализации PairRecord | Молча `null`, повторный пейринг | Логировать причину, затем re-pair |

---

### S3: TLS-сессия

StartSession с lockdownd, TLS handshake, переключение на шифрованный канал, StartService.

**Positive flow:**
1. `lockdownd.startSession(hostId, pairRecord.hostId)` -> SessionID + EnableSessionSSL
2. `lockdownd.upgradeTls(pairRecord)` -> BouncyCastle TLS 1.2 mutual auth
3. `lockdownd.startService("com.apple.afc")` -> порт для AFC
4. `lockdownd.startService("com.apple.mobile.installation_proxy")` -> порт для install proxy

**Negative flows:**

| ID | Сценарий | Текущее поведение | Ожидаемое поведение |
|----|----------|-------------------|---------------------|
| T-09 | TLS handshake зависает | **P0:** App hang | Таймаут 10с + abort |
| T-10 | `upgradeTls()` выбрасывает исключение | Pipeline может продолжить без TLS | Abort pipeline, показать ошибку |
| T-11 | StartService возвращает невалидный порт | Попытка connect на port 0 | Валидация перед connect |

---

### S4: Выбор IPA

Файловый пикер через SAF, валидация расширения, парсинг Info.plist из ZIP.

**Positive flow:**
1. Пользователь выбирает файл через SAF (`*/*` фильтр)
2. Проверка расширения `.ipa`
3. Парсинг `Info.plist` из ZIP -> `IpaInfo` (bundleId, version, displayName, size)
4. Отображение в `IpaInfoCard`

**Negative flows:**

| ID | Сценарий | Текущее поведение | Ожидаемое поведение |
|----|----------|-------------------|---------------------|
| T-12 | IPA повреждён / нет Info.plist | Crash при парсинге | Ошибка «Файл повреждён или не является IPA» |
| T-13 | Показать signing info (P3) | Не реализовано | Тип подписи (dev/adhoc/enterprise) в UI |

---

### S5: Загрузка через AFC

Стриминг IPA-файла на устройство через Apple File Conduit в `/PublicStaging/`.

**Positive flow:**
1. AFC connect на полученный порт
2. `afcClient.makeDirectory("/PublicStaging")`
3. `afcClient.uploadFile("/PublicStaging/app.ipa", inputStream, totalSize)` — чанками
4. Progress callback обновляет UI

**Negative flows:**

| ID | Сценарий | Текущее поведение | Ожидаемое поведение |
|----|----------|-------------------|---------------------|
| T-14 | `readBytes()` на большом файле | **P0:** OOM | Streaming через `ContentResolver.openInputStream()` |
| T-15 | USB disconnect mid-upload | Hang/crash | Graceful abort + очистка на устройстве |
| T-16 | EOF раньше totalSize | Тихий «успех» | `bytesWritten == totalSize` check |
| T-17 | Пользователь хочет отменить | Нет кнопки Cancel | CancellationToken + Cancel UI |

---

### S6: Установка IPA

Команда Install через installation_proxy, отслеживание прогресса, обработка ошибок подписи.

**Positive flow:**
1. Connect к installation_proxy на полученном порту
2. `installProxy.install("/PublicStaging/app.ipa")` с `PackageType`
3. Чтение progress updates: `PercentComplete`, `Status`
4. Финальный `Status: "Complete"` -> успех

**Negative flows:**

| ID | Сценарий | Текущее поведение | Ожидаемое поведение |
|----|----------|-------------------|---------------------|
| T-18 | `Status=""` в ответе | **P0:** Бесконечный цикл | Обработка + timeout |
| T-19 | Устройство не отвечает | **P0:** Hang | Таймаут 30с без ответа -> error |
| T-20 | IPA не подписан для устройства | Непонятная ошибка | ErrorMapper -> человеко-читаемое сообщение |
| T-21 | Пользователь хочет отменить | Нет кнопки Cancel | Cancel + abort в proxy |

---

### S7: Пост-установка

Очистка временных файлов, уведомления, запись в историю.

**Positive flow:**
1. Удаление IPA из `/PublicStaging/` через AFC
2. Notification «Установка завершена» (если app в фоне)
3. Запись `InstallRecord` в Room DB
4. Обновление UI -> Success state

**Negative flows:**

| ID | Сценарий | Текущее поведение | Ожидаемое поведение |
|----|----------|-------------------|---------------------|
| T-22 | Нет очистки /PublicStaging | Файл остаётся на устройстве | AFC `removeFile()` в finally-блоке |
| T-23 | Notification до завершения | Возможна гонка | Notification только после финального Complete |
| T-24 | POST_NOTIFICATIONS не запрошен (API 33+) | Нет runtime permission request | Запрос при запуске или перед установкой |

---

### S8: Переподключение и отмена

Reconnect после ошибок, отмена blocking-операций пользователем.

**Positive flow:**
1. Ошибка в pipeline -> кнопка «Reconnect»
2. `MainViewModel.reconnect()` -> создаёт новый `DeviceConnectionManager`
3. Новый pipeline с использованием сохранённого PairRecord

**Negative flows:**

| ID | Сценарий | Текущее поведение | Ожидаемое поведение |
|----|----------|-------------------|---------------------|
| T-25 | Reconnect со старым transport | Возможен crash | Полный reset: новый transport + сессия |
| T-26 | Нет Cancel в blocking states | Пользователь застревает | Cancel для Pairing, Connecting, Uploading |
| T-27 | Binary v0 fallback не работает | Только plist v1 | Автоматический retry с v0 при fail |

---

### S9: Edge Cases

| ID | Сценарий | Задача | Приоритет |
|----|----------|--------|-----------|
| T-28 | iOS 17+ устройство | Поддержка RemoteXPC протокола | P3 |
| T-29 | Несколько устройств одновременно | UI выбора устройства из списка | P3 |
| T-30 | Управление приложениями | Browse + Uninstall через installation_proxy | P3 |
| T-31 | Экспорт логов для диагностики | Кнопка Share logs -> файл/Intent | P3 |

---

### S10: Качество кода

| ID | Задача | Приоритет | Файлы |
|----|--------|-----------|-------|
| T-32 | ViewModel unit-тесты (state transitions, error handling) | P1 | Новый `MainViewModelTest.kt` |
| T-33 | Integration-тест DeviceConnectionManager с mock transport | P2 | Новый `DeviceConnectionManagerTest.kt` |
| T-34 | PairRecordStorage round-trip тест (save -> load -> verify) | P2 | Новый `PairRecordStorageTest.kt` |
| T-35 | TlsTransport unit-тест (mock handshake) | P2 | Новый `TlsTransportTest.kt` |
| T-36 | Accessibility: `contentDescription` для всех иконок | P2 | `MainScreen.kt` |
| T-37 | Compose UI snapshot-тесты | P3 | Новые instrumented-тесты |
| T-38 | Оптимизации: буферы (O1), batching GetValue (O2), таймауты USB (O3), ProGuard (O4) | P3 | Различные файлы |

---

## ErrorMapper: Маппинг ошибок (T-20)

Создать `ErrorMapper.kt` — маппинг протокольных ошибок в user-facing сообщения:

| Протокольная ошибка | Сообщение (RU) | Сообщение (EN) |
|---------------------|----------------|----------------|
| `UserDeniedPairing` | Нажмите «Доверять» на iOS-устройстве | Tap "Trust" on the iOS device |
| `PasswordProtected` | Разблокируйте устройство и попробуйте снова | Unlock the device and try again |
| `InvalidHostID` | Пейринг устарел. Переподключите устройство | Pairing expired. Reconnect the device |
| AFC error codes | Ошибка передачи файла: {описание} | File transfer error: {description} |
| Install proxy errors | Установка не удалась: {описание} | Installation failed: {description} |
| TLS handshake timeout | Ошибка шифрования. Переподключите устройство | Encryption error. Reconnect the device |
| USB disconnect | Устройство отключено | Device disconnected |

---

## Roadmap

### Фаза 1: Минимально рабочий прототип — ЗАВЕРШЕНА

Все 9 подзадач выполнены: бинарный протокол v0, TLS, пейринг, хранение PairRecord, pipeline, стриминг AFC, исправление багов B1-B15.

### Фаза 2: Стабилизация (текущая)

| # | Задача | Задачи TODO | Сложность | Статус |
|---|--------|-------------|-----------|--------|
| 2.1 | Unit-тесты протоколов | — | Средняя | Done (13 тестов) |
| 2.2 | Таймауты на blocking-операции | T-04, T-09, T-18, T-19 | Средняя | TODO |
| 2.3 | Обработка ошибок протоколов | T-05, T-06, T-07, T-10, T-11, T-16, T-20 | Средняя | TODO |
| 2.4 | Отмена операций mid-progress | T-17, T-21, T-26 | Средняя | TODO |
| 2.5 | OOM fix (streaming IPA) | T-14 | Низкая | TODO |
| 2.6 | Очистка /PublicStaging | T-22 | Низкая | TODO |
| 2.7 | ErrorMapper | T-20 | Низкая | TODO |
| 2.8 | USB disconnect handling | T-02, T-15 | Средняя | TODO |
| 2.9 | UX: permission denied, reconnect, cancel | T-01, T-25, T-24 | Средняя | TODO |
| 2.10 | ViewModel + integration тесты | T-32, T-33, T-34, T-35 | Средняя | TODO |
| 2.11 | Логирование и UX-исправления | — | Низкая | Done |

### Фаза 3: Расширенный функционал

| # | Задача | Задачи TODO | Сложность |
|---|--------|-------------|-----------|
| 3.1 | Поддержка iOS 17+ (RemoteXPC) | T-28 | Высокая |
| 3.2 | Множественные устройства | T-29 | Средняя |
| 3.3 | Список/удаление приложений | T-30 | Средняя |
| 3.4 | Signing info в UI | T-13 | Низкая |
| 3.5 | Экспорт логов | T-31 | Низкая |
| 3.6 | Compose UI тесты | T-37 | Средняя |
| 3.7 | Оптимизации (O1-O4) | T-38 | Низкая |

### Оценка прогресса

| Фаза | Статус |
|-------|--------|
| Фаза 1 | Done — pipeline функционален |
| Фаза 2 | ~40% — тесты протоколов и UX готовы; нужны: таймауты, обработка ошибок, отмена, streaming fix, тесты ViewModel |
| Фаза 3 | ~30% — IPA info, история, уведомления готовы; нужны: iOS 17+, множественные устройства, управление приложениями |

---

<details>
<summary><strong>Исправленные проблемы (Фаза 1)</strong></summary>

### Критические проблемы (все исправлены)

1. **Бинарный протокол usbmuxd v0** — `MuxProtocol` поддерживает v0 (`serializeBinaryConnect`, `parseBinaryResult`), `UsbMuxConnection` имеет флаг `useBinaryProtocol`
2. **TLS/SSL** — `TlsTransport` на BouncyCastle `bctls-jdk18on`, TLS 1.2 mutual auth, `LockdownClient.upgradeTls()`
3. **Заглушки MainViewModel** — полный pipeline через `DeviceConnectionManager` с `ConnectionManagerCallback`
4. **Хранение PairRecord** — `PairRecordStorage` в `filesDir/pair_records/{udid}.json` (JSON+Base64)

### Баги B1-B15 (все исправлены)

- **B1:** `UsbTransport.write()` — цикл с offset для дозаписи
- **B2:** `readExact()` — ZLP counter (MAX_ZLP_RETRIES=10)
- **B3:** `AfcClient.readResponse()` — валидация `thisLength >= HEADER_SIZE`
- **B4:** `AfcClient.uploadFile()` — streaming вместо `ByteArray`
- **B5:** `PairingCrypto` — `BigInteger(128, SecureRandom())` для серийных номеров
- **B6:** `PairingCrypto` — `BasicConstraints(CA=true)` + `KeyUsage` в корневом CA
- **B7:** `InstallationProxyClient` — `packageType` как параметр метода
- **B8:** `MuxProtocol` — `require(port in 0..65535)`
- **B9:** `AppleDeviceDetector` — `hasMuxInterface()` фильтрация
- **B10:** `MainActivity` — обработка `USB_DEVICE_ATTACHED` Intent
- **B11:** `MainViewModel` — `Mutex` для `currentDevice` и `muxConnection`
- **B12:** `MainScreen` — `ContentResolver.query()` для имени файла
- **B13:** `MainScreen` — все строки в `strings.xml`
- **B14:** `UsbTransport` — `SET_CONFIGURATION` + `claimInterface()`
- **B15:** `LockdownClient` — проверка `Error` в общем методе `request()`

### UX-улучшения U1-U10 (все реализованы)

- MIME-фильтр IPA, отображение имени файла, прокрутка экрана, кнопка Reconnect
- IPA info (bundleId, version, size), Compose Previews (6 шт)
- Локализация EN/RU, Material 3 тема, уведомления, история установок (Room DB)

### Функционал F1-F8 (частично реализован)

- **F1:** Бинарный usbmuxd v0 — готов, нет TCP-мультиплексирования
- **F2:** TLS — полностью готов
- **F3:** Пейринг — готов, нет обработки UserDeniedPairing/PasswordProtected
- **F4:** AFC стриминг — готов, нет отмены загрузки
- **F5:** Отмена — частично (connectJob), нет cancel mid-transfer/install
- **F6:** Reconnect — частично, нет auto-retry
- **F7:** Информация — частично, нет доступного места и списка приложений
- **F8:** Логирование — частично, нет экспорта и raw USB dump

</details>

---

## Существующие тесты (13 шт)

| Тест | Описание | Приоритет |
|------|----------|-----------|
| `MuxProtocolTest` | Сериализация/десериализация заголовков usbmuxd | P0 |
| `MuxProtocolPortTest` | Конвертация порта в big-endian | P0 |
| `MuxProtocolPayloadTest` | Сериализация plist-сообщений | P0 |
| `MuxProtocolParseTest` | Парсинг ответов (Result, Attached, Detached) | P0 |
| `PairingCryptoTest` | Генерация сертификатов, валидность | P1 |
| `PairingCryptoCertChainTest` | Root CA подписывает Host cert | P1 |
| `PairingCryptoSerialTest` | Серийные номера положительные | P1 |
| `AfcPacketTest` | Формат AFC-заголовков | P1 |
| `AfcClientTest` | Последовательность операций open -> write -> close | P1 |
| `LockdownClientTest` | Формат запросов/ответов lockdownd | P2 |
| `InstallProxyClientTest` | Парсинг progress-обновлений | P2 |
| `PlistUtilTest` | Парсинг XML и бинарных plist | P2 |
| `PairRecordTest` | Сериализация/десериализация PairRecord | P2 |
