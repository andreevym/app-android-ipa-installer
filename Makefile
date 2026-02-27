SHELL := /bin/bash
.DEFAULT_GOAL := help

# ─── Конфигурация ─────────────────────────────────────────────
ANDROID_HOME ?= $(HOME)/Android/Sdk
ADB          := $(ANDROID_HOME)/platform-tools/adb
GRADLE       := ./gradlew
GRADLE_OPTS  ?=
APP_ID       := com.example.ipainstaller
VERSION      := $(shell grep 'versionName' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
APK_DEBUG    := app/build/outputs/apk/debug/app-debug.apk
APK_RELEASE  := app/build/outputs/apk/release/app-release-unsigned.apk

# ─── Цвета ────────────────────────────────────────────────────
GREEN  := \033[0;32m
YELLOW := \033[0;33m
CYAN   := \033[0;36m
RED    := \033[0;31m
RESET  := \033[0m

# ═══════════════════════════════════════════════════════════════
#  СБОРКА APK
# ═══════════════════════════════════════════════════════════════

.PHONY: apk
apk: apk-debug ## Синоним для apk-debug

.PHONY: apk-debug
apk-debug: ## Собрать debug APK
	@echo -e "$(GREEN)▸ Сборка debug APK...$(RESET)"
	$(GRADLE) assembleDebug
	@echo -e "$(GREEN)✓ $(APK_DEBUG)$(RESET)"

.PHONY: apk-release
apk-release: ## Собрать release APK (без подписи)
	@echo -e "$(GREEN)▸ Сборка release APK...$(RESET)"
	$(GRADLE) assembleRelease
	@echo -e "$(GREEN)✓ $(APK_RELEASE)$(RESET)"

.PHONY: apk-sign
apk-sign: apk-release ## Собрать и подписать release APK
	@echo -e "$(GREEN)▸ Подпись APK...$(RESET)"
	@if [ -z "$(KEYSTORE)" ]; then \
		echo -e "$(RED)✗ Укажите KEYSTORE=path/to/keystore.jks$(RESET)"; \
		echo "  make apk-sign KEYSTORE=release.jks KEYSTORE_PASS=*** KEY_ALIAS=key0 KEY_PASS=***"; \
		exit 1; \
	fi
	$(ANDROID_HOME)/build-tools/$$(ls $(ANDROID_HOME)/build-tools/ | tail -1)/apksigner sign \
		--ks $(KEYSTORE) \
		--ks-pass pass:$(KEYSTORE_PASS) \
		--ks-key-alias $(KEY_ALIAS) \
		--key-pass pass:$(KEY_PASS) \
		--out app/build/outputs/apk/release/app-release-signed.apk \
		$(APK_RELEASE)
	@echo -e "$(GREEN)✓ app/build/outputs/apk/release/app-release-signed.apk$(RESET)"

.PHONY: bundle
bundle: ## Собрать AAB (Android App Bundle)
	@echo -e "$(GREEN)▸ Сборка AAB...$(RESET)"
	$(GRADLE) bundleRelease
	@echo -e "$(GREEN)✓ app/build/outputs/bundle/release/app-release.aab$(RESET)"

# ═══════════════════════════════════════════════════════════════
#  ТЕСТОВЫЙ IPA
# ═══════════════════════════════════════════════════════════════

TEST_IPA_DIR := test-ipa
TEST_IPA     := $(TEST_IPA_DIR)/TestApp.ipa

.PHONY: ipa-test
ipa-test: $(TEST_IPA) ## Создать тестовый IPA-файл (dummy для отладки)

$(TEST_IPA):
	@echo -e "$(CYAN)▸ Создание тестового IPA...$(RESET)"
	@mkdir -p $(TEST_IPA_DIR)/Payload/TestApp.app
	@printf '%s\n' \
		'<?xml version="1.0" encoding="UTF-8"?>' \
		'<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">' \
		'<plist version="1.0">' \
		'<dict>' \
		'  <key>CFBundleIdentifier</key>' \
		'  <string>com.example.testapp</string>' \
		'  <key>CFBundleDisplayName</key>' \
		'  <string>Test App</string>' \
		'  <key>CFBundleVersion</key>' \
		'  <string>1</string>' \
		'  <key>CFBundleShortVersionString</key>' \
		'  <string>1.0.0</string>' \
		'  <key>CFBundleExecutable</key>' \
		'  <string>TestApp</string>' \
		'  <key>MinimumOSVersion</key>' \
		'  <string>15.0</string>' \
		'  <key>CFBundlePackageType</key>' \
		'  <string>APPL</string>' \
		'</dict>' \
		'</plist>' > $(TEST_IPA_DIR)/Payload/TestApp.app/Info.plist
	@echo "dummy" > $(TEST_IPA_DIR)/Payload/TestApp.app/TestApp
	@cd $(TEST_IPA_DIR) && zip -qr TestApp.ipa Payload/
	@rm -rf $(TEST_IPA_DIR)/Payload
	@echo -e "$(CYAN)✓ $(TEST_IPA) ($$(du -h $(TEST_IPA) | cut -f1))$(RESET)"

.PHONY: ipa-clean
ipa-clean: ## Удалить тестовый IPA
	rm -rf $(TEST_IPA_DIR)

.PHONY: ipa-push
ipa-push: $(TEST_IPA) ## Загрузить тестовый IPA на Android-устройство
	@echo -e "$(CYAN)▸ Загрузка IPA на устройство...$(RESET)"
	$(ADB) push $(TEST_IPA) /sdcard/Download/TestApp.ipa
	@echo -e "$(CYAN)✓ Загружен в /sdcard/Download/TestApp.ipa$(RESET)"

# ═══════════════════════════════════════════════════════════════
#  УСТАНОВКА И ЗАПУСК
# ═══════════════════════════════════════════════════════════════

.PHONY: install
install: apk-debug ## Собрать и установить debug APK на устройство
	@echo -e "$(GREEN)▸ Установка на устройство...$(RESET)"
	$(ADB) install -r $(APK_DEBUG)
	@echo -e "$(GREEN)✓ Установлено$(RESET)"

.PHONY: run
run: install ## Собрать, установить и запустить
	@echo -e "$(GREEN)▸ Запуск $(APP_ID)...$(RESET)"
	$(ADB) shell am start -n $(APP_ID)/.ui.main.MainActivity
	@echo -e "$(GREEN)✓ Запущено$(RESET)"

.PHONY: uninstall
uninstall: ## Удалить приложение с устройства
	$(ADB) uninstall $(APP_ID)

.PHONY: log
log: ## Показать logcat (фильтр по приложению)
	$(ADB) logcat --pid=$$($(ADB) shell pidof $(APP_ID)) -v color

.PHONY: log-usb
log-usb: ## Показать USB-логи
	$(ADB) logcat -v color -s "UsbTransport:*" "AppleDeviceDetector:*" "UsbMuxConnection:*" "MuxProtocol:*"

# ═══════════════════════════════════════════════════════════════
#  ТЕСТЫ И КАЧЕСТВО
# ═══════════════════════════════════════════════════════════════

.PHONY: test
test: ## Запустить unit-тесты
	@echo -e "$(YELLOW)▸ Unit-тесты...$(RESET)"
	$(GRADLE) test
	@echo -e "$(YELLOW)✓ Тесты пройдены$(RESET)"

.PHONY: test-single
test-single: ## Запустить один тест: make test-single CLASS=MuxProtocolTest
	@if [ -z "$(CLASS)" ]; then \
		echo -e "$(RED)✗ Укажите CLASS: make test-single CLASS=MuxProtocolTest$(RESET)"; \
		exit 1; \
	fi
	$(GRADLE) test --tests "com.example.ipainstaller.$(CLASS)"

.PHONY: test-android
test-android: ## Запустить instrumented-тесты на устройстве
	@echo -e "$(YELLOW)▸ Instrumented-тесты...$(RESET)"
	$(GRADLE) connectedAndroidTest

.PHONY: lint
lint: ## Запустить lint-проверку
	@echo -e "$(YELLOW)▸ Lint...$(RESET)"
	$(GRADLE) lint
	@echo -e "$(YELLOW)✓ Отчёт: app/build/reports/lint-results-debug.html$(RESET)"

.PHONY: check
check: lint test ## Lint + тесты

# ═══════════════════════════════════════════════════════════════
#  УТИЛИТЫ
# ═══════════════════════════════════════════════════════════════

.PHONY: clean
clean: ## Очистить сборку
	$(GRADLE) clean
	rm -rf $(TEST_IPA_DIR)

.PHONY: deps
deps: ## Показать дерево зависимостей
	$(GRADLE) app:dependencies --configuration implementation

.PHONY: devices
devices: ## Показать подключённые устройства (ADB)
	$(ADB) devices -l

.PHONY: version
version: ## Показать версию приложения
	@echo "$(VERSION)"

.PHONY: size
size: ## Показать размер APK
	@if [ -f "$(APK_DEBUG)" ]; then \
		echo -e "Debug:   $$(du -h $(APK_DEBUG) | cut -f1)"; \
	fi
	@if [ -f "$(APK_RELEASE)" ]; then \
		echo -e "Release: $$(du -h $(APK_RELEASE) | cut -f1)"; \
	fi

.PHONY: env
env: ## Проверить окружение для сборки
	@echo -e "$(CYAN)Проверка окружения:$(RESET)"
	@echo -n "  Java:        " && java -version 2>&1 | head -1
	@echo -n "  Gradle:      " && $(GRADLE) --version 2>/dev/null | grep "^Gradle" || echo "не найден"
	@echo    "  ANDROID_HOME: $(ANDROID_HOME)"
	@echo -n "  SDK:         " && ls $(ANDROID_HOME)/platforms/ 2>/dev/null | tail -1 || echo "не найден"
	@echo -n "  Build Tools: " && ls $(ANDROID_HOME)/build-tools/ 2>/dev/null | tail -1 || echo "не найдены"
	@echo -n "  ADB:         " && $(ADB) version 2>/dev/null | head -1 || echo "не найден"
	@echo -n "  Устройства:  " && $(ADB) devices 2>/dev/null | grep -c "device$$" || echo "0"

# ═══════════════════════════════════════════════════════════════
#  HELP
# ═══════════════════════════════════════════════════════════════

.PHONY: help
help: ## Показать эту справку
	@echo ""
	@echo -e "$(CYAN)IPA Installer — команды сборки$(RESET)"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  $(GREEN)%-16s$(RESET) %s\n", $$1, $$2}'
	@echo ""
	@echo -e "$(YELLOW)Примеры:$(RESET)"
	@echo "  make apk               # Собрать debug APK"
	@echo "  make run               # Собрать, установить, запустить"
	@echo "  make test              # Запустить тесты"
	@echo "  make ipa-test          # Создать тестовый IPA для отладки"
	@echo "  make ipa-push          # Загрузить тестовый IPA на устройство"
	@echo "  make apk-sign KEYSTORE=key.jks KEYSTORE_PASS=pass KEY_ALIAS=key0 KEY_PASS=pass"
	@echo ""
