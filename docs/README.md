# IPA Installer для Android

Android-приложение для установки IPA-файлов (iOS-приложений) на iPhone/iPad через USB OTG кабель **без root-доступа** на Android.

## Что это такое

IPA Installer позволяет устанавливать приложения на iOS-устройства напрямую с Android-телефона. Достаточно подключить iPhone к Android через OTG-кабель, выбрать IPA-файл и нажать «Установить».

В отличие от аналогичных решений (например, [OTGLocation](https://github.com/cczhr/OTGLocation)), это приложение **не требует root-доступа** на Android. Вместо запуска нативных бинарников `usbmuxd`/`libimobiledevice` через `su`, все протоколы Apple реализованы на чистом Kotlin с использованием Android USB Host API.

## Требования

- Android 7.0+ (API 24) с поддержкой USB Host (OTG)
- USB OTG кабель (USB-C/micro-USB на Lightning/USB-C)
- iOS-устройство (iPhone, iPad, iPod Touch)
- IPA-файл с валидной подписью (developer, ad-hoc или enterprise)

## Быстрый старт

### Сборка

```bash
# Debug-сборка
./gradlew assembleDebug

# Release-сборка
./gradlew assembleRelease

# Запуск тестов
./gradlew test

# Lint-проверка
./gradlew lint
```

### Использование

1. Установите APK на Android-устройство
2. Подключите iPhone через USB OTG кабель
3. Подтвердите разрешение на доступ к USB-устройству
4. На iPhone нажмите «Доверять этому компьютеру»
5. Выберите IPA-файл
6. Нажмите «Установить»

## Как это работает

Приложение реализует стек протоколов Apple для коммуникации через USB:

```
Android App (Kotlin)
    │
    ├── Android USB Host API (без root)
    │
    ├── usbmuxd — мультиплексирование TCP через USB
    │
    ├── lockdownd — пейринг, информация об устройстве, запуск сервисов
    │
    ├── AFC — загрузка файлов на iOS
    │
    └── installation_proxy — установка IPA
```

## Документация

| Документ | Описание |
|----------|----------|
| [Архитектура](architecture.md) | Подробное описание архитектуры, протоколов и всех компонентов |
| [Список доработок](TODO.md) | Баги, недоработки, улучшения и план развития |

## Технологии

- **Язык:** Kotlin 2.1
- **UI:** Jetpack Compose + Material 3
- **DI:** Hilt
- **Крипто:** BouncyCastle (TLS-пейринг с iOS)
- **Plist:** dd-plist (формат данных Apple)
- **Архитектура:** MVVM + Coroutines/Flow
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 35 (Android 15)

## Структура проекта

```
app/src/main/java/com/example/ipainstaller/
├── usb/           # USB Host API, обнаружение устройств, транспорт
├── protocol/      # Реализации протоколов Apple
│   ├── usbmuxd/   # Мультиплексирование TCP через USB
│   ├── lockdownd/  # Управление устройством, пейринг
│   ├── afc/        # Передача файлов (Apple File Conduit)
│   └── installproxy/ # Установка/удаление приложений
├── crypto/        # Генерация сертификатов для пейринга
├── plist/         # Утилиты для работы с plist
├── model/         # Модели данных
├── viewmodel/     # MVVM ViewModels
├── di/            # Hilt-модули
└── ui/            # Jetpack Compose экраны
```

## Лицензия

GPL-3.0 (на основе OTGLocation)

## Благодарности

- [OTGLocation](https://github.com/cczhr/OTGLocation) — референсный проект по USB-коммуникации Android↔iOS
- [libimobiledevice](https://github.com/libimobiledevice/libimobiledevice) — эталонная реализация протоколов Apple
- [pymobiledevice3](https://github.com/doronz88/pymobiledevice3) — Python-реализация (отличная справка по протоколам)
