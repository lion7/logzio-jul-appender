package io.logz.jul;

import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.logz.sender.LogzioSender;
import io.logz.sender.SenderStatusReporter;
import io.logz.sender.com.google.gson.JsonObject;
import io.logz.sender.exceptions.LogzioParameterErrorException;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.logging.*;

public class LogzioHandler extends Handler {

    private static final String TIMESTAMP = "@timestamp";
    private static final String LOGLEVEL = "loglevel";
    private static final String MESSAGE = "message";
    private static final String LOGGER = "logger";
    private static final String THREAD = "thread";
    private static final String EXCEPTION = "exception";

    private static final Set<String> reservedFields = new HashSet<>(Arrays.asList(TIMESTAMP, LOGLEVEL, MESSAGE, LOGGER, THREAD, EXCEPTION));

    private static Logger statusLogger = Logger.getLogger("io.logz.jul");
    private static ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    private LogzioSender logzioSender;
    private ScheduledExecutorService tasksExecutor;
    private LogEnhancer enhancer;

    private final String logzioToken;
    private final String logzioType;
    private final int drainTimeoutSec;
    private final int fileSystemFullPercentThreshold;
    private final String bufferDir;
    private final String logzioUrl;
    private final int connectTimeout;
    private final int socketTimeout;
    private final boolean debug;
    private final boolean addHostname;
    private final int gcPersistedQueueFilesIntervalSeconds;

    private final Map<String, String> additionalFieldsMap = new HashMap<>();

    private static String getStringProperty(String name) {
        return LogManager.getLogManager().getProperty(LogzioHandler.class.getName() + "." + name);
    }

    private static Integer getIntegerProperty(String name) {
        String value = getStringProperty(name);
        return value != null ? Integer.parseInt(value) : null;
    }

    private static Boolean getBooleanProperty(String name) {
        String value = getStringProperty(name);
        return value != null ? Boolean.parseBoolean(value) : null;
    }

    private static Level getLevelProperty(String name, Level defaultValue) {
        String val = getStringProperty(name);
        if (val == null) {
            return defaultValue;
        }
        Level l = Level.parse(val.trim());
        return l != null ? l : defaultValue;
    }

    private static Filter getFilterProperty(String name, Filter defaultValue) {
        String val = getStringProperty(name);
        try {
            if (val != null) {
                Class<?> clz = ClassLoader.getSystemClassLoader().loadClass(val);
                return (Filter) clz.newInstance();
            }
        } catch (Exception ex) {
            // We got one of a variety of exceptions in creating the
            // class or creating an instance.
            // Drop through.
        }
        // We got an exception.  Return the defaultValue.
        return defaultValue;
    }

    private static LogEnhancer getEnhancerProperty(String name, LogEnhancer defaultValue) {
        String val = getStringProperty(name);
        try {
            if (val != null) {
                Class<?> clz = ClassLoader.getSystemClassLoader().loadClass(val);
                return (LogEnhancer) clz.newInstance();
            }
        } catch (Exception ex) {
            // We got one of a variety of exceptions in creating the
            // class or creating an instance.
            // Drop through.
        }
        // We got an exception.  Return the defaultValue.
        return defaultValue;
    }

    public LogzioHandler() {
        this(
                getStringProperty("url"),
                getStringProperty("token"),
                getStringProperty("type"),
                getIntegerProperty("drainTimeoutSec"),
                getIntegerProperty("fileSystemFullPercentThreshold"),
                getStringProperty("bufferDir"),
                getIntegerProperty("socketTimeout"),
                getIntegerProperty("connectTimeout"),
                getBooleanProperty("addHostname"),
                getStringProperty("additionalFields"),
                getBooleanProperty("debug"),
                getIntegerProperty("gcPersistedQueueFilesIntervalSeconds")
        );
        setLevel(getLevelProperty("level", Level.INFO));
        setFilter(getFilterProperty("filter", null));
        setEnhancer(getEnhancerProperty("enhancer", null));
        try {
            setEncoding(getStringProperty("encoding"));
        } catch (Exception ex) {
            try {
                setEncoding(null);
            } catch (Exception ex2) {
                // doing a setEncoding with null should always work.
                // assert false;
            }
        }
    }

    public LogzioHandler(String token) {
        this(null, token);
    }

    public LogzioHandler(String url, String token) {
        this(url, token, null, null, null, null, null, null, null, null, null, null);
    }

    LogzioHandler(String url, String token, String type, Integer drainTimeoutSec,
                  Integer fileSystemFullPercentThreshold, String bufferDir, Integer socketTimeout,
                  Integer connectTimeout, Boolean addHostname, String additionalFields,
                  Boolean debug, Integer gcPersistedQueueFilesIntervalSeconds) {
        super();
        this.logzioToken = getValueFromSystemEnvironmentIfNeeded(token);
        this.logzioUrl = getValueFromSystemEnvironmentIfNeeded(url);
        this.logzioType = type != null ? type : "java";
        this.drainTimeoutSec = drainTimeoutSec != null ? drainTimeoutSec : 5;
        this.fileSystemFullPercentThreshold = fileSystemFullPercentThreshold != null ? fileSystemFullPercentThreshold : 98;
        this.bufferDir = bufferDir;
        this.socketTimeout = socketTimeout != null ? socketTimeout : 10 * 1000;
        this.connectTimeout = connectTimeout != null ? connectTimeout : 10 * 1000;
        this.debug = debug != null ? debug : false;
        this.addHostname = addHostname != null ? addHostname : false;
        this.gcPersistedQueueFilesIntervalSeconds = gcPersistedQueueFilesIntervalSeconds != null ? gcPersistedQueueFilesIntervalSeconds : 30;
        if (additionalFields != null) {
            Splitter.on(';').omitEmptyStrings().withKeyValueSeparator('=').split(additionalFields).forEach((k, v) -> {
                if (reservedFields.contains(k)) {
                    statusLogger.log(Level.WARNING, "The field name '" + k
                            + "' defined in additionalFields configuration can't be used since it's a reserved field name. This field will not be added to the outgoing log messages");
                } else {
                    String value = getValueFromSystemEnvironmentIfNeeded(v);
                    if (value != null) {
                        additionalFieldsMap.put(k, value);
                    }
                }
            });
            statusLogger.info("The additional fields that would be added: " + additionalFieldsMap.toString());
        }
        start();
    }

    public void start() {
        if (!(fileSystemFullPercentThreshold >= 1 && fileSystemFullPercentThreshold <= 100)) {
            if (fileSystemFullPercentThreshold != -1) {
                statusLogger.log(Level.SEVERE, "fileSystemFullPercentThreshold should be a number between 1 and 100, or -1");
                return;
            }
        }
        try {
            if (addHostname) {
                String hostname = InetAddress.getLocalHost().getHostName();
                additionalFieldsMap.put("hostname", hostname);
            }
        } catch (UnknownHostException e) {
            statusLogger.log(Level.WARNING, "The configuration addHostName was specified but the host could not be resolved, thus the field 'hostname' will not be added", e);
        }
        String bufferDirPath;
        if (bufferDir != null) {
            bufferDirPath = bufferDir;
            File bufferFile = new File(bufferDirPath);
            if (bufferFile.exists()) {
                if (!bufferFile.canWrite()) {
                    statusLogger.log(Level.SEVERE, "We cant write to your bufferDir location: " + bufferFile.getAbsolutePath());
                    return;
                }
            } else {
                if (!bufferFile.mkdirs()) {
                    statusLogger.log(Level.SEVERE, "We cant create your bufferDir location: " + bufferFile.getAbsolutePath());
                    return;
                }
            }
        } else {
            bufferDirPath = System.getProperty("java.io.tmpdir") + File.separator + "logzio-jul-buffer";
        }
        File bufferDirFile = new File(bufferDirPath, logzioType);

        try {
            ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat(this.getClass().getSimpleName() + "-%d").setDaemon(true).build();
            tasksExecutor = Executors.newScheduledThreadPool(2, threadFactory);
            logzioSender = LogzioSender.getOrCreateSenderByType(logzioToken, logzioType, drainTimeoutSec, fileSystemFullPercentThreshold,
                    bufferDirFile, logzioUrl, socketTimeout, connectTimeout, debug,
                    new StatusReporter(), tasksExecutor, gcPersistedQueueFilesIntervalSeconds);
            logzioSender.start();
        } catch (LogzioParameterErrorException e) {
            statusLogger.log(Level.SEVERE, "Some of the configuration parameters of logz.io is wrong: " + e.getMessage(), e);
        }
    }

    public LogEnhancer getEnhancer() {
        return enhancer;
    }

    public void setEnhancer(LogEnhancer enhancer) {
        this.enhancer = enhancer;
    }

    @Override
    public void flush() {
        logzioSender.drainQueueAndSend();
    }

    @Override
    public void close() {
        flush();
        if (logzioSender != null)
            logzioSender.stop();
        if (tasksExecutor != null)
            tasksExecutor.shutdownNow();
    }

    @Override
    public void publish(LogRecord record) {
        if (!isLoggable(record)) {
            return;
        }
        if (!record.getLoggerName().equals(statusLogger.getName()) && !record.getLoggerName().contains("io.logz.sender")) {
            logzioSender.send(formatMessageAsJson(record));
        }
    }

    private JsonObject formatMessageAsJson(LogRecord record) {
        String threadName = threadMXBean.getThreadInfo(record.getThreadID()).getThreadName();

        JsonObject logMessage = new JsonObject();

        logMessage.addProperty(TIMESTAMP, new Date(record.getMillis()).toInstant().toString());
        logMessage.addProperty(LOGLEVEL, record.getLevel().toString());
        logMessage.addProperty(MESSAGE, record.getMessage());
        logMessage.addProperty(LOGGER, record.getLoggerName());
        logMessage.addProperty(THREAD, threadName);
        Throwable throwable = record.getThrown();
        if (throwable != null) {
            logMessage.addProperty(EXCEPTION, Throwables.getStackTraceAsString(throwable));
        }

        if (additionalFieldsMap != null) {
            additionalFieldsMap.forEach(logMessage::addProperty);
        }

        if (enhancer != null) {
            enhancer.enhance(logMessage);
        }

        return logMessage;
    }

    private static String getValueFromSystemEnvironmentIfNeeded(String value) {
        if (value == null)
            return value;
        if (value.startsWith("$")) {
            return System.getenv(value.replace("$", ""));
        }
        return value;
    }

    private class StatusReporter implements SenderStatusReporter {

        @Override
        public void error(String msg) {
            statusLogger.log(Level.SEVERE, msg);
        }

        @Override
        public void error(String msg, Throwable e) {
            statusLogger.log(Level.SEVERE, msg, e);
        }

        @Override
        public void warning(String msg) {
            statusLogger.log(Level.WARNING, msg);
        }

        @Override
        public void warning(String msg, Throwable e) {
            statusLogger.log(Level.WARNING, msg, e);
        }

        @Override
        public void info(String msg) {
            statusLogger.log(Level.INFO, msg);
        }

        @Override
        public void info(String msg, Throwable e) {
            statusLogger.log(Level.INFO, msg, e);
        }
    }
}