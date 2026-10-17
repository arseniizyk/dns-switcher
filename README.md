# QuickDNS Switcher

Android-приложение для смены системных DNS-серверов без Root-прав через локальный `VpnService`.

## Возможности

- Переключение DNS в один клик.
- Поддержка IPv4 и DoT/DoQ-хостов (например, Cloudflare, Google DNS, Quad9, AdGuard, Geohide).
- Работа без Root: весь сторонний трафик идет напрямую в сеть через bypass, перехватываются только DNS-запросы на порту 53.
- Современный интерфейс на Jetpack Compose Material 3.

## Требования

- Android 8.0 (API 26) и выше
- JDK 17+
- Android SDK (API 35)

## Сборка проекта

Сборка отладочного APK через Gradle:

```bash
./gradlew assembleDebug
```

Сборка релизного APK:

```bash
./gradlew assembleRelease
```
