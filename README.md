Mexc-Client
============
![GitHub](https://img.shields.io/github/license/osslabz/mexc-client)
![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/osslabz/mexc-client/build-on-push.yml?branch=dev&label=build&logo=git)
![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/osslabz/mexc-client/release.yml?branch=dev&label=perform-release&logo=semanticrelease)
[![Reproducible Builds](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/jvm-repo-rebuild/reproducible-central/master/content/net/osslabz/mexc-client/badge.json)](https://github.com/jvm-repo-rebuild/reproducible-central/blob/master/content/net/osslabz/mexc-client/README.md)
[![Maven Central](https://img.shields.io/maven-central/v/net.osslabz/mexc-client?label=Maven%20Central)](https://search.maven.org/artifact/net.osslabz/mexc-client)

Connects to [MEXC's Websocket API](https://www.mexc.com/mexc-api) and allows to subscribe to various data channels.

One author, one release (0.2.0, November 2024), and one known user, a trading bot of mine. The only tests hit the live
MEXC API and are excluded from the build, so there is no automated coverage and no API stability guarantee.


Features:
---------
- OHLC streaming for all supported intervals
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

```java

MexcClient client = new MexcClient();
client.subscribe(new CurrencyPair("BTC", "USDT"), Interval.PT1M, ohlc -> {
    log.debug("{}", ohlc);
});


client.unsubscribe(new CurrencyPair("BTC", "USDT"), Interval.PT1M);
client.close();
```        

Logging
------
This project uses slf4j-api but doesn't package an implementation. This is up to the using application. For the
tests logback is backing slf4j as implementation, with a default configuration logging to STOUT.


Compatibility
------
mexc-client targets Java 17.