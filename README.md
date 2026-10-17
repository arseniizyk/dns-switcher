# QuickDNS Switcher

Android application for system DNS switching.

## Overview

The application captures UDP DNS queries (port 53) via a local TUN interface and relays them to configured upstream resolvers using standard UDP or DNS-over-TLS (DoT, port 853). All non-DNS traffic bypasses the VPN tunnel (`allowBypass()`), preserving native Wi-Fi/cellular throughput and latency.

## Key Features

- **No Root Required**: Operates using Android's native `VpnService` API.
- **Selective Routing**: Only DNS endpoints (`10.0.0.1`, `10.0.0.3`, `fd00::1`) are routed into the TUN interface; generic TCP/UDP traffic is untouched.
- **IPv6 Leak Protection**: Configured IPv6 virtual interface to prevent ISP DNS fallback on dual-stack networks.
- **Upstream Protocols**: Supports standard IPv4 DNS endpoints and DoT/DoQ domain targets.
- **Fallback Transparency**: Explicit visual indicators when an upstream host is unreachable and fallback routing is engaged.
- **State Persistence**: Custom DNS configurations and selection state persist across process restarts via `SharedPreferences`.

## Tech Stack

- **Platform**: Android 8.0+ (API level 26–35)
- **Language**: Kotlin 2.0.21
- **UI Framework**: Jetpack Compose (Material 3, BOM `2024.10.01`)
- **Build System**: Gradle 8.10.2 / Android Gradle Plugin 8.7.2

## Architecture

```text
[ Application Traffic ] ---> Direct to Internet (Bypass)
[ System DNS Queries  ] ---> TUN (10.0.0.2 / fd00::2)
                                   |
                             DnsVpnService
                                   |
                +------------------+------------------+
                |                                     |
       Plain UDP (Port 53)                  DoT / TLS (Port 853)
                |                                     |
         [ Upstream DNS ]                      [ Upstream DNS ]
```

## Build Requirements

- JDK 17 or higher
- Android SDK Platform 35
- Android SDK Build-Tools 35.0.0

## Building from Source

Debug build:

```bash
./gradlew assembleDebug
```

Release build:

```bash
./gradlew assembleRelease
```

### Release Signing Configuration

Release signing parameters can be supplied via environment variables:

| Variable | Description |
|---|---|
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

If variables are not defined, the build will use defaults or omit signing if no keystore file exists.
