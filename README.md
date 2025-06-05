# Android VPN Service with SOCKS5 Proxy

This Android app implements a simple VPN service that routes all device traffic through a local SOCKS5 proxy server. The VPN captures packets via a TUN interface and forwards them through the SOCKS5 server to the internet.

## Features

- **Simple UI**: One-button toggle to start/stop VPN
- **Local SOCKS5 Server**: Runs on 127.0.0.1:1080
- **TUN Interface**: Captures all device traffic
- **Packet Processing**: Custom TUN2SOCKS implementation
- **DNS Routing**: Routes DNS queries through the proxy
- **Connection Status**: Real-time VPN status display

## Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Android App   │───▶│  TUN Interface   │───▶│ SOCKS5 Proxy    │
│   (All Traffic) │    │  (10.0.0.2/24)   │    │ (127.0.0.1:1080)│
└─────────────────┘    └──────────────────┘    └─────────────────┘
                                │                        │
                                ▼                        ▼
                       ┌──────────────────┐    ┌─────────────────┐
                       │ Tun2SocksProcessor│    │    Internet     │
                       │  - IP parsing     │    │   Connections   │
                       │  - TCP/UDP routing│    │                 │
                       │  - ICMP handling  │    │                 │
                       └──────────────────┘    └─────────────────┘
```

## Components

### 1. MainActivity
- **UI Control**: Manages VPN toggle button and status display
- **Service Binding**: Connects to VPN service for control
- **Permission Handling**: Requests VPN permission from user
- **Status Updates**: Real-time connection status

### 2. ZyxelVpnService
- **VPN Management**: Extends Android's VpnService
- **TUN Interface**: Creates and manages virtual network interface
- **SOCKS5 Server**: Local proxy server implementation
- **Foreground Service**: Persistent notification for VPN status

### 3. Tun2SocksProcessor
- **Packet Capture**: Reads packets from TUN interface
- **Protocol Parsing**: Handles IPv4, TCP, UDP, ICMP packets
- **SOCKS5 Integration**: Routes connections through proxy
- **Connection Management**: Tracks active network connections

## Setup Instructions

### Prerequisites
- Android Studio with NDK support
- Android device with API level 29+ (Android 10+)
- VPN permission granted by user

### Build Steps
1. Clone this repository
2. Open in Android Studio
3. Build and install on device
4. Grant VPN permission when prompted

### Testing
1. Launch the app
2. Tap "Connect VPN" button
3. Grant VPN permission
4. Verify connection status shows "Connected"
5. Test internet connectivity through the VPN

## Technical Details

### VPN Configuration
- **IP Address**: 10.0.0.2/24
- **DNS Servers**: 8.8.8.8, 8.8.4.4
- **MTU**: 1500 bytes
- **Routes**: All traffic (0.0.0.0/0)

### SOCKS5 Implementation
- **Protocol**: SOCKS5 (RFC 1928)
- **Authentication**: None required
- **Address Types**: IPv4 and domain names supported
- **Connection Types**: TCP connections only

### Packet Processing
- **IP Version**: IPv4 support
- **Protocols**: TCP, UDP, ICMP
- **DNS**: Routed through SOCKS5 proxy
- **ICMP**: Basic ping response support

## Limitations

This is a basic implementation with some limitations:

1. **IPv6**: Not supported (IPv4 only)
2. **UDP**: Limited support (DNS only)
3. **Authentication**: No SOCKS5 authentication
4. **Performance**: Not optimized for high throughput
5. **Full TUN2SOCKS**: Simplified packet routing

## Future Enhancements

To make this production-ready:

1. **TUN2SOCKS**: Replace custom TUN2SOCKS with actual work library
2. **IPv6 Support**: Add dual-stack support
3. **UDP Relay**: Full UDP over SOCKS5 support
4. **Performance**: Optimize packet processing
5. **Configuration**: Configurable proxy settings
6. **Error Handling**: Better error recovery and reporting

## Recent Fixes (2025-01-06)

**Major TUN2SOCKS Data Relay Implementation:**
- ✅ Fixed missing bidirectional data relay between TUN interface and SOCKS proxy
- ✅ Implemented complete TCP session management with proper state tracking
- ✅ Added real packet reconstruction for responses back to TUN interface
- ✅ Fixed SOCKS5 handshake parsing bugs (domain name length handling)
- ✅ Enhanced DNS query forwarding through SOCKS proxy
- ✅ Added proper connection lifecycle management
- ✅ Improved error handling and logging throughout the system

**Key Improvements:**
- **Data Flow**: Now properly relays data in both directions (TUN ↔ SOCKS ↔ Internet)
- **TCP Support**: Full TCP connection handling with sequence number management
- **DNS Resolution**: Working DNS queries through the SOCKS proxy
- **Connection Tracking**: Proper session management and cleanup
- **Packet Reconstruction**: Creates valid IP/TCP packets for responses

This should resolve the "network connectivity" issues where VPN would connect but websites wouldn't load.

## License

This project is for educational purposes. Production use would require proper Leaf integration and additional security considerations.

## Debugging

Enable verbose logging to see packet flow:
```bash
adb logcat | grep -E "(LeafVpnService|Tun2SocksProcessor|MainActivity)"
```

Key log messages:
- `VPN service started successfully`: VPN is running
- `SOCKS5 server started on port 1080`: Proxy is accepting connections
- `TUN2SOCKS processor started`: Packet processing is active
- `TCP packet: [connection]`: Traffic being processed
- `SOCKS connection established`: Successful proxy connection
