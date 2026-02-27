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

### 1. Бинарный протокол usbmuxd (version 0)

**Файлы:** `protocol/usbmuxd/MuxProtocol.kt`, `usb/UsbMuxConnection.kt`

**Проблема:** Код реализует только plist-версию протокола (version 1), которая используется через Unix-сокет на десктопе. При прямом USB-подключении Android → iPhone нужна **бинарная версия (version 0)** с фиксированными структурами.

**Что нужно:**
- [ ] Реализовать бинарный формат заголовков usbmuxd v0
- [ ] Реализовать бинарную сериализацию Connect/Listen/Result
- [ ] Реализовать TCP-мультиплексирование поверх USB-пакетов
- [ ] Определить, нужен ли plist-формат вообще, или достаточно бинарного

**Референс:** `libimobiledevice/libusbmuxd` — файл `src/libusbmuxd.c`, функции `usbmuxd_send()`, `usbmuxd_recv()`

### 2. Отсутствие TLS/SSL

**Файлы:** `protocol/lockdownd/LockdownClient.kt`

**Проблема:** После `StartSession` lockdownd требует переключения на TLS. Без TLS **невозможно** вызвать `StartService` — lockdownd откажет с ошибкой. Весь pipeline после пейринга нерабочий.

**Что нужно:**
- [ ] Реализовать TLS handshake через BouncyCastle JSSE
- [ ] Создать кастомный `SSLContext` с TrustManager, принимающим самоподписанные сертификаты устройства
- [ ] Обернуть `UsbTransport` в `SSLSocket`-подобный интерфейс (или использовать `SSLEngine`)
- [ ] Реализовать переключение lockdownd-соединения на TLS после StartSession

### 3. Заглушки в MainViewModel

**Файл:** `viewmodel/MainViewModel.kt`

**Проблема:** `connectToDevice()` и `installIpa()` — TODO-заглушки. Нет рабочего pipeline.

**Что нужно:**
- [ ] Реализовать полный pipeline подключения: usbmuxd handshake → lockdownd connect → pairing → TLS session
- [ ] Реализовать полный pipeline установки: start AFC → upload IPA → start installation_proxy → install → cleanup
- [ ] Получать `InputStream` из `ContentResolver` для чтения IPA-файла из `Uri`
- [ ] Добавить обработку ошибок на каждом этапе

### 4. Хранение PairRecord

**Файлы:** `model/PairRecord.kt`, `crypto/PairingCrypto.kt`

**Проблема:** PairRecord нигде не сохраняется. При каждом запуске приложения нужен повторный пейринг — пользователь должен каждый раз нажимать «Доверять» на iPhone.

**Что нужно:**
- [ ] Создать `PairRecordStorage` (EncryptedSharedPreferences или файловое хранилище)
- [ ] Сериализация PairRecord в/из plist
- [ ] Поиск PairRecord по UDID устройства
- [ ] Удаление устаревших PairRecord

---

## БАГИ

### B1. UsbTransport.write() не дозаписывает данные

**Файл:** `usb/UsbTransport.kt:72`

`bulkTransfer` может вернуть меньше байт, чем `data.size`, но код не дозаписывает остаток. Нужен цикл аналогично `readExact`.

```kotlin
// Текущий код (баг):
val sent = connection.bulkTransfer(endpointOut, data, data.size, TIMEOUT_MS)

// Нужно:
suspend fun writeExact(data: ByteArray) { ... } // цикл дозаписи
```

### B2. UsbTransport.readExact() зацикливается при ZLP

**Файл:** `usb/UsbTransport.kt:88`

Если `bulkTransfer` вернёт 0 (zero-length packet), `offset` не увеличится и цикл станет бесконечным.

**Исправление:** добавить счётчик попыток и выброс исключения после N неудачных чтений.

### B3. Бесконечные циклы в AfcClient.readResponse()

**Файл:** `protocol/afc/AfcClient.kt:76-82`

При некорректных значениях от устройства (`entireLength < thisLength`) результат вычитания будет отрицательным, `readFn(-N)` вызовет непредсказуемое поведение.

**Исправление:** валидация `entireLength >= thisLength >= HEADER_SIZE`.

### B4. AfcClient.uploadFile() грузит весь файл в RAM

**Файл:** `protocol/afc/AfcClient.kt:114`

Для IPA размером 2+ ГБ вызовет `OutOfMemoryError`. Нужна стриминговая загрузка через `InputStream`.

**Исправление:** изменить сигнатуру на `uploadFile(remotePath: String, inputStream: InputStream, totalSize: Long, ...)`.

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

### B9. AppleDeviceDetector — слишком широкий фильтр

**Файлы:** `usb/AppleDeviceDetector.kt`, `res/xml/usb_device_filter.xml`

Vendor ID `0x05AC` ловит все устройства Apple: клавиатуры, мыши, AirPods, CarPlay-аксессуары.

**Исправление:** дополнительная фильтрация по USB class/subclass или проверка наличия mux-интерфейса (subclass 0xFE).

### B10. MainActivity не обрабатывает USB Intent

**Файл:** `ui/main/MainActivity.kt`

При запуске через `USB_DEVICE_ATTACHED` Intent содержит `UsbDevice`, но код его не извлекает.

**Исправление:** в `onCreate` проверять `intent.action == ACTION_USB_DEVICE_ATTACHED` и извлекать устройство.

### B11. MainViewModel — гонки данных

**Файл:** `viewmodel/MainViewModel.kt`

`currentDevice` и `muxConnection` — обычные `var`, не `@Volatile` и без мутекса. При одновременных корутинах возможна гонка.

**Исправление:** использовать `Mutex` или `AtomicReference`.

### B12. MainScreen — неверное имя файла из Uri

**Файл:** `ui/main/MainScreen.kt:65`

`selectedIpa?.lastPathSegment` для DocumentProvider возвращает cryptic ID вместо имени файла.

**Исправление:** использовать `ContentResolver.query()` с `OpenableColumns.DISPLAY_NAME`.

### ~~B13. MainScreen — захардкоженные строки~~ ИСПРАВЛЕНО

**Файл:** `ui/main/MainScreen.kt`

~~Строки `"USB connected, requesting permission…"`, `"OK"`, `"Dismiss"` не в `strings.xml`.~~

**Исправлено:** все строки вынесены в `strings.xml` и используют `stringResource()`.

### B14. UsbTransport — нет setConfiguration()

**Файл:** `usb/UsbTransport.kt`

Не вызывается `setConfiguration()` перед `claimInterface()`. Для iPhone нужно выбрать правильную USB-конфигурацию.

**Исправление:** перебрать конфигурации устройства, выбрать ту, что содержит mux-интерфейс.

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

### U1. MIME-фильтр для IPA

Текущий `application/octet-stream` показывает все файлы. Использовать `"*/*"` с проверкой расширения `.ipa` после выбора.

### U2. Отображение имени файла

Использовать `ContentResolver.query()` + `OpenableColumns.DISPLAY_NAME` вместо `Uri.lastPathSegment`.

### U3. Прокрутка экрана

Обернуть содержимое `MainScreen` в `LazyColumn` или `verticalScroll` для маленьких экранов.

### U4. Кнопка переподключения

Добавить кнопку «Переподключить» в карточке устройства при ошибке.

### U5. Информация об IPA

После выбора файла показать: имя, размер, bundle ID (парсить Info.plist из ZIP).

### U6. Compose Preview

Добавить `@Preview` функции для всех Composable-компонентов для удобства разработки.

### U7. Локализация

- [ ] Добавить `values-ru/strings.xml`
- [ ] Перенести захардкоженные строки в ресурсы

### U8. Тема

Заменить `android:Theme.Material.Light.NoActionBar` на `Theme.Material3.Light.NoActionBar` в `themes.xml`.

### U9. Уведомления

Показывать уведомление при завершении установки (если приложение в фоне).

### U10. История установок

Хранить историю успешных/неудачных установок (Room DB).

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
| 2.5 | UX-исправления (U1–U8) | Низкая |

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
