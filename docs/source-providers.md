# Source Providers

## Интерфейс

`AppSourceProvider` (domain/repository) — контракт любого источника каталога:

```kotlin
interface AppSourceProvider {
    val providerId: String
    val displayName: String
    suspend fun isEnabled(): Boolean
    suspend fun search(query: String): List<RemoteApp>
    suspend fun getAppDetails(packageName: String): RemoteAppDetails?
    suspend fun getVersions(packageName: String): List<AppVersion>
    suspend fun getLatestVersion(packageName: String): AppVersion?
    suspend fun getDownloadInfo(version: AppVersion): DownloadInfo
    suspend fun refresh()
}
```

Провайдер сообщает только: что за приложение, какие версии существуют, где артефакт,
какая metadata доступна. Он НЕ знает про `UpdateEngine` и `PackageInstaller`.

## Реализованные провайдеры

### FdroidSourceProvider (data/source)

Реальный клиент публичного индекса F-Droid **index-v1.json** — структурированный
формат, который использует и официальный клиент F-Droid (HTML не скрейпится):

- `https://f-droid.org/repo/index-v1.json` (базовый URL настраивается);
- поля: packageName, name, summary, description, license, categories, author,
  icon (относительный путь), added/lastUpdated, versions: versionCode, versionName,
  apkName, size, **sha256**, minSdk/targetSdk, sig (если индекс его отдаёт);
- иконки: `<repo>/icons-640/<pkg>_<versionCode>.png`;
- ETag/If-None-Match кэширование индекса (не качаем заново без изменений);
- `refresh()` — обновление индекса, cancellable, не блокирует UI.

Поля, которых нет в индексе, не выдумываются (`null`).

### GenericRepositorySourceProvider

Любой F-Droid-совместимый репозиторий: пользовательский `baseUrl` + `index-v1.json`
(формат self-hosted fdroidserver-репозиториев). Позволяет добавить собственный/корпоративный
источник без изменения кода.

### SourceRegistry (data/source)

Агрегирует провайдеры: enabled/trust-статусы из Room (`RepositoryEntity` + DataStore),
единый поиск/детали по включённым источникам.

## Trust (§12)

```
TRUSTED   — источник включён и помечен доверенным (F-Droid по умолчанию)
UNKNOWN   — пользовательский репозиторий до подтверждения
DISABLED  — выключен пользователем
INVALID   — индекс не читается / репозиторий недоступен
```

Из UNKNOWN установка не выполняется без явного решения пользователя. В UI источник
всегда показывается: «Source: F-Droid» / «Source: Custom Repository».

## Как добавить новый провайдер

1. Создать `class XxxSourceProvider @Inject constructor(...) : AppSourceProvider`
   в `data/source/`.
2. Использовать собственные DTO (kotlinx.serialization) и клиент (OkHttp) для
   вашего формата метаданных.
3. Зарегистрировать в `SourceRegistry` (DI-модуль `DataModule`).
4. Trust по умолчанию — `UNKNOWN`, включение — только через пользовательский UI.

Никакие изменения в `UpdateEngine` не требуются — он работает с абстракцией.

## Разрешение мульти-источников

Один package в нескольких источниках → `SourceResolutionPolicy` (core:updater):
совместимость подписи → trust → versionCode → приоритет пользователя. Выбранный
источник показывается пользователю. Разная подпись у источника B → автообновление
заблокировано (§74).

## Ограничения

- Google Play API не существует публично → провайдера нет, и fake-провайдер не
  создаётся (§141).
- index-v2 пока не используется: index-v1 покрывает те же данные компактнее
  (см. README «Ограничения»).
- LocalRepositorySourceProvider (локальная папка с индексом) — точка расширения.
