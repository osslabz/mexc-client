Mexc-Client
============
![GitHub](https://img.shields.io/github/license/osslabz/mexc-client)
![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/osslabz/mexc-client/maven-build.yml?branch=main)
[![Maven Central](https://img.shields.io/maven-central/v/net.osslabz/mexc-client?label=Maven%20Central)](https://search.maven.org/artifact/net.osslabz/mexc-client)

Connects to [MEXC's Websocket API](https://www.mexc.com/mexc-api) and allows to subscrive to various data channels.


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
    <version>0.0.1</version>
</dependency>
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