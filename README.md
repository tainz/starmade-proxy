# StarMade TCP+UDP Proxy

A simple StarMade proxy server using Netty 4.2.x.

## Architecture

```
Client (TCP/UDP) → Frontend Proxy (localhost:4243) → Backend Server (localhost:4242)
                         ↓
                   Login Sniffer
                   Backpressure Handler
```

### Package Structure

- `nz.tai.starmade` - Main entry point
- `nz.tai.starmade.config` - Configuration management
- `nz.tai.starmade.tcp` - TCP frontend/backend handlers
- `nz.tai.starmade.udp` - UDP frontend/backend handlers and session management
- `nz.tai.starmade.protocol` - Protocol-specific handlers (login sniffer)
- `nz.tai.starmade.common` - Shared handlers (backpressure)

## Requirements

- Java 17 or higher
- Maven 3.6+
- StarMade server running on localhost:4242

## Building

```
mvn clean package
```

This produces an executable JAR in `target/starmade-proxy-1.0-SNAPSHOT.jar`.

## Running

### With default configuration (localhost:4242 → localhost:4243):

```
java -jar target/starmade-proxy-1.0-SNAPSHOT.jar
```

### With custom configuration:

```
export BACKEND_HOST=10.0.0.5
export BACKEND_TCP_PORT=4242
export FRONTEND_TCP_PORT=4243
java -jar target/starmade-proxy-1.0-SNAPSHOT.jar
```

### Available environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `BACKEND_HOST` | 127.0.0.1 | Backend server hostname/IP |
| `BACKEND_TCP_PORT` | 4242 | Backend TCP port |
| `BACKEND_UDP_PORT` | 4242 | Backend UDP port |
| `FRONTEND_TCP_PORT` | 4243 | Frontend TCP listen port |
| `FRONTEND_UDP_PORT` | 4243 | Frontend UDP listen port |
| `MAX_FRAME_SIZE` | 10485760 | Maximum frame size (10MB) |

## Logging

Logs are written to:
- Console (INFO level)
- `logs/starmade-proxy.log` (rotating daily, 30-day retention)

Adjust log levels in `src/main/resources/logback.xml`.

## Testing

Run unit tests:

```
mvn test
```

Run with coverage report:

```
mvn clean test jacoco:report
```

Coverage report is generated in `target/site/jacoco/index.html`.
