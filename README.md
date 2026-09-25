Mexc-Client
============
![GitHub](https://img.shields.io/github/license/osslabz/mexc-client)
![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/osslabz/mexc-client/build-on-push.yml?branch=dev&label=build&logo=git)
![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/osslabz/mexc-client/release.yml?branch=dev&label=perform-release&logo=semanticrelease)
[![Reproducible Builds](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/jvm-repo-rebuild/reproducible-central/master/content/net/osslabz/mexc-client/badge.json)](https://github.com/jvm-repo-rebuild/reproducible-central/blob/master/content/net/osslabz/mexc-client/README.md)
[![Maven Central](https://img.shields.io/maven-central/v/net.osslabz/mexc-client?label=Maven%20Central)](https://search.maven.org/artifact/net.osslabz/mexc-client)

Connects to [MEXC's Websocket API](https://www.mexc.com/mexc-api) and allows to subscribe to various data channels.

One author, one release (0.2.0, November 2024), and one known user, a trading bot of mine. There is no API stability
guarantee.


Features:
---------
- OHLC streaming for all supported intervals
- Order updates of your own account, with the listen key kept alive
- Robust connection lost detection with automatic re-connect and resubscribe to previously subscribed topics



QuickStart
---------

Maven
------

```xml

<dependency>
    <groupId>net.osslabz</groupId>
    <artifactId>mexc-client</artifactId>
    <version>0.2.0</version>
</dependency>
```

Snapshots
---------

Every push to `dev` publishes the next version as a `-SNAPSHOT` to Central's snapshot repository. Maven doesn't
search that repository by default, so a build that wants a snapshot declares it:

```xml
<repositories>
    <repository>
        <id>central-snapshots</id>
        <url>https://central.sonatype.com/repository/maven-snapshots/</url>
        <releases>
            <enabled>false</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```

Usage
------

Market data needs no API key:

```java
PublicMexcClient client = new PublicMexcClient();
client.subscribeToOhlc(new CurrencyPair("BTC", "USDT"), Interval.PT1M, ohlc -> log.debug("{}", ohlc));

client.unsubscribeFromOhlc(new CurrencyPair("BTC", "USDT"), Interval.PT1M);
client.close();
```

Order updates of your own account need an API key. The client keeps its listen key alive until `close()`, and a closed
client can't subscribe again:

```java
PrivateMexcClient client = new PrivateMexcClient(accessKey, secretKey);
client.subscribeToOrders(order -> log.debug("{}", order));

client.unsubscribeFromOrders();
client.close();
```

Logging
------
This project uses slf4j-api but doesn't package an implementation. This is up to the using application. For the
tests logback is backing slf4j as implementation, with a default configuration logging to STOUT.


Tests
------
`mvn verify` runs the offline tests against a local HTTP and websocket server, plus PMD, Checkstyle, SpotBugs, Error
Prone, Spotless and a JaCoCo coverage floor. Tests tagged `live` call the real MEXC API and are excluded from the build;
`UserDataClientTest` needs `MEXC_API_KEY` and `MEXC_SECRET_KEY`. Run them with
`mvn test -Dgroups=live -Dsurefire.excluded.groups=`.

Protobuf schemas
------
MEXC pushes websocket data as protobuf. `src/main/proto` holds a copy of the schemas from
[mexcdevelop/websocket-proto](https://github.com/mexcdevelop/websocket-proto) (Apache-2.0, license alongside) at commit
`0c9c4f35dd0fadc3a46a350e909a93379d81e811`, with `java_package` changed to `net.osslabz.mexc.proto`. The build
generates the Java classes with `protobuf-maven-plugin`. To update, copy the upstream files over the vendored ones,
reapply the package and the first-line source comment with the new commit, and run `mvn verify`.

Compatibility
------
mexc-client targets Java 17.