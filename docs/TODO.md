# Список доработок IPA Installer

Полный анализ текущего состояния проекта: критические баги, недоработки, недостающие тесты, улучшения.

**Текущая готовность: ~25-30%** — каркас приложения готов, протоколы нефункциональны.

## Содержание

- [Критические проблемы](#-критические-проблемы)
- [Баги](#-баги)
- [Недостающий функционал](#-недостающий-функционал)
- [Тесты](#-тесты)
- [UX-улучшения](#-ux-улучшения)
- [Оптимизации](#-оптимизации)
- [Roadmap](#-roadmap)

---

## КРИТИЧЕСКИЕ ПРОБЛЕМЫ

Эти проблемы делают приложение полностью нерабочим. Без их решения невозможна установка IPA.

### ~~1. Бинарный протокол usbmuxd (version 0)~~ ИСПРАВЛЕНО

**Файлы:** `protocol/usbmuxd/MuxProtocol.kt`, `usb/UsbMuxConnection.kt`

~~**Проблема:** Код реализует только plist-версию протокола (version 1), которая используется через Unix-сокет на десктопе.~~

**Исправлено:** Добавлена поддержка бинарного протокола v0 в `MuxProtocol` (`VERSION_BINARY`, `serializeBinaryConnect`, `parseBinaryResult`). `UsbMuxConnection` получил флаг `useBinaryProtocol` и метод `sendBinaryConnect()` для fallback на v0 при прямом USB-подключении. Plist v1 остаётся по умолчанию.

### ~~2. Отсутствие TLS/SSL~~ ИСПРАВЛЕНО

**Файлы:** `crypto/TlsTransport.kt`, `protocol/lockdownd/LockdownClient.kt`, `usb/UsbTransport.kt`

~~**Проблема:** После `StartSession` lockdownd требует переключения на TLS. Без TLS **невозможно** вызвать `StartService`.~~

**Исправлено:** Создан `TlsTransport` на базе BouncyCastle `bctls-jdk18on` (`TlsClientProtocol` + `DefaultTlsClient`). TLS 1.2 с mutual authentication: клиент предъявляет host certificate+key из PairRecord, сервер принимает любой (self-signed Apple certs). `UsbTransport` получил `asInputStream()`/`asOutputStream()` для TLS bridge. `LockdownClient.upgradeTls()` выполняет handshake и переключает read/write на шифрованный канал. `DeviceConnectionManager` вызывает TLS upgrade после `startSession()` перед `startService()`.

### ~~3. Заглушки в MainViewModel~~ ИСПРАВЛЕНО

**Файл:** `viewmodel/MainViewModel.kt`

~~**Проблема:** `connectToDevice()` и `installIpa()` — TODO-заглушки. Нет рабочего pipeline.~~

**Исправлено:** `connectToDevice()` создаёт `DeviceConnectionManager` с `ConnectionManagerCallback`, который обновляет StateFlows, отправляет уведомления и записывает историю установок. `installIpa()` читает IPA из Uri через `ContentResolver.openInputStream()` и делегирует в `connectionManager.installIpa()`. Mutex защищает `connectionManager` reference.

### ~~4. Хранение PairRecord~~ ИСПРАВЛЕНО

**Файлы:** `storage/PairRecordStorage.kt`, `di/AppModule.kt`, `connection/DeviceConnectionManager.kt`

~~**Проблема:** PairRecord нигде не сохраняется. При каждом запуске приложения нужен повторный пейринг.~~

**Исправлено:** Создан `PairRecordStorage` — файловое хранилище в `context.filesDir/pair_records/{udid}.json` с JSON+Base64 сериализацией. Методы: `save()`, `load()`, `delete()`, `exists()`. Провайдер добавлен в `AppModule`. `DeviceConnectionManager.phaseDiscoverAndPair()` проверяет `pairRecordStorage.load(udid)` перед пейрингом — при повторном подключении "Trust" dialog не появляется.

---

## БАГИ

### ~~B1. UsbTransport.write() не дозаписывает данные~~ ИСПРАВЛЕНО

**Файл:** `usb/UsbTransport.kt`

~~`bulkTransfer` может вернуть меньше байт, чем `data.size`, но код не дозаписывает остаток.~~

**Исправлено:** `write()` теперь использует цикл с offset, дозаписывая остаток до полной отправки.

### ~~B2. UsbTransport.readExact() зацикливается при ZLP~~ ИСПРАВЛЕНО

**Файл:** `usb/UsbTransport.kt`

~~Если `bulkTransfer` вернёт 0 (zero-length packet), `offset` не увеличится и цикл станет бесконечным.~~

**Исправлено:** добавлен счётчик ZLP (`MAX_ZLP_RETRIES = 10`) с выбросом `IOException` при превышении.

### ~~B3. Бесконечные циклы в AfcClient.readResponse()~~ ИСПРАВЛЕНО

**Файл:** `protocol/afc/AfcClient.kt`

~~При некорректных значениях от устройства (`entireLength < thisLength`) результат вычитания будет отрицательным.~~

**Исправлено:** добавлена валидация `thisLength >= HEADER_SIZE` и `entireLength >= thisLength` перед вычислением размеров.

### ~~B4. AfcClient.uploadFile() грузит весь файл в RAM~~ ИСПРАВЛЕНО

**Файл:** `protocol/afc/AfcClient.kt`

~~Для IPA размером 2+ ГБ вызовет `OutOfMemoryError`.~~

**Исправлено:** добавлен основной `uploadFile(remotePath, inputStream, totalSize, ...)` со стримингом. Старая `ByteArray`-версия делегирует в новую.

### ~~B5. PairingCrypto — отрицательные серийные номера сертификатов~~ ИСПРАВЛЕНО

**Файл:** `crypto/PairingCrypto.kt`

~~`SecureRandom().nextLong()` может вернуть отрицательное значение. X.509 требует положительного серийного номера.~~

**Исправлено:** заменено на `BigInteger(128, SecureRandom())` в обоих методах генерации сертификатов.

### ~~B6. PairingCrypto — нет X.509 расширений~~ ИСПРАВЛЕНО

**Файл:** `crypto/PairingCrypto.kt`

~~Корневой CA не имеет `basicConstraints: CA=true`. iOS может отклонить такой сертификат.~~

**Исправлено:** добавлены расширения `BasicConstraints(CA=true)` и `KeyUsage(keyCertSign | cRLSign)` в корневой CA.

### ~~B7. InstallationProxyClient — жёсткий PackageType~~ ИСПРАВЛЕНО

**Файл:** `protocol/installproxy/InstallationProxyClient.kt`

~~`PackageType = "Developer"` работает только для dev-signed IPA. Для ad-hoc/enterprise IPA это неверно.~~

**Исправлено:** `packageType` стал параметром метода `install()` с дефолтным значением `"Developer"`.

### ~~B8. Конвертация порта без валидации~~ ИСПРАВЛЕНО

**Файл:** `protocol/usbmuxd/MuxProtocol.kt`

~~Порт не проверяется на диапазон 0..65535.~~

**Исправлено:** добавлен `require(message.port in 0..65535)` перед конвертацией.

### ~~B9. AppleDeviceDetector — слишком широкий фильтр~~ ИСПРАВЛЕНО

**Файлы:** `usb/AppleDeviceDetector.kt`

~~Vendor ID `0x05AC` ловит все устройства Apple: клавиатуры, мыши, AirPods, CarPlay-аксессуары.~~

**Исправлено:** добавлена проверка `hasMuxInterface()` (subclass 0xFE, protocol 2) в `findConnectedDevices()` и `deviceEvents()`.

### ~~B10. MainActivity не обрабатывает USB Intent~~ ИСПРАВЛЕНО

**Файл:** `ui/main/MainActivity.kt`

~~При запуске через `USB_DEVICE_ATTACHED` Intent содержит `UsbDevice`, но код его не извлекает.~~

**Исправлено:** `onCreate` проверяет `ACTION_USB_DEVICE_ATTACHED` и вызывает `viewModel.onUsbDeviceAttached(device)`.

### ~~B11. MainViewModel — гонки данных~~ ИСПРАВЛЕНО

**Файл:** `viewmodel/MainViewModel.kt`

~~`currentDevice` и `muxConnection` — обычные `var`, не `@Volatile` и без мутекса.~~

**Исправлено:** все обращения к `currentDevice` и `muxConnection` защищены `Mutex.withLock`.

### ~~B12. MainScreen — неверное имя файла из Uri~~ ИСПРАВЛЕНО

**Файл:** `ui/main/MainScreen.kt`

~~`selectedIpa?.lastPathSegment` для DocumentProvider возвращает cryptic ID вместо имени файла.~~

**Исправлено:** используется `ContentResolver.query()` с `OpenableColumns.DISPLAY_NAME` через `remember(selectedIpa)`.

### ~~B13. MainScreen — захардкоженные строки~~ ИСПРАВЛЕНО

**Файл:** `ui/main/MainScreen.kt`

~~Строки `"USB connected, requesting permission…"`, `"OK"`, `"Dismiss"` не в `strings.xml`.~~

**Исправлено:** все строки вынесены в `strings.xml` и используют `stringResource()`.

### ~~B14. UsbTransport — нет setConfiguration()~~ ИСПРАВЛЕНО

**Файл:** `usb/UsbTransport.kt`

~~Не вызывается `setConfiguration()` перед `claimInterface()`.~~

**Исправлено:** `open()` перебирает все USB-конфигурации через `findMuxInConfigurations()`, отправляет `SET_CONFIGURATION` control transfer, затем `claimInterface()`. Fallback на default interfaces сохранён.

### ~~B15. LockdownClient — нет проверки ошибок в ответах~~ ИСПРАВЛЕНО

**Файл:** `protocol/lockdownd/LockdownClient.kt`

~~`getValue()` и `queryType()` не проверяют ключ `Error` в ответе lockdownd. Только `startService()` его проверяет.~~

**Исправлено:** проверка `Error` перенесена в общий метод `request()`, дублирование в `startService()` убрано.

---

## НЕДОСТАЮЩИЙ ФУНКЦИОНАЛ

### F1. Бинарный usbmuxd для прямого USB
- [ ] Заголовок v0 (бинарный формат)
- [ ] TCP-мультиплексирование (открытие нескольких виртуальных соединений)
- [ ] Обработка асинхронных уведомлений (DeviceAttached/Detached)

### F2. TLS-обёртка для lockdownd
- [ ] `SSLEngine`-обёртка поверх `UsbTransport`
- [ ] Кастомный `TrustManager` для самоподписанных сертификатов
- [ ] Переключение lockdownd-сессии на TLS

### F3. Полный пейринг
- [ ] Извлечение `DevicePublicKey` из lockdownd
- [ ] Генерация Device Certificate из публичного ключа
- [ ] Обработка `UserDeniedPairing`
- [ ] Обработка `PasswordProtected` устройств
- [ ] Извлечение `EscrowBag` из ответа Pair
- [ ] Сохранение PairRecord на диск

### F4. Стриминговая загрузка файлов через AFC
- [ ] `uploadFile(inputStream, totalSize)` вместо `uploadFile(ByteArray)`
- [ ] Отмена загрузки (CancellationToken)
- [ ] Настраиваемый размер чанка

### F5. Отмена операций
- [ ] Отмена загрузки IPA
- [ ] Отмена установки
- [ ] Корректная очистка ресурсов при отмене

### F6. Повторное подключение
- [ ] Автоматическая повторная попытка при ошибке USB
- [ ] Восстановление сессии без повторного пейринга
- [ ] USB reset при неотвечающем устройстве

### F7. Информация об устройстве
- [ ] Серийный номер, модель, доступное место
- [ ] Список установленных приложений (Browse)
- [ ] Удаление приложений (Uninstall)

### F8. Логирование
- [ ] Логирование USB-пакетов для отладки
- [ ] Логирование plist-сообщений
- [ ] Экспорт логов для диагностики

### F9. Поддержка iOS 17+
- [ ] Новый протокол lockdownd (RemoteXPC)
- [ ] Персонализированные DeveloperDiskImage (если понадобятся)
- [ ] Проверка совместимости с iOS 18

### F10. Множественные устройства
- [ ] Выбор устройства из списка, если подключено несколько
- [ ] Параллельная установка на несколько устройств

---

## ТЕСТЫ

Тестов **нет вообще**. Каталоги `test/` и `androidTest/` пустые.

### Unit-тесты (обязательные)

| Приоритет | Тест | Описание |
|-----------|------|----------|
| P0 | `MuxProtocolTest` | Сериализация/десериализация заголовков usbmuxd |
| P0 | `MuxProtocolPortTest` | Конвертация порта в big-endian (0, 1, 255, 256, 62078, 65535) |
| P0 | `MuxProtocolPayloadTest` | Сериализация plist-сообщений (Connect, Listen, ListDevices) |
| P0 | `MuxProtocolParseTest` | Парсинг ответов (Result, Attached, Detached) |
| P1 | `PairingCryptoTest` | Генерация сертификатов, валидность, подпись |
| P1 | `PairingCryptoCertChainTest` | Root CA подписывает Host cert |
| P1 | `PairingCryptoSerialTest` | Серийные номера положительные |
| P1 | `AfcPacketTest` | Формат AFC-заголовков (magic, длины, операции) |
| P1 | `AfcClientTest` | Последовательность операций (open → write → close) |
| P2 | `LockdownClientTest` | Формат запросов/ответов lockdownd |
| P2 | `InstallProxyClientTest` | Парсинг progress-обновлений |
| P2 | `PlistUtilTest` | Парсинг XML и бинарных plist |
| P2 | `PairRecordTest` | Сериализация/десериализация PairRecord |

### Instrumented-тесты (Android)

| Приоритет | Тест | Описание |
|-----------|------|----------|
| P1 | `AppleDeviceDetectorTest` | Обнаружение USB-устройств (mock UsbManager) |
| P1 | `UsbTransportTest` | Открытие/закрытие mux-интерфейса |
| P2 | `MainViewModelTest` | State transitions, обработка событий |
| P3 | `MainScreenTest` | Compose snapshot-тесты для разных состояний |

### Mocking-стратегия

Для тестирования протоколов без реального устройства:

```kotlin
// Создать FakeTransport, имитирующий ответы iPhone
class FakeTransport : UsbTransport {
    private val responseQueue: Queue<ByteArray> = LinkedList()
    fun enqueueResponse(data: ByteArray) { responseQueue.add(data) }
    override suspend fun read(maxLength: Int) = responseQueue.poll()
    override suspend fun write(data: ByteArray) = data.size
}
```

---

## UX-УЛУЧШЕНИЯ

### ~~U1. MIME-фильтр для IPA~~ РЕАЛИЗОВАНО

~~Текущий `application/octet-stream` показывает все файлы. Использовать `"*/*"` с проверкой расширения `.ipa` после выбора.~~

**Реализовано:** Файловый пикер использует `*/*`, после выбора валидируется расширение `.ipa`. При неверном расширении показывается Snackbar.

### ~~U2. Отображение имени файла~~ ИСПРАВЛЕНО (B12)

~~Использовать `ContentResolver.query()` + `OpenableColumns.DISPLAY_NAME` вместо `Uri.lastPathSegment`.~~

### ~~U3. Прокрутка экрана~~ РЕАЛИЗОВАНО

~~Обернуть содержимое `MainScreen` в `LazyColumn` или `verticalScroll` для маленьких экранов.~~

**Реализовано:** `MainScreen` использует `LazyColumn` для прокрутки контента и истории установок.

### ~~U4. Кнопка переподключения~~ РЕАЛИЗОВАНО

~~Добавить кнопку «Переподключить» в карточке устройства при ошибке.~~

**Реализовано:** Кнопка «Reconnect» с иконкой Refresh в `DeviceStatusCard` при `ConnectionState.Error`. ViewModel метод `reconnect()`.

### ~~U5. Информация об IPA~~ РЕАЛИЗОВАНО

~~После выбора файла показать: имя, размер, bundle ID (парсить Info.plist из ZIP).~~

**Реализовано:** `IpaInfo` модель с `displayName`, `sizeBytes`, `bundleId`, `bundleVersion`. Парсинг Info.plist из ZIP через `dd-plist`. Карточка `IpaInfoCard` отображает данные.

### ~~U6. Compose Preview~~ РЕАЛИЗОВАНО

~~Добавить `@Preview` функции для всех Composable-компонентов для удобства разработки.~~

**Реализовано:** 6 `@Preview` функций: Disconnected, Paired+IPA, Error, Uploading, Install Success, Install Failed.

### ~~U7. Локализация~~ РЕАЛИЗОВАНО

- [x] Добавить `values-ru/strings.xml`
- [x] Перенести захардкоженные строки в ресурсы

**Реализовано:** Полная русская локализация всех строк включая новые (U1, U4, U5, U9, U10).

### ~~U8. Тема~~ РЕАЛИЗОВАНО

~~Заменить `android:Theme.Material.Light.NoActionBar` на `Theme.Material3.Light.NoActionBar` в `themes.xml`.~~

**Реализовано:** XML-тема обновлена на Material3. Добавлена зависимость `com.google.android.material`.

### ~~U9. Уведомления~~ РЕАЛИЗОВАНО

~~Показывать уведомление при завершении установки (если приложение в фоне).~~

**Реализовано:** `InstallNotificationHelper` с каналом `install_status`, `POST_NOTIFICATIONS` permission в манифесте. Уведомления при Success/Failed.

### ~~U10. История установок~~ РЕАЛИЗОВАНО

~~Хранить историю успешных/неудачных установок (Room DB).~~

**Реализовано:** Room DB с `InstallRecord` entity, `InstallHistoryDao`, `AppDatabase`. Секция истории в `MainScreen` с иконками Success/Failed. Автоочистка старых записей.

---

## ОПТИМИЗАЦИИ

### O1. Пул буферов для USB-чтения

`UsbTransport.read()` выделяет новый `ByteArray(65536)` при каждом вызове. Использовать `ByteArrayPool`.

### O2. Батчинг GetValue в lockdownd

`getDeviceInfo()` делает 5 последовательных запросов. Можно получить все значения одним запросом `GetValue` без ключа.

### O3. Настраиваемый таймаут USB

`TIMEOUT_MS = 5000` слишком мал для больших файлов через USB 2.0. Сделать таймаут настраиваемым.

### O4. ProGuard-оптимизация

Правило `-keep class org.bouncycastle.** { *; }` сохраняет **все** классы BouncyCastle. Сузить до используемых пакетов.

---

## ROADMAP

### Фаза 1: Минимально рабочий прототип

Цель: установка dev-signed IPA на iOS-устройство через USB.

| # | Задача | Зависимости | Сложность |
|---|--------|-------------|-----------|
| 1.1 | Реализовать бинарный протокол usbmuxd v0 | — | Высокая |
| 1.2 | Реализовать TCP-мультиплексирование | 1.1 | Высокая |
| 1.3 | Реализовать TLS через BouncyCastle SSLEngine | — | Высокая |
| 1.4 | Реализовать полный пейринг (извлечение DevicePublicKey, генерация сертификатов) | 1.2 | Средняя |
| 1.5 | Реализовать StartSession + TLS-переключение | 1.3, 1.4 | Средняя |
| 1.6 | Связать pipeline: connect → pair → session → AFC → install | 1.1–1.5 | Средняя |
| 1.7 | Стриминговая загрузка IPA через AFC | 1.6 | Средняя |
| 1.8 | Хранение PairRecord | — | Низкая |
| 1.9 | Исправить все баги B1–B15 | — | Средняя |

### Фаза 2: Стабилизация

| # | Задача | Сложность |
|---|--------|-----------|
| 2.1 | Unit-тесты для протоколов (P0, P1) | Средняя |
| 2.2 | Обработка ошибок и retry-логика | Средняя |
| 2.3 | Отмена операций (upload, install) | Средняя |
| 2.4 | Логирование для отладки | Низкая |
| ~~2.5~~ | ~~UX-исправления (U1–U10)~~ | ~~Низкая~~ ✅ |

### Фаза 3: Расширенный функционал

| # | Задача | Сложность |
|---|--------|-----------|
| 3.1 | Поддержка iOS 17+ (RemoteXPC) | Высокая |
| 3.2 | Список установленных приложений | Средняя |
| 3.3 | Удаление приложений | Низкая |
| 3.4 | Множественные устройства | Средняя |
| 3.5 | Информация об IPA (парсинг Info.plist) | Низкая |
| 3.6 | История установок | Низкая |
| 3.7 | Уведомления | Низкая |
| 3.8 | Instrumented-тесты | Средняя |

### Оценка общей трудоёмкости

| Фаза | Ориентировочный объём |
|-------|----------------------|
| Фаза 1 | Основная работа — бинарный usbmuxd + TLS |
| Фаза 2 | Тесты и стабилизация |
| Фаза 3 | Расширения по мере необходимости |
