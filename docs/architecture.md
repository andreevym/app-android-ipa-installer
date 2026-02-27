# Архитектура IPA Installer

Подробное описание архитектуры приложения, протоколов Apple и всех компонентов.

## Содержание

- [Общая схема](#общая-схема)
- [Слой USB](#слой-usb)
- [Протокол usbmuxd](#протокол-usbmuxd)
- [Протокол lockdownd](#протокол-lockdownd)
- [Протокол AFC](#протокол-afc-apple-file-conduit)
- [Протокол installation_proxy](#протокол-installation_proxy)
- [Криптография и пейринг](#криптография-и-пейринг)
- [Полный pipeline установки IPA](#полный-pipeline-установки-ipa)
- [UI и MVVM](#ui-и-mvvm)
- [Отличия от OTGLocation](#отличия-от-otglocation)

---

## Общая схема

```
┌──────────────────────────────────────────────────┐
│                   Android App                     │
│                                                   │
│  ┌─────────┐  ┌───────────┐  ┌────────────────┐  │
│  │   UI    │──│ ViewModel │──│   Use Cases    │  │
│  │ Compose │  │   MVVM    │  │                │  │
│  └─────────┘  └───────────┘  └───────┬────────┘  │
│                                       │           │
│  ┌────────────────────────────────────┴────────┐  │
│  │           Протоколы Apple (Kotlin)           │  │
│  │                                              │  │
│  │  ┌──────────────┐  ┌─────────────────────┐   │  │
│  │  │ installation │  │        AFC          │   │  │
│  │  │    _proxy    │  │  (файловый доступ)  │   │  │
│  │  └──────┬───────┘  └──────────┬──────────┘   │  │
│  │         │                     │              │  │
│  │  ┌──────┴─────────────────────┴──────────┐   │  │
│  │  │            lockdownd                   │   │  │
│  │  │  (пейринг, сессии, запуск сервисов)    │   │  │
│  │  └──────────────────┬─────────────────────┘   │  │
│  │                     │                         │  │
│  │  ┌──────────────────┴─────────────────────┐   │  │
│  │  │              usbmuxd                    │   │  │
│  │  │  (TCP-мультиплексирование через USB)    │   │  │
│  │  └──────────────────┬─────────────────────┘   │  │
│  └─────────────────────┼─────────────────────────┘  │
│                        │                             │
│  ┌─────────────────────┴─────────────────────────┐  │
│  │         Android USB Host API                   │  │
│  │  UsbManager → UsbDeviceConnection → bulkXfer   │  │
│  └─────────────────────┬─────────────────────────┘  │
└────────────────────────┼─────────────────────────────┘
                         │ USB OTG кабель
                         ▼
                 ┌───────────────┐
                 │  iOS-устройство│
                 │  (iPhone/iPad) │
                 └───────────────┘
```

---

## Слой USB

### Файлы

| Файл | Назначение |
|------|------------|
| `usb/AppleDeviceDetector.kt` | Обнаружение Apple-устройств, USB-разрешения |
| `usb/UsbTransport.kt` | Низкоуровневый bulk read/write через USB |
| `usb/UsbMuxConnection.kt` | Мост между USB-транспортом и usbmuxd |

### Как работает USB Host API без root

Android начиная с версии 3.1 (API 12) поддерживает режим USB Host. Это позволяет Android-устройству выступать в роли хоста, а подключенное устройство (iPhone) — в роли периферии.

```kotlin
// 1. Обнаружение устройства
val usbManager = getSystemService(USB_SERVICE) as UsbManager
val devices = usbManager.deviceList.values
    .filter { it.vendorId == 0x05AC } // Apple

// 2. Запрос разрешения (диалог пользователю)
usbManager.requestPermission(device, pendingIntent)

// 3. Открытие соединения
val connection = usbManager.openDevice(device)
connection.claimInterface(muxInterface, true)

// 4. Передача данных
connection.bulkTransfer(endpointOut, data, data.size, timeout)
connection.bulkTransfer(endpointIn, buffer, buffer.size, timeout)
```

### Обнаружение Apple USB Multiplexor Interface

iPhone при подключении через USB выставляет несколько интерфейсов. Для коммуникации через usbmuxd нужен интерфейс с параметрами:

| Параметр | Значение | Описание |
|----------|----------|----------|
| Subclass | `0xFE` | Apple USB Multiplexor |
| Protocol | `2` | MUX протокол |
| Endpoints | 2 (Bulk IN + Bulk OUT) | Двунаправленная передача |

```kotlin
// Поиск mux-интерфейса
for (i in 0 until device.interfaceCount) {
    val iface = device.getInterface(i)
    if (iface.interfaceSubclass == 0xFE && iface.interfaceProtocol == 2) {
        // Нашли Apple USB Multiplexor
    }
}
```

### Поток событий USB

```
BroadcastReceiver (callbackFlow)
    │
    ├── ACTION_USB_DEVICE_ATTACHED → DeviceEvent.Attached
    ├── ACTION_USB_DEVICE_DETACHED → DeviceEvent.Detached
    └── USB_PERMISSION result → DeviceEvent.PermissionResult
```

---

## Протокол usbmuxd

### Файлы

| Файл | Назначение |
|------|------------|
| `protocol/usbmuxd/MuxHeader.kt` | Заголовок пакета (16 байт) |
| `protocol/usbmuxd/MuxMessage.kt` | Типизированные сообщения |
| `protocol/usbmuxd/MuxProtocol.kt` | Сериализация/десериализация |

### Описание

usbmuxd (USB Multiplexor Daemon) — протокол Apple для мультиплексирования TCP-соединений через USB. Это тот же протокол, который iTunes/Finder используют для коммуникации с iPhone на десктопе.

### Формат пакета

```
┌──────────────────────────────────────────┐
│ Заголовок (16 байт, little-endian)       │
│ ┌──────────┬──────────┬────────┬───────┐ │
│ │ length   │ version  │ type   │ tag   │ │
│ │ uint32   │ uint32   │ uint32 │uint32 │ │
│ └──────────┴──────────┴────────┴───────┘ │
├──────────────────────────────────────────┤
│ Payload (XML plist или бинарный)         │
│ длина = length - 16                      │
└──────────────────────────────────────────┘
```

### Версии протокола

| Версия | Формат payload | Использование |
|--------|---------------|---------------|
| 0 | Бинарный (фиксированные структуры) | Прямое USB-подключение |
| 1 | XML plist | Подключение через Unix-сокет |

### Типы сообщений

| Тип | Код | Направление | Описание |
|-----|-----|-------------|----------|
| Result | 1 | ← ответ | Результат операции (0 = успех) |
| Connect | 2 | → запрос | Подключение к TCP-порту на iOS |
| Listen | 3 | → запрос | Подписка на события attach/detach |
| DeviceAdd | 4 | ← событие | Устройство подключено |
| DeviceRemove | 5 | ← событие | Устройство отключено |
| Plist | 8 | → запрос | Plist-формат (version 1) |

### Пример: Connect к lockdownd

```xml
<!-- Запрос -->
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
    <key>MessageType</key>
    <string>Connect</string>
    <key>DeviceID</key>
    <integer>1</integer>
    <key>PortNumber</key>
    <integer>32498</integer>  <!-- 62078 в network byte order -->
    <key>ProgName</key>
    <string>ipainstaller</string>
</dict>
</plist>
```

### Конвертация порта

usbmuxd ожидает порт в **network byte order** (big-endian), упакованный в 16 бит:

```kotlin
// 62078 = 0xF27E → swap → 0x7EF2 = 32498
val portBE = ((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)
```

---

## Протокол lockdownd

### Файлы

| Файл | Назначение |
|------|------------|
| `protocol/lockdownd/LockdownClient.kt` | Клиент lockdownd |

### Описание

lockdownd — основной сервис управления iOS-устройством. Работает на порту **62078**. Отвечает за:

- Информация об устройстве (имя, версия iOS, UDID)
- Пейринг (обмен сертификатами, установка доверия)
- Запуск TLS-сессий
- Запуск других сервисов (AFC, installation_proxy и т.д.)

### Формат пакета

```
┌───────────────────────────────────┐
│ Длина payload (4 байта, big-endian)│
├───────────────────────────────────┤
│ XML plist payload                  │
└───────────────────────────────────┘
```

### Основные операции

#### QueryType

Проверка соединения с lockdownd.

```xml
<!-- Запрос -->
<dict>
    <key>Label</key><string>ipainstaller</string>
    <key>Request</key><string>QueryType</string>
</dict>

<!-- Ответ -->
<dict>
    <key>Request</key><string>QueryType</string>
    <key>Type</key><string>com.apple.mobile.lockdown</string>
</dict>
```

#### GetValue

Получение информации об устройстве.

```xml
<!-- Запрос конкретного ключа -->
<dict>
    <key>Label</key><string>ipainstaller</string>
    <key>Request</key><string>GetValue</string>
    <key>Key</key><string>ProductVersion</string>
</dict>

<!-- Ответ -->
<dict>
    <key>Key</key><string>ProductVersion</string>
    <key>Request</key><string>GetValue</string>
    <key>Value</key><string>17.2</string>
</dict>
```

#### Pair

Установка доверительных отношений. Требует нажатия «Доверять» на iOS.

```xml
<dict>
    <key>Label</key><string>ipainstaller</string>
    <key>Request</key><string>Pair</string>
    <key>PairRecord</key>
    <dict>
        <key>HostCertificate</key><data>...</data>
        <key>HostPrivateKey</key><data>...</data>
        <key>RootCertificate</key><data>...</data>
        <key>RootPrivateKey</key><data>...</data>
        <key>DeviceCertificate</key><data>...</data>
        <key>SystemBUID</key><string>UUID</string>
        <key>HostID</key><string>UUID</string>
    </dict>
</dict>
```

#### StartSession

Запуск TLS-сессии (после пейринга). Соединение переключается на SSL.

#### StartService

Запуск сервиса на устройстве. Возвращает динамический порт.

```xml
<!-- Запрос -->
<dict>
    <key>Label</key><string>ipainstaller</string>
    <key>Request</key><string>StartService</string>
    <key>Service</key><string>com.apple.afc</string>
</dict>

<!-- Ответ -->
<dict>
    <key>Port</key><integer>49152</integer>
    <key>EnableServiceSSL</key><false/>
    <key>Service</key><string>com.apple.afc</string>
</dict>
```

---

## Протокол AFC (Apple File Conduit)

### Файлы

| Файл | Назначение |
|------|------------|
| `protocol/afc/AfcClient.kt` | Клиент AFC |

### Описание

AFC предоставляет файловый доступ к iOS-устройству. Используется для загрузки IPA-файла перед установкой.

**Имя сервиса:** `com.apple.afc`

### Формат пакета

```
┌────────────────────────────────────────────────┐
│ Заголовок (40 байт, little-endian)              │
│ ┌──────────────────────────────────────────────┐│
│ │ magic: "CFA6LPAA" (8 байт)                  ││
│ │ entireLength: uint64 (заголовок+данные+payload)││
│ │ thisLength:   uint64 (заголовок+данные)       ││
│ │ packetNum:    uint64                         ││
│ │ operation:    uint64                         ││
│ └──────────────────────────────────────────────┘│
├────────────────────────────────────────────────┤
│ Header Data (пути, хэндлы — переменная длина)   │
├────────────────────────────────────────────────┤
│ Payload (содержимое файла — для write)          │
└────────────────────────────────────────────────┘
```

### Операции AFC

| Операция | Код | Описание |
|----------|-----|----------|
| Status | 0x01 | Ответ со статусом |
| ReadDir | 0x03 | Список файлов в директории |
| RemovePath | 0x08 | Удаление файла/директории |
| MakeDir | 0x09 | Создание директории |
| FileOpen | 0x0D | Открытие файла (возвращает хэндл) |
| FileRead | 0x0F | Чтение из файла |
| FileWrite | 0x10 | Запись в файл |
| FileClose | 0x14 | Закрытие файла |

### Загрузка IPA

```
1. makeDirectory("/PublicStaging")
2. handle = fileOpen("/PublicStaging/app.ipa", WRONLY)
3. fileWrite(handle, chunk1)  ─┐
   fileWrite(handle, chunk2)   │ чанками по 64KB
   fileWrite(handle, chunk3)  ─┘
4. fileClose(handle)
```

---

## Протокол installation_proxy

### Файлы

| Файл | Назначение |
|------|------------|
| `protocol/installproxy/InstallationProxyClient.kt` | Клиент installation_proxy |

### Описание

Управление приложениями на iOS: установка, удаление, просмотр списка.

**Имя сервиса:** `com.apple.mobile.installation_proxy`

### Формат пакета

Такой же как у lockdownd: 4 байта длины (big-endian) + XML plist.

### Команда Install

```xml
<dict>
    <key>Command</key><string>Install</string>
    <key>PackagePath</key><string>/PublicStaging/app.ipa</string>
    <key>ClientOptions</key>
    <dict>
        <key>PackageType</key><string>Developer</string>
    </dict>
</dict>
```

### Ответы прогресса

installation_proxy отправляет множественные ответы с прогрессом:

```xml
<!-- Прогресс -->
<dict>
    <key>Status</key><string>CopyingApplication</string>
    <key>PercentComplete</key><integer>30</integer>
</dict>

<!-- Завершение -->
<dict>
    <key>Status</key><string>Complete</string>
</dict>

<!-- Ошибка -->
<dict>
    <key>Error</key><string>ApplicationVerificationFailed</string>
    <key>ErrorDescription</key><string>...</string>
</dict>
```

### Статусы установки

| Статус | Процент | Описание |
|--------|---------|----------|
| CopyingApplication | 0-30% | Копирование приложения |
| InstallingApplication | 30-60% | Установка |
| VerifyingApplication | 60-90% | Верификация подписи |
| InstallingEmbeddedProfile | 90-95% | Установка профиля |
| Complete | 100% | Готово |

---

## Криптография и пейринг

### Файлы

| Файл | Назначение |
|------|------------|
| `crypto/PairingCrypto.kt` | Генерация сертификатов и ключей |
| `model/PairRecord.kt` | Модель данных пейринга |

### Процесс пейринга

```
Android                              iOS
   │                                   │
   │── GetValue(DevicePublicKey) ──────>│
   │<──── DevicePublicKey (PEM) ────────│
   │                                   │
   │   [Генерация сертификатов]         │
   │   - Root CA (самоподписанный)      │
   │   - Host cert (подписан Root CA)   │
   │   - Device cert (из DevicePublicKey)│
   │                                   │
   │── Pair(PairRecord) ───────────────>│
   │                                   │
   │   [Пользователь нажимает          │
   │    "Доверять" на iPhone]           │
   │                                   │
   │<──── Pair(Success, EscrowBag) ─────│
   │                                   │
   │── StartSession(HostID, BUID) ─────>│
   │<──── SessionID, EnableSSL ─────────│
   │                                   │
   │══════ TLS Handshake ══════════════>│
   │<═══════════════════════════════════│
   │                                   │
   │   [Теперь соединение зашифровано]  │
```

### Сертификаты

| Сертификат | Кто создаёт | Назначение |
|------------|-------------|------------|
| Root CA | Android (BouncyCastle) | Корневой центр сертификации |
| Host Certificate | Android (подписан Root CA) | Идентификация хоста |
| Device Certificate | Android (из DevicePublicKey) | Идентификация iOS-устройства |

### PairRecord

Структура, которая хранится локально и используется при повторных подключениях:

```
PairRecord:
  HostID: UUID          — уникальный ID хоста
  SystemBUID: UUID      — системный ID
  HostCertificate: PEM  — сертификат хоста
  HostPrivateKey: PEM   — приватный ключ хоста
  RootCertificate: PEM  — корневой CA
  RootPrivateKey: PEM   — приватный ключ CA
  DeviceCertificate: PEM— сертификат устройства
  EscrowBag: bytes      — токен доверия (из ответа Pair)
```

---

## Полный pipeline установки IPA

```
┌─────────────────────────────────────────────┐
│ 1. USB Detection                             │
│    UsbManager обнаруживает VID 0x05AC        │
│    Запрос USB permission у пользователя       │
├─────────────────────────────────────────────┤
│ 2. USB Transport                             │
│    Открытие mux-интерфейса (0xFE/2)          │
│    claimInterface + bulk endpoints           │
├─────────────────────────────────────────────┤
│ 3. usbmuxd Handshake                        │
│    ListDevices → получение DeviceID          │
├─────────────────────────────────────────────┤
│ 4. lockdownd Connect                         │
│    usbmuxd Connect(deviceId, port=62078)     │
├─────────────────────────────────────────────┤
│ 5. Pairing (первый раз)                      │
│    GetValue(DevicePublicKey) →                │
│    генерация сертификатов →                   │
│    Pair(PairRecord) →                         │
│    "Доверять?" на iPhone →                    │
│    сохранение PairRecord                     │
├─────────────────────────────────────────────┤
│ 6. StartSession + TLS                        │
│    StartSession(HostID, BUID) →               │
│    TLS handshake с сертификатами             │
├─────────────────────────────────────────────┤
│ 7. Start AFC Service                         │
│    StartService("com.apple.afc") → port      │
│    usbmuxd Connect(deviceId, afcPort)        │
├─────────────────────────────────────────────┤
│ 8. Upload IPA                                │
│    makeDirectory("/PublicStaging")            │
│    fileOpen + fileWrite (чанками) + fileClose │
├─────────────────────────────────────────────┤
│ 9. Start installation_proxy                  │
│    StartService("com.apple.mobile.           │
│      installation_proxy") → port             │
│    usbmuxd Connect(deviceId, proxyPort)      │
├─────────────────────────────────────────────┤
│ 10. Install IPA                              │
│     Install(PackagePath) →                    │
│     прогресс (CopyingApplication 30%...) →    │
│     Complete                                 │
├─────────────────────────────────────────────┤
│ 11. Cleanup                                  │
│     removePath("/PublicStaging/app.ipa")      │
│     закрытие соединений                      │
└─────────────────────────────────────────────┘
```

---

## UI и MVVM

### Архитектура

```
┌────────────┐    ┌──────────────┐    ┌───────────────┐
│  Compose   │◄───│  ViewModel   │◄───│  USB/Protocol │
│  MainScreen│    │  StateFlow   │    │  Layers       │
└────────────┘    └──────────────┘    └───────────────┘
     │                   │
     │  collectAsState() │  viewModelScope.launch
     ▼                   ▼
  UI State          ConnectionState
  Rendering         InstallState
                    selectedIpa
```

### Состояния подключения

```
Disconnected ──► UsbConnected ──► Pairing ──► Paired
     ▲                                          │
     └──────── Detached / Error ◄───────────────┘
```

### Состояния установки

```
Idle ──► Uploading(progress) ──► Installing(progress) ──► Success
  ▲                                                          │
  └─────────── resetInstallState() ◄─────────────────────────┘
                                    ◄── Failed(error) ◄──────┘
```

### Навигация

Текущая версия — одноэкранное приложение. Главный экран содержит:

1. **Карточка устройства** — статус подключения, имя устройства, версия iOS
2. **Выбор файла** — кнопка для открытия системного файлового менеджера (SAF)
3. **Кнопка установки** — активна когда устройство спарено и IPA выбран
4. **Прогресс** — полоса загрузки/установки с текстовым статусом

---

## Отличия от OTGLocation

| Аспект | OTGLocation | IPA Installer |
|--------|-------------|---------------|
| Root | Требуется (su) | Не требуется |
| USB-доступ | libusb (нативный) | Android USB Host API |
| Протоколы | Нативные бинарники (C) | Чистый Kotlin |
| usbmuxd | Внешний демон | Встроенная реализация |
| Цель | Подмена GPS | Установка IPA |
| Размер | ~15 MB (бинарники + .so) | ~5 MB (только Kotlin) |
| Совместимость | Только root-устройства | Любой Android 7.0+ с OTG |
