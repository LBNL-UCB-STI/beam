package beam.utils;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import org.slf4j.LoggerFactory;

public class LoggingUtil {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(LoggingUtil.class);

    private static boolean keepConsoleAppenderOn = true;
    /**
     * Creates a File based appender to create a log file in output dir
     * and adds into root logger to put all the logs into output directory
     *
     * @param outputDirectory path of ths output directory
     */
    public static Logger createFileLogger(String outputDirectory, boolean keepConsoleAppenderOn) {
        LoggingUtil.keepConsoleAppenderOn = keepConsoleAppenderOn;
        final LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();

        if(!keepConsoleAppenderOn)
            lc.getLoggerList().forEach(Logger::detachAndStopAllAppenders);

        final PatternLayoutEncoder ple = new PatternLayoutEncoder();
        ple.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        ple.setContext(lc);
        ple.start();

        final FileAppender<ILoggingEvent> fileAppender = new FileAppender<>();
        fileAppender.setFile(String.format("%s/beamLog.out", outputDirectory));
        fileAppender.setEncoder(ple);
        fileAppender.setContext(lc);
        fileAppender.start();

        final Logger rootLogger = lc.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(fileAppender);
        rootLogger.setAdditive(true); /* set to true if root should log too */

        return rootLogger;
    }

    public static void logToFile(String msg) {
        if(!keepConsoleAppenderOn)
            log.info(msg);
    }
}