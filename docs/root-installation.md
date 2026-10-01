# Root Installation Backend

Root в Nova Store — **опциональный** способ установки. Приложение полноценно работает
без root (Standard `PackageInstaller`).

## Поддерживаемые окружения

- Стандартный `su` (Magisk, KernelSU и совместимые root-менеджеры), где
  `/system/bin/su` или `/system/xbin/su` доступны и выполняются.
- Root-менеджер пользователя сам решает, выдавать ли доступ: Nova Store не хранит
  и не запрашивает пароли, использует только предоставленный авторизованный канал.

## Детекция (`SuRootAccessProvider`)

Наличие бинарника `su` НЕ считается доказательством. Проверяется фактическая
возможность выполнить безопасную privileged-операцию (`su -c id`), с состоянием:

```
UNAVAILABLE          — su отсутствует или не исполняем
AVAILABLE            — su есть, авторизация ещё не запрашивалась
AUTHORIZATION_REQUIRED — su запрашивает подтверждение у пользователя
AUTHORIZED           — `su -c id` вернул uid=0
DENIED               — root-менеджер отказал
REVOKED              — доступ был и отозван
ERROR                — сбой выполнения
```

## Авторизация

Settings → Installation → Root Mode → Enable:
1. проверка root (выше);
2. вызов авторизационного механизма root-менеджера (пользователь подтверждает в
   системном диалоге root-менеджера);
3. сохраняется ТОЛЬКО preference «root mode включён» (никаких credentials/tokens);
4. перед КАЖДОЙ root-операцией доступ проверяется заново.

## Отзыв (§34)

Если root отозван: состояние `REVOKED`, привилегированная операция останавливается,
пользователю показывается «Root permission is no longer available» с предложением
включить Standard Installation. Скрытого переключения на Standard НЕ происходит.

## Пайплайн root-установки (§32)

```
Download → SHA-256 → package verification → version verification
→ certificate verification → compatibility check → InstallationPlan
→ request/verify root access → privileged installation
→ wait for result → PackageManager verification → confirm installed version
→ database update → cleanup
```

Успешный exit code root-команды ≠ успех установки: всегда подтверждается реальный
установленный packageName/versionCode через `PackageManager`.

## Выполнение команд (`RootCommandExecutor`)

- Команды строятся приложением из валидированных structured arguments:
  `su -c pm install-create ...` и т.п.
- Пути передаются только локальные, валидированные файлы из приватного каталога
  приложения.
- НИКОГДА: `sh -c "<данные из сети>"`, URL в shell, metadata источника в shell.
- Таймаут, exit code, stdout/stderr capture, cancellation (destroy process),
  error mapping (denied/revoked/timeout/unknown).
- Чувствительные данные (токены, вывод с секретами) не логируются.

## Мульти-APK

Сплиты устанавливаются через сессию `pm install-create` → `pm install-add`
(по одному `.apk`) → `pm install-commit`, с проверкой session-id между шагами.

## Security model

- Root НЕ отключает верификацию артефакта (§107).
- Root НЕ позволяет смену подписи (§106): certificate check выполняется до root-шага.
- Root НЕ используется для обхода Play Protect, SELinux, DRM, системных промптов.
- Отказ root → операция останавливается с понятной ошибкой (RootDenied/RootRevoked),
  без бесконечных повторов.

## Troubleshooting

- «Root unavailable» — на устройстве нет su / root-менеджер не установлен.
- «Authorization required» — подтвердите запрос в root-менеджере.
- Установка не подтверждена, хотя команда успешна — версия/подпись не совпали
  с ожидаемыми; смотрите Update History (source, error).
- После перезагрузки root «пропал» — root-менеджер ещё не поднял su-сервис;
  Nova Store повторит проверку перед следующей операцией.
