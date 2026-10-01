# Build

## Требования

| Инструмент | Версия |
|---|---|
| JDK | 17+ (проверено на Temurin 17.0.20) |
| Android SDK | platform 35 (rev 2), build-tools 35.0.0, platform-tools |
| Gradle | wrapper 8.10.2 (скачается автоматически) |

## Быстрый старт

```bash
# 1. Укажи путь к Android SDK (или открой проект в Android Studio — создаст сам)
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. Сборка debug APK
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# 3. Unit-тесты
./gradlew test

# 4. Lint
./gradlew :app:lintDebug
```

Linux/macOS: `./gradlew`; Windows: `gradlew.bat`. Права на исполнение: `chmod +x gradlew`.

## APK

- Debug: `app/build/outputs/apk/debug/app-debug.apk` → копируется в
  `releases/NovaStore-debug.apk` (см. README).
- Release: `./gradlew assembleRelease` — **требует подписи**. Ключ НЕ хранится в Git:

```properties
# local.properties (не коммитится)
storeFile=/absolute/path/to/keystore.jks
storePassword=...
keyAlias=novastore
keyPassword=...
```

Release build не содержит debug-логирования, fake-данных, тестовых репозиториев,
секретов (§87–88).

## Модули

22 модуля — см. `settings.gradle.kts`. Сборка одного модуля:

```bash
./gradlew :core:downloader:assembleDebug
./gradlew :feature:updates:test
```

## Скрипты

- `scripts/build-debug.sh` — assembleDebug + копирование APK в releases/
- `scripts/run-tests.sh` — все unit-тесты
- `scripts/verify-apk.sh` — проверка существования/размера/package ID APK

## Варианты и memory

`gradle.properties` настроен консервативно (4 ГБ CI-машина):
`-Xmx1536m`, `workers.max=1`, `parallel=false`. На мощной машине можно поднять
(`org.gradle.jvmargs=-Xmx4g`, `org.gradle.parallel=true`) — сборка ускорится.

## Troubleshooting

- `SDK location not found` — создай `local.properties` с `sdk.dir=...`
- `Failed to find platform android-35` — `sdkmanager "platforms;android-35"`
- Kotlin daemon OOM на слабой машине — оставь `kotlin.daemon.jvmargs=-Xmx1024m`
- Первый запуск долго качает зависимости (~1 ГБ) — это нормально.
