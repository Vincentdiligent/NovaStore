# Nova Store — технический журнал / Changelog

> Подробное техническое описание и история изменений (ранее — README).


**Android App Update Engine + Store Aggregator + Optional Root Installation Backend**

Nova Store — платформа управления приложениями Android: обнаружение обновлений,
загрузка APK с криптографической проверкой, установка через несколько бэкендов
(стандартный `PackageInstaller`, опционально Root, Device Owner), агрегация каталогов
из нескольких источников (F-Droid и совместимые репозитории).

```
Discover → Search → Resolve → Compare → Download → Verify → Install → Verify installation → Record
```

## APK

**APK: `releases/NovaStore-v7.2.0.apk`** (release; сборка: `gradlew.bat assembleRelease` / `./gradlew assembleRelease`).

См. [docs/build.md](docs/build.md) для сборки release-варианта.

## Обзор

| Параметр | Значение |
|---|---|
| Application ID | `com.novastore.app` |
| Минимальная версия | Android 8.0 (API 26) |
| Target SDK | 35 (Android 15) |
| Язык | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material 3 (динамическая тема, тёмный/светлый режим) |
| DI | Hilt 2.52 (KSP) |
| БД | Room 2.6.1 |
| Настройки | DataStore (Preferences) |
| Фоновые задачи | WorkManager |
| Сеть | OkHttp 4.12 + kotlinx.serialization |
| Архитектура | Clean Architecture + MVVM + Repository + Strategy + Provider |

## Возможности

- **Каталог и поиск** по включённым источникам (F-Droid, пользовательские репозитории
  F-Droid-совместимого формата index-v1) с debounce и отменой устаревших запросов.
- **Установленные приложения**: сканирование через `PackageManager` (кэш в Room,
  поиск/сортировка/фильтр), метаданные источника установки, где API позволяет.
- **Обнаружение обновлений**: сравнение по `versionCode` (основной источник истины),
  `versionName` — только отображение. Пайплайн `UpdateEngine` с явной машиной состояний
  (DISCOVERED → … → CONFIRMED / FAILED / CANCELLED).
- **Загрузки**: очередь с ограничением параллелизма, пауза/резюме (HTTP Range + ETag),
  отмена, повтор с экспоненциальным backoff, прогресс/скорость/ETA, стриминг на диск
  (APK не хранится в RAM), восстановление очереди после перезапуска/ребута.
- **Верификация перед установкой (всегда)**: существование файла → размер → SHA-256 →
  разбор пакета (packageName, versionCode, minSdk) → совместимость ABI → сравнение
  подписи с установленной версией. `ChecksumMismatch`/`SignatureMismatch` блокируют
  установку.
- **Установка**: `InstallationStrategyResolver` с режимами AUTOMATIC / STANDARD / ROOT /
  MANAGED. Standard — официальный `PackageInstaller` с системным подтверждением
  (`USER_ACTION_REQUIRED` показывается пользователю). Root — опциональный бэкенд
  (`pm install-create/-add/-commit`), только структурированные аргументы, полная
  пост-проверка через PackageManager. Root **не** отключает верификацию.
- **Update All**: Scan → Resolve → Compatibility → Queue → Download → Verify → Install →
  Verify → Next; одиночная ошибка не теряет очередь, в конце — сводка.
- **Автообновления** (по умолчанию OFF): Wi-Fi only, только на зарядке, заряд батареи,
  расписание (немедленно/ежедневно/еженедельно) через WorkManager.
- **Anonymous Mode** — полнофункциональный режим без аккаунта. Внешние аккаунты
  (Google OAuth) не реализованы и не подделываются (см. «Ограничения»).
- **История обновлений и загрузок**, очистка, кэш метаданных/иконок с экспирацией.
- **Уведомления**: каналы Updates / Downloads / Installation / Errors с троттлингом
  и запросом POST_NOTIFICATIONS только при необходимости.

## Архитектура

22 модуля Gradle (см. [docs/architecture.md](docs/architecture.md)):

```
app                    # Hilt-агрегация, MainActivity, навигация, воркеры, уведомления
core/model             # доменные модели (чистый Kotlin)
core/common            # DispatcherProvider, AppResult, инфраструктура
core/network           # OkHttp, NetworkStatusMonitor, клиент F-Droid index-v1
core/database          # Room: сущности, DAO, миграции
core/datastore         # DataStore (Preferences): настройки
core/security          # HashVerifier, PackageVerifier, SignatureVerifier, ArtifactVerifier
core/downloader        # DownloadEngine, очередь, DownloadWorker
core/installer         # стратегии установки + Root-бэкенд + резолвер
core/updater           # UpdateEngine, машина состояний, VersionComparator, совместимость
core/ui                # Material 3 тема, общие UI-компоненты
domain                 # интерфейсы репозиториев + use cases
data                   # реализации репозиториев, Source Providers, маперы
feature/{home,search,details,installed,updates,downloads,settings,account}
```

Направление зависимостей: `feature → domain → core ← data`, `app` агрегирует всё.
`UpdateEngine` не знает деталей источников; `SourceProvider` не знает деталей установки.

## Источники (Source Providers)

- **F-Droid** (`https://f-droid.org/repo/index-v1.json`) — реальный структурированный
  индекс: пакеты, версии, versionCode, size, SHA-256, иконки, категории, описания.
- **Generic Repository** — любой F-Droid-совместимый репозиторий (index-v1.json на
  пользовательском baseUrl). Добавление новых источников = новый `AppSourceProvider`
  без изменения `UpdateEngine`.
- Trust-статусы: `TRUSTED / UNKNOWN / DISABLED / INVALID`; источник и доверие
  показываются пользователю. Из `UNKNOWN` установка требует явного решения.

См. [docs/source-providers.md](docs/source-providers.md).

## Root-режим (опционально)

Settings → Installation → Root Mode. Проверка фактической возможности `su -c id`
(не только наличия бинарника), состояния
`UNAVAILABLE / AVAILABLE / AUTHORIZATION_REQUIRED / AUTHORIZED / DENIED / REVOKED`,
команды только из валидированных структурированных аргументов (никаких
`sh -c "<данные из сети>"`), таймауты, отмена, пост-проверка реально установленной
версии. Root используется **только** как бэкенд установки — не для обхода
верификации. См. [docs/root-installation.md](docs/root-installation.md).

## Модель безопасности

- HTTPS + проверка сертификатов; SHA-256 артефакта против метаданных источника;
  packageName/versionCode/ABI против заявленных; сравнение подписи установленного
  и нового APK (несовместимая подпись → автоматическое обновление заблокировано
  с пояснением; root это НЕ обходит).
- Никаких коллекций паролей/Google-учётных данных, никаких fake Google Play API,
  никакого выполнения удалённых команд, никаких способов обхода DRM/Play Integrity.
- `QUERY_ALL_PACKAGES` используется, т.к. сканирование списка установленных
  приложений — основная функция менеджера обновлений; список не покидает устройство
  (см. [docs/security.md](docs/security.md)).
- Секретов в Git нет; release-подпись настраивается через локальное окружение.

## Приватность

Privacy-first: список установленных приложений не отправляется никуда без явного
согласия; аналитики нет (и по умолчанию OFF навсегда в этой сборке); метаданные
запрашиваются напрямую у выбранных репозиториев.

## Требования и сборка

- JDK 17, Android SDK (platform 35, build-tools 35.0.0)
- `local.properties`: `sdk.dir=...`

```bash
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # unit-тесты
./gradlew :app:lintDebug       # статический анализ
```

Полная инструкция: [docs/build.md](docs/build.md).

## Тесты

Unit-тесты (JVM): `VersionComparatorTest`, `UpdateEngineTest`, `UpdateStateMachineTest`,
`SourceResolutionPolicyTest`, `CompatibilityCheckerTest`, `HashVerifierTest`
(реальные SHA-256 на временных файлах), `SignatureVerifierTest`,
`DownloadEngineTest` (MockWebServer), `FdroidSourceProviderTest` (MockWebServer +
реальный формат index-v1), `InstallationStrategyResolverTest`,
`RepositoryTest`, мапперы Room-сущностей.

```bash
./gradlew test
```

## Структура проекта

```
public/download/
├── app/            # точка входа
├── core/           # 10 инфраструктурных модулей
├── domain/         # use cases + интерфейсы репозиториев
├── data/           # реализации, source providers
├── feature/        # 8 feature-модулей (Compose)
├── docs/           # архитектура, безопасность, root, источники, движок, сборка
├── scripts/        # build/verify/test сценарии
├── releases/       # NovaStore-debug.apk
└── README.md
```

## Ограничения (честно)

- Внешние аккаунты (Google OAuth) **не реализованы** — требуется серверный OAuth client,
  которого нет; подделка формы логина запрещена ТЗ и не сделана. Anonymous Mode полный.
- `ManagedDeviceInstallationStrategy` (Device Owner) реализован проверкой статуса
  device owner; на обычном устройстве честно сообщает «недоступно».
- Robolectric-инструментальные тесты БД не используются (JVM-тесты мапперов вместо них);
  установка на реальном устройстве требует сессии Android.
- F-Droid index-v2 не используется: клиент работает с реальным index-v1
  (тот же структурированный формат, что использует официальный клиент F-Droid).

## Troubleshooting

- «Installation blocked by Play Protect» — это решение системы, Nova Store его не
  обходит (по ТЗ).
- «Different signing certificate» — обновление с другой подписью заблокировано
  осознанно.
- Root-установка молча не работает — см. статус Root в Settings → Installation;
  при REVOKED приложение не переключается скрытно на Standard.
- Нет сети — экраны показывают кэш и «Last checked: …».

## What's new in this build (v2)

**Google Play as a full source** (vendored GPlayApi, GPL-3):
- Sign in with your Google account (email + App Password) OR anonymously via a token dispenser
- Search across Google Play + all F-Droid repositories simultaneously
- Check updates for ALL installed apps — apps installed from Play Market are updated from Play, F-Droid apps from F-Droid
- Download & install split APKs (App Bundles) via one PackageInstaller session
- Device spoofing: present yourself as Pixel 10 Pro, Pixel 10, Pixel 8 Pro, Pixel XL, Galaxy S24 Ultra, Pixel Tablet and 37 more devices

**Premium UI (Play Store level):**
- Redesigned Home: hero carousel, category chips, featured apps, new arrivals, paged app grid
- Fixed: search input no longer loses text while typing
- Updates screen with app icons, ratings, sizes and source badges
- Installed apps: full details with Uninstall / Open / App settings actions
- App details: screenshots, ratings, what's new, versions, security info
- Settings: account card, device profile picker, repositories manager, update schedules

**Fixed:** home screen scrolling & categories, uninstall support, updates without icons, "Not tracked by Nova Store" dead-end.

Build: JDK 17, Android SDK 35, `./gradlew assembleDebug`.

## What's new in this build (v3)

**Passwordless "device account" login (the big one):**
- New sign-in method that uses the Google account already registered on the phone:
  Nova Store asks Android's AccountManager for a scoped OAuth token and exchanges it
  for a Play (AAS) token. No password, no 2FA, no app passwords, no server to deploy.
- One tap in Account → pick an account → done. Android may show a one-time consent dialog.

**Anonymous access with failover:**
- Anonymous login now tries: your custom dispenser URL → built-in community dispensers
  (toggleable in Settings) → graceful F-Droid-only fallback with a clear explanation.
- Token dispensers fully explained in Settings (what they are, how they work).

**Premium UI:**
- Status-bar overlap fixed (header now sits below the system bar)
- Home grid: choose 2/3/4 columns, icon size (S/M/L), grid or list style — persisted
- Clean Play-Store-style grid cells (no card clutter behind icons), horizontal carousels
- Category chips curated: the 120+ niche F-Droid categories ("Cast", "Dice", …) are gone;
  only well-known localized categories remain
- App details: "Photos" section with F-Droid fastlane screenshots
  ({repo}/{pkg}/en-US/phoneScreenshots) + Play screenshots, full-screen photo viewer
- App details header: accent gradient with rounded corners
- Update All / Update / View buttons are now bright (white on gradient), never dark
- Updates: apps disappear from the list immediately after updating (auto re-scan);
  per-app menu to ignore a single version or all updates + ignored list management

**Localization:** full UI translation into English, Russian, French and Spanish
(per-app locale switch in Settings, instant apply).

**Themes:** System / Light / Dark / AMOLED-black + 6 accent palettes
(Emerald, Ocean, Violet, Amber, Rose, Lime) — gradients, buttons and highlights follow.

Build: JDK 17, Android SDK 35, `./gradlew assembleDebug` → `releases/NovaStore-v3-debug.apk`.

## What's new in this build (v4)

**Nova Anonymous Engine — our own anonymous access scheme (no third-party login services, no servers to deploy):**
- **Nova Web Catalog**: the app reads the *public* play.google.com store pages directly
  (exactly like a browser does — they are open to everyone). Result: anonymous search,
  details, **screenshots for every app**, ratings and download counts, with zero accounts
  and zero servers. This replaces the old pooled-account "token dispenser" approach —
  no more 403 errors from shared dispensers.
- **Community mirror (APKPure)**: anonymous version histories and plain-APK downloads
  as a fallback. Update checks for Google-Play-installed apps now work without any
  login: the mirror provides the latest version numbers, downloads go through the same
  mirror, and the original Play delivery kicks in automatically when a session exists.
  Toggleable in Settings → Sources (some networks/regions block it; failures degrade
  gracefully).
- **Install/update fallback chain**: a signed-in session delivers genuine Play files;
  when there is no session (or it breaks mid-download), the mirror takes over
  transparently — same button, same flow.
- Optional "session provider URL" (advanced): a self-hosted or trusted token dispenser
  endpoint that can mint a genuine Play session; empty by default, never required.
- Anonymous sign-in can no longer fail: pressing "Continue without an account" always
  activates the engine (Web Catalog + mirror + F-Droid).

**Email login fixed:**
- ClientLogin now sends the full parameter set Google expects (`add_account`,
  `has_permission`) and strips spaces from App Passwords automatically — the
  "Bad authentication" error with 2FA app passwords is gone.

**No third-party branding:**
- All third-party/"dispenser" wording removed from the UI; the access scheme is presented
  and documented as Nova Store's own engine (the vendored protocol library remains
  GPL-3 credited in About).

**UI:**
- Settings rebuilt: compact grouped sections (Appearance / **Sources** / Updates /
  Advanced) with per-source toggles instead of one long flat list.
- Account screen: the anonymous engine is now the primary, recommended path with a
  tier breakdown card; device-account and email sign-ins are secondary.
- App details "Available version": compact single-line rows (name · size · date),
  no more oversized gaps.
- Photos section upgraded: higher-resolution screenshots in the full-screen viewer.

Build: JDK 17, Android SDK 35, `./gradlew assembleDebug` → `releases/NovaStore-v4-debug.apk`.

### Known limitations (honest)
- XAPK/APKM-only variants cannot be installed account-lessly yet (plain APKs and
  split deliveries work); sign in for those.
- The community mirror is a public website: it may be rate-limited or blocked on some
  networks/regions; the app stays fully usable through the Web Catalog and F-Droid.
- Play *metadata* via the Web Catalog is public; paid apps still cannot be downloaded
  without purchasing them.

## What's new in this build (v5)

**The catalog finally works for every app — root cause fixed:**
- Google rotated the obfuscated CSS classes and localized the image alt texts on the
  public Play pages, which silently broke names, ratings and screenshots (that is why
  you saw `org.telegram.messenger` under the icon). v5 parses the **AF_initDataCallback
  JSON trees** the page embeds — the same technique the leading open-source Play
  scrapers use. Language-independent, immune to CSS rotation, and every field is richer
  than before: proper names, developers, categories, icons, banners, content rating.

**Real user reviews — no account, no server:**
- The details screen now shows a **Ratings & reviews** card: the average score, the
  **full star histogram** (5→1) and **actual Play reviews** — author, avatar, rating,
  date, full text, even developer replies — read from the same public reviews feed the
  Play page itself loads (`UsvDTd` batchexecute RPC, fully anonymous).

**Full descriptions:**
- "About" used to show the two-line meta description. It now renders the complete
  listing text (multi-paragraph, with bullets) with an expand/collapse control, plus
  "Updated on" / "Released on" dates in the header.

**Screenshots for F-Droid and open-source apps:**
- The fastlane screenshot prober stays, and the web catalog now covers every app with
  full screenshot sets (portrait and landscape, high-resolution variants in the viewer).

**More sources, all toggleable (Settings → Sources):**
- **GitHub releases**: open-source apps published as APK release assets are searchable
  (`github.` synthetic ids) and installable straight from GitHub's CDN.
- **GitLab releases**: same, through GitLab's public API.
- **Second mirror (APKCombo)**: APKPure remains stage one; APKCombo — the same catalog
  family on a different edge — is stage two for version histories, so a block on one
  family no longer empties the "Available version" list.

**The download fallback nothing else has — built-in web mirror:**
- When a version cannot be resolved anonymously (JavaScript-gated mirror page, captcha,
  bundle-only), the details screen offers **"Get from web mirror"**: an in-app WebView
  (desktop UA, JS enabled) where you tap Download on the mirror page, and Nova Store
  **intercepts the real file URL** the moment the download starts, then finishes the
  normal download → verify → install pipeline itself. Cloudflare, countdowns and
  captchas pass because it is a real browser view; the file still lands in Nova's
  verified install flow.

**Search, the way you asked:**
- Result layout switcher (**Auto / Grid / List**), grid columns 2×/3×/4×, sort
  (**Relevance / Top rated / Most downloaded / Name A–Z**), and source filter chips
  (All / Play / F-Droid / GitHub / GitLab). All persisted.

**Scroll performance:**
- Stable keys + contentType on every lazy list, hoisted scroll state, 2-minute web
  search caching and no re-fetch storms while switching layout/sort/filters — the home
  and search pages no longer freeze while scrolling.

Version 5.0.0 (versionCode 5). APK: `releases/NovaStore-v5-debug.apk`.

## Что нового в 7.2.0

**Главная:**
- «Все приложения» теперь включает Google Play: публичные витрины Play (главная, игры
  и 19 категорий) чередуются со страницами репозиториев, без дублей — лента не пустая,
  даже если все репозитории выключены. Чипы категорий Play: Игры, Связь, Инструменты…
- Плавная прокрутка: ячейки сетки без `BoxWithConstraints` (раньше 6–8 дорогих
  замеров на строку), шиммер рисуется без перекомпозиции, одна звезда рейтинга вместо пяти.
- Шапка «Nova Store» закреплена (больше не уезжает под статус-бар), логотип — градиент
  #6965F1 → #A556F7. Pull-to-refresh убран (конфликтовал со скроллом).
- Возврат из карточки приложения сохраняет позицию прокрутки.
- Ряд «Избранное».

**Обновления:**
- Нет «пустого экрана» после обновления одного приложения: очистка устаревших строк
  выполняется один раз в конце сканирования.
- Модифицированные приложения: «Платное в Google Play» → кнопка «В Play»; «Другая подпись»
  → «Заменить» (удалить мод и поставить оригинал) или «Скрыть». Такие строки не попадают
  в «Обновить всё» и счётчики. Ошибки показываются по конкретному приложению.

**Карточка приложения:** кнопка «Открыть», сердечко «В избранное», секции
«Дополнительная информация» (версия, даты, размер, цена, покупки в приложении, реклама,
Android min/target, пакет), «Зависимости» (с отметкой, что установлено), «Разрешения»
(с системными подписями, опасные — отмечены), «Разработчик» (сайт, почта, адрес).
Для платных — «Открыть в Google Play».

**Прочее:** поиск не перезапускается при возврате; «Установленные» по умолчанию без
системных приложений (переключатель в меню); «О приложении» показывает реальную
версию, источники, устройство, конфиденциальность и лицензию. Исправлена инициализация
WorkManager — фоновая проверка обновлений снова запускается. Release-сборка
(не отладочная, с baseline-профилями) подписана тем же ключом, что и debug.

Версия 7.2.0 (versionCode 11). APK: `releases/NovaStore-v7.2.0.apk` (рекомендуется),
отладочная — `releases/NovaStore-v7.2.0-debug.apk`.

## Что нового в 7.1.0

**Google Play без аккаунта — по-настоящему:**
- Анонимная сессия Google Play включена по умолчанию: поиск, карточки, точные
  версии для этого устройства и **оригинальные APK/сплиты с серверов Google** —
  без аккаунта, без сервисов Google и без microG. Сессия живёт только в памяти,
  перевыпускается автоматически при 401/429 и через 45 минут.
- Прежний клиент анонимного входа слал не тот запрос и был выключен (пустой URL) —
  поэтому всё уходило в зеркала и упиралось в «зеркало отдаёт файлы только через
  скрипт сайта». Теперь зеркала — только запасной путь, а строка из зеркала перед
  скачиванием автоматически «повышается» до доставки из Play.
- Нативный профиль устройства (по умолчанию): Play видит реальные Android/ABI/
  плотность экрана и отдаёт правильный вариант сборки и сплиты.
- Настройки → Источники → «Google Play без аккаунта» — отдельный переключатель;
  APKPure и APKCombo теперь включаются/выключаются независимо друг от друга.

**Обновления без «11.0.3 → 11.0.3»:**
- Удалён ярус DISCOVERY (строки, где «доступная» версия = установленной).
- Единое правило `UpdateRules`: одинаковое имя версии — никогда не обновление;
  зеркала сравниваются по имени. Покрыто юнит-тестами.
- Через Play проверяются все приложения, чей ключ подписи не совпадает со
  сборками репозиториев (в т.ч. установленные самим Nova), а не только те,
  что поставил Play Маркет.

**Прочее:**
- Удаление: добавлено разрешение `REQUEST_DELETE_PACKAGES` (без него Android 9+
  молча игнорировал запрос), кнопки обновляются сразу после системного диалога,
  списки реагируют на установку/удаление мгновенно.
- Уведомление о прогрессе: «Загрузка репозиториев 3/12 · …», «Проверка Google Play…».
- Репозитории скачиваются параллельно (до 4 одновременно).
- Поиск: локальные результаты сразу, затем Play (с догрузкой страниц), веб-каталог,
  GitHub, GitLab; прямой поиск по имени пакета.
- Прокрутка: иконки установленных приложений растрируются в фоне и кэшируются;
  главная догружает по 60 приложений и не «замирает» в конце ленты.
- Исправлена сборка на Windows (конфликт регистра в GooglePlay.proto), добавлен `gradlew.bat`.

Версия 7.1.0 (versionCode 10). APK: `releases/NovaStore-v7.1.0-debug.apk`.

## What's new in this build (v7.0.2)

**Почему «11.11.3 → 11.11.3» выжил после v7.0.1 — и почему теперь не выживет:**
- **Синтетические versionCode APKCombo (~900M) обходили правило EXACT.**
  Правило v7.0.1 «доступный код > установленного» честно, но строки,
  созданные из метаданных APKCombo, несут фейковые коды (900 000 000 −
  ранг листинга) — они «строго больше» любого реального кода, поэтому
  мусорные строки с равными именами версий продолжали показываться. Теперь
  добавлен `SyntheticVersionCodes`: строки с синтетическими кодами
  требуют **строго более нового имени версии**, а **равное имя = тот же
  релиз = никогда не обновление** — для любых строк, не только зеркальных.
- **Мёртвые строки удаляются при скане, а не только маскируются.** Если
  зеркало ОТВЕТИЛО по пакету (версий нет или они не новее), а кандидат не
  создан — лежащая строка EXACT удаляется сразу. F-Droid и DISCOVERY-строки
  фильтр не трогает. Синтетический код больше не может затенить реальную
  запись APKPure при выборе кандидата.

**Единая цепочка анонимной доставки (главный архитектурный фикс):**
- Раньше: строка из APKCombo → APKCombo → JS-защита → ошибка «зеркало
  отдаёт файлы только через скрипт сайта» → тупик. Теперь **каждый** путь
  скачивания идёт через цепочку **APKPure по коду → APKPure по ИМЕНИ
  версии → APKCombo по имени**. Слепая зона одного зеркала больше не
  становится ошибкой пользователя.
- **Anonymous Play pipeline подключён**: если в Настройках → Источники
  задан URL token-дispenser'а, Nova лениво минует анонимную Play-сессию и
  доставляет **оригинальные файлы Play без аккаунта на устройстве** (сессия
  только в памяти, не сохраняется; сбой → TTL 10 минут → зеркала). Без
  dispenser'а — честный анонимный режим через зеркала, как раньше.
  `AnonymousAuth` больше не мёртвый код.

**HTTP 200 ≠ APK (проверка содержимого):**
- DownloadEngine теперь отклоняет очевидные `text/html` / `text/plain` /
  `application/json` ответы **до** записи на диск (страница Cloudflare с
  кодом 200 больше не сохраняется как `app.apk`), и проверяет magic bytes
  `PK` (APK/XAPK/APKM — это ZIP-контейнеры) после загрузки базового файла
  и каждого сплита. Фейковый файл падает сразу с понятной ошибкой.
- **XAPK определяется по содержимому**: ZIP с `manifest.json` внутри —
  контейнер, даже если CDN переименовал файл. Раньше — только по
  расширению `.xapk`.

**Реальная шкала загрузки при установке:**
- Экран приложения показывал **неопределённый** индикатор «Загрузка и
  проверка…» на весь процесс. Теперь: живой **определённый** прогресс —
  «Загрузка… 45% · 23.1 МБ / 51.4 МБ · 4.2 МБ/с» — с реальными процентами,
  байтами и скоростью движка; неопределённая полоса осталась только для
  фаз без измеримого прогресса (резолв зеркала, верификация, системный
  диалог установки).

**Запрос доступа к файлам при первом запуске:**
- Раньше разрешение на память запрашивалось только вручную через
  Настройки. Теперь при первом запуске Nova один раз показывает диалог с
  объяснением → «Разрешить» открывает точный экран системы (All files
  access на Android 11+, обычный runtime-диалог на 10-). Оба ответа
  считаются «спрошено», повторных запросов нет.

**Производительность (P0 от ревью):**
- **Home scroll**: чтение `listState.isScrollInProgress` убрано из
  композиции — буферизация обновлений ленты переехала в `LaunchedEffect`
  + `snapshotFlow` (старый механизм сам провоцировал пересборки на
  старте/стопе каждого жеста).
- **Иконки**: per-request `crossfade(false)` (кроссфейд 40–100 маленьких
  иконок во время fling — чистый оверхед) и явный `.size(px)` для декода
  Coil ровно под размер ячейки.
- **Поиск**: все источники (F-Droid, Play/Web, GitHub, GitLab)
  запускаются **параллельно** — медленный источник больше не задерживает
  весь результат; порядок слияния сохранён.

**UI:**
- **Сетка 4× в поиске**: числовой рейтинг («3,8») больше не переносится
  колонкой символов под звёзды в узких ячейках — узкие лайны получают
  компактную строку Play-стиля (★ 3,8, одна строка без переноса),
  широкие — полную пятизвёздную.
- Баннер «установка из неизвестных источников» локализован (4 локали).

Version 7.0.2 (versionCode 9). APK: `releases/NovaStore-v7.0.2-debug.apk`.

## What's new in this build (v7.0.1)

**Фиксы списка «Обновления» — «вечные» строки и «11.11.3 → 11.11.3»:**
- **Провал загрузки больше не оставляет строку «в процессе» навсегда.**
  Раньше при ошибке доставки (например, честный отказ APKCombo) строка
  оставалась в состоянии QUEUED, а чистка скана намеренно не трогает
  активные загрузки — они накапливались бессмертными. Теперь провал
  переводит строку в FAILED, и следующий скан её убирает.
- **Жёсткое правило EXACT: доступная versionCode должна быть СТРОГО выше
  установленной.** «Установленная» сторона строки обновляется из системы
  живьём, а «доступная» заморожена — после обновления приложения любым
  способом старая строка показывала «11.11.3 → 11.11.3, готов к
  установке». Теперь такая строка не отображается никогда (DISCOVERY-строки
  — сигналы даты — исключение по дизайну).
- **Зеркала предлагают только «строго новее», а не «отличается».** Старая
  проверка `имя != имя` считала обновлением любое расхождение (другое
  форматирование, устаревший листинг) — включая даунгрейды. Теперь
  сравнение строгое, а непарсенное имя версии (синтетический
  «v<код зеркала>») вообще не считается обновлением.
- **Zombie-чистка**: строки «в процессе», не обновлявшиеся больше суток
  (убитый процесс, потерянная задача), удаляются сканом.
- **Честная подпись состояния**: «Готово к установке» → **«Ожидает
  загрузки»** — старая подпись показывалась даже когда ничего не было
  скачано.
- DISCOVERY-строки без повторившегося сигнала сбрасываются безусловно
  (раньше смена источника установки могла осиротить их навсегда).

Version 7.0.1 (versionCode 8). APK: `releases/NovaStore-v7.0.1-debug.apk`.

## What's new in this build (v7)

**Nova Resolver v8 — обновления ищутся ВСЕГДА, даже без аккаунтов и зеркал:**
- Движок обновлений разделён на уровни честности: **EXACT** (источник назвал
  реальный versionCode — подтверждённое обновление) и **DISCOVERY** (публичная
  страница Google Play сообщила новую дату «Updated on» — приглашение проверить).
- **Play Web Watch**: ежедневный фоновой скан публичных страниц Play для
  установленных приложений — без аккаунта, без зеркал, без серверов. Дата
  изменилась → в Обновлениях появляется строка «Обновлено в Google Play: дата»
  с кнопкой **«Проверить»** (открывает карточку — там движок пробует все способы
  доставки). Зеркала выключены? Обновления всё равно ищутся.
- «Обновить всё» трогает только подтверждённые (EXACT) обновления — слепых
  установок по сигналу даты не бывает.
- **Гонка источников**: APKPure и APKCombo опрашиваются параллельно —
  Cloudflare-блокировка одного больше не тормозит скан (проверено: из сетей
  дата-центров APKPure закрыт челленджем, с реальных мобильных IP работает).

**XAPK-бандлы теперь устанавливаются (главная причина «не ставится с зеркала»):**
- APKPure часто отдаёт приложения как XAPK (ZIP с base.apk + конфиг-сплитами).
  Раньше мы честно отвергали — теперь контейнер **распаковывается на устройстве**,
  собирается набор «base + сплиты под твоё устройство» (ABI/плотность/язык — как
  это делает сам Play), и всё ставится **одной сессией PackageInstaller**.
- Перед установкой проверяются те же гарантии: имя пакета, версия, **подпись**
  (несовпадение с установленной = честный отказ), полнота набора.

**Кэш доверия (задел репутации релизов):**
- Каждая успешная установка запоминает «пакет → сертификат → источник».
  Это цепочка доверия: будущие версии сверяются с ней — подмена источника с
  другой подписью не пройдёт молча.

**WebView-браузер зеркала удалён насовсем:**
- Вместо псевдо-загрузки в встроенном браузере — честные состояния: прямая
  загрузка, XAPK-установка, либо (последний шанс) кнопка открывает поиск
  зеркала в обычном браузере системы.

**UI:**
- «Оценки и отзывы» — теперь **один раздел**: сводка + гистограмма + **3 отзыва +
  кнопка «Читать все отзывы (N)»** → шторка со всеми отзывами.
- **Плавность главной**: обновления ленты больше не дёргают сетку во время
  скролла (изменения применяются при остановке прокрутки), Coil-кэш
  (256 МБ диск), декодирование картинок ровно под размер ячейки.
- **Скриншоты F-Droid**: поиск в нескольких локалях (en-US/en/de-DE) + разбор
  веб-страницы пакета (один запрос находит ВСЕ скриншоты разработчика,
  проверено на NewPipe: 23 против 6 раньше). IzzyOnDroid уже встроен.

Version 7.0.0 (versionCode 7). APK: `releases/NovaStore-v7-debug.apk`.

## What's new in this build (v6)

**Отзывы теперь работают — и богаче:**
- Найдена и исправлена причина пустых отзывов: Google молча отклонял запрос (RPC
  `UsvDTd` теперь требует плоский массив `["pkg", 7]` вместо вложенного). Проверено
  живьём: 20 реальных отзывов на приложение, на языке интерфейса, с фолбэком на
  английский.
- В карточке отзыва появились: **версия приложения, о которой отзыв**, счётчик
  «полезно» (👍 N), ответ разработчика — всё из живого фида Play.

**Поиск снова находит оригиналы:**
- Корень проблемы: оригинальное приложение (например, Telegram) есть только в
  HTML-карточке страницы поиска — ни в одном `AF_initDataCallback`-блоке. Добавлен
  парсер серверных карточек (стабильные атрибуты: href, aria-label, иконки, рейтинг):
  запрос «Telegram» теперь находит **настоящий Telegram** первым.

**Обновления стали честными:**
- Фантомные обновления «4.0.2 → 4.0.2» устранены: для зеркал (APKPure/APKCombo)
  обновлением считается только **отличающееся имя версии** (у APKCombo коды версий
  синтетические — ~900M — и всегда «новее»; теперь решает имя).
- **Системные приложения исключены** из Обновлений — только пользовательские.
- Игнорируемые приложения больше не считаются ни в баннере «Доступны обновления»
  на главной, **ни в бейдже нижней панели**.

**Скорость и UI:**
- Главная грузится мгновенно: контент рендерится из локальной БД сразу, сетевое
  сканирование репозиториев идёт параллельно в фоне.
- Карточка обновления переработана в **одну строку**: иконка → название → старая →
  новая версия → размер → кнопка «Обновить» → меню «⋮» (игнор). Ничего не переносится.
- Иконки сетки больше не выходят за края при 4 колонках: размер иконки
  автоматически ужимается под ширину ячейки (главная, поиск, карусели).
- Подписи вкладок нижней панели — одна строка, 10sp: «Установленные» больше не
  переносится, иконка не подпрыгивает.
- Блок «Оценки и отзывы» опущен ниже — больше воздуха после кнопок.
- **Отмена установки = не ошибка**: нажали «Отмена» в системном окне — виден нейтральный
  баннер «Установка отменена», а не красный «Installation failed».

**Загрузки под контролем:**
- В разделе «Завершённые» появилась кнопка **«Очистить»** (файлы + записи), для
  неудачных — своя очистка.
- **Автоочистка** завершённых загрузок: Выкл / 1 / 3 / 7 / 14 дней (настройки →
  «Хранилище и загрузки», и прямо на экране загрузок) — APK-файлы не копятся.
- Загруженное APK автоматически перепроверяется перед установкой — как раньше.

**Зеркало без тупика:**
- Если APKCombo отдаёт ссылку только через JavaScript страницы, Nova Store
  **сам открывает встроенный браузер зеркала** ( раньше — непонятная английская
  ошибка): берём файл, перехватываем, ставим — как всегда.

**Уведомления как у больших:**
- Фоновое сканирование (WorkManager, по расписанию) при появлении новых обновлений
  присылает уведомление: «Доступны обновления: N» + список «Приложение: старая → новая»
  (до 5 строк) — тап открывает вкладку Обновлений.

**Доступ ко всем файлам:**
- Запрос `MANAGE_EXTERNAL_STORAGE` объявлен; в настройках есть строка состояния и
  переход к системному экрану разрешения.

Version 6.0.0 (versionCode 6). APK: `releases/NovaStore-v6-debug.apk`.
