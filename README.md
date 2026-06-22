# barricator-java-client

[![Maven Central](https://img.shields.io/maven-central/v/io.github.barricator/barricator-java-client?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.barricator/barricator-java-client)

Production-grade **Java Server SDK** for Barricator. Java 21, Jackson + the JDK `HttpClient` (no
heavy transitive deps).

## Install

Gradle:
```groovy
implementation 'io.github.barricator:barricator-java-client:0.1.0'
```
Maven:
```xml
<dependency>
  <groupId>io.github.barricator</groupId>
  <artifactId>barricator-java-client</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Guarantees

- **Zero-latency evaluation.** `isEnabled(...)` is a synchronous `ConcurrentHashMap` lookup — never
  any network I/O on the call path.
- **Local evaluation.** The full environment ruleset is downloaded once and targeting runs in-process
  (an exact port of the backend engine, including MurmurHash3 rollout bucketing).
- **Real-time sync.** A daemon SSE thread applies deltas live; on disconnect it reconnects with
  exponential backoff + jitter and keeps serving the last cached state.
- **Resilient.** Bootstrap/stream/flush failures never throw into the host app — it degrades to cached
  state, then to your supplied default.
- **Async telemetry.** Evaluations are aggregated lock-free and flushed every 30s by a background
  worker.

## Usage

```java
try (BarricatorClient client = BarricatorClient.builder("sdk-srv-...")
        .baseUrl("https://app.barricator.com")
        .build()) {

    UserContext user = UserContext.builder("user-123")
            .email("user@enterprise.com")
            .country("US")
            .custom("plan", "pro")
            .build();

    if (client.isEnabled("premium-pricing", user)) {
        // ...
    }
    String theme = client.stringVariation("homepage-theme", user, "control");
}
```

## Build

```bash
./gradlew test
```

## Layout

| Package | Responsibility |
|---------|----------------|
| `com.barricator.client` | Public API: `BarricatorClient`, `BarricatorConfig`, `UserContext` |
| `…client.internal` | `EvaluationEngine`, `FlagStore`, `StreamSynchronizer`, `HttpTransport`, `MetricsBuffer`, `MurmurHash3` |
| `…client.model` | Wire-format ruleset models |
