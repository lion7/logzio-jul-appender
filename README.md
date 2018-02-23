[![Build Status](https://travis-ci.org/logzio/logzio-jul-appender.svg?branch=master)](https://travis-ci.org/logzio/logzio-jul-appender)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.logz.jul/logzio-jul-appender/badge.svg)](http://mvnrepository.com/artifact/io.logz.jul/logzio-jul-appender)

# Logzio Java Util Logging appender
This appender sends logs to your [Logz.io](http://logz.io) account, using non-blocking threading, bulks, and HTTPS encryption. 

### Technical Information
This appender uses [LogzioSender](https://github.com/logzio/logzio-java-sender) implementation. All logs are backed up to a local file system before being sent. Once you send a log, it will be enqueued in the buffer and 100% non-blocking. There is a background task that will handle the log shipment for you. This jar is an "Uber-Jar" that shades both BigQueue, Gson and Guava to avoid "dependency hell".

### Installation from maven
```xml
<dependency>
    <groupId>io.logz.jul</groupId>
    <artifactId>logzio-jul-appender</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Example Logging Configuration
```properties
.level=INFO
handlers=io.logz.jul.LogzioHandler
io.logz.jul.LogzioHandler.url=https://listener.logz.io:8071
io.logz.jul.LogzioHandler.token=jHXoNwdMHZplSBVkoZZjGoJzZfKKvxXI
io.logz.jul.LogzioHandler.type=myAwesomeType
io.logz.jul.LogzioHandler.level=WARNING
```

### Parameters
| Parameter          | Default                              | Explained  |
| ------------------ | ------------------------------------ | ----- |
| **token**              | *None*                                 | Your Logz.io token, which can be found under "settings" in your account, If the value begins with `$` then the appender looks for an environment variable with the name specified. For example: `$LOGZIO_TOKEN` will look for environment variable named `LOGZIO_TOKEN` |
| **type**               | *java*                                 | The [log type](http://support.logz.io/support/solutions/articles/6000103063-what-is-type-) for that appender, it must not contain spaces |
| **url**               | *https://listener.logz.io:8071*                                 | The url that the appender sends to.  If your account is in the EU you must use https://listener-eu.logz.io:8071 |
| **drainTimeoutSec**       | *5*                                    | How often the appender should drain the buffer (in seconds) |
| **fileSystemFullPercentThreshold** | *98*                                   | The percent of used file system space at which the appender will stop buffering. When we will reach that percentage, the file system in which the buffer rests will drop all new logs until the percentage of used space drops below that threshold. Set to -1 to never stop processing new logs |
| **bufferDir**          | *System.getProperty("java.io.tmpdir")* | Where the appender should store the buffer |
| **socketTimeout**       | *10 * 1000*                                    | The socket timeout during log shipment |
| **connectTimeout**       | *10 * 1000*                                    | The connection timeout during log shipment |
| **addHostname**       | *false*                                    | Optional. If true, then a field named 'hostname' will be added holding the host name of the machine. If from some reason there's no defined hostname, this field won't be added |
| **additionalFields**       | *None*                                    | Optional. Allows to add additional fields to the JSON message sent. The format is "fieldName1=fieldValue1;fieldName2=fieldValue2". You can optionally inject an environment variable value using the following format: "fieldName1=fieldValue1;fieldName2=$ENV_VAR_NAME". In that case, the environment variable should be the only value. In case the environment variable can't be resolved, the field will be omitted. |
| **debug**       | *false*                                    | Print some debug messages to stdout to help to diagnose issues |


### Code Example
```java
import java.util.Logger;

public class LogzioExample {

    public static void main(String[] args) {
        Logger logger = Logger.getLogger(LogzioExample.class.java);
        
        logger.info("Testing logz.io!");
        logger.warning("Winter is coming");
    }
}
```

### Release notes
 - 1.0.0 
   - Initial release   

### Contribution
 - Fork
 - Code
 - ```mvn test```
 - Issue a PR :)
