# DNS Switcher

Android application for system DNS switching without Root privileges via local `VpnService`.

## Overview

The application captures UDP DNS queries (port 53) via a local TUN loopback interface and relays them to configured upstream resolvers using standard UDP or DNS-over-TLS (DoT, port 853). All generic non-DNS application traffic (HTTP/HTTPS, messaging, media) completely bypasses the VPN tunnel (`allowBypass()`), preserving native Wi-Fi/cellular throughput and latency.

## Key Features

- **No Root Required**: Operates entirely in user space via the Android `VpnService` API.
- **Selective DNS Routing**: Only virtual DNS endpoints (`10.0.0.1`, `10.0.0.3`, `fd00::1`) are routed into the TUN interface; generic TCP/UDP traffic is untouched.
- **Real-Time Latency Measurement**: Live round-trip time (RTT in ms) benchmarks for each configured server with visual color coding.
- **Quick Settings Tile**: Fast single-tap toggle directly from the Android notification shade to enable or disable active DNS protection.
- **IPv6 Leak Protection**: Virtual IPv6 loopback route prevents mobile ISP DNS fallback on dual-stack networks.
- **Flexible Upstream Protocols**: Supports standard IPv4 DNS endpoints and encrypted DoT/DoQ domain targets.
- **Resolver Management**: Delete presets or custom resolvers, with instant one-click restoration of defaults.
- **Fallback Transparency**: Automatic fallback indicators if an upstream host is unreachable.
- **State Persistence**: Selected endpoints and custom configurations persist across restarts via `SharedPreferences`.
- **Fast Startup**: Smooth Network Core launch transition without static splash icon flicker.

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
