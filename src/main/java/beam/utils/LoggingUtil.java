package beam.utils;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class LoggingUtil {
    /**
     * Creates a File based appender to create a log file in output dir
     * and adds into root logger to put all the logs into output directory
     *
     * @param outputDirectory path of ths output directory
     */
    public static void createFileLogger(String outputDirectory) {
        final LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();

        final PatternLayoutEncoder ple = new PatternLayoutEncoder();
        ple.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        ple.setContext(lc);
        ple.start();

        final FileAppender<ILoggingEvent> fileAppender = new FileAppender<>();
        fileAppender.setFile(String.format("%s/beam-log.out", outputDirectory));
        fileAppender.setEncoder(ple);
        fileAppender.setContext(lc);
        fileAppender.start();

        final Logger rootLogger = lc.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(fileAppender);
        rootLogger.setAdditive(true); /* set to true if root should log too */
    }

    /**
     * Method returns the git commit hash or HEAD if git not present
     * git rev-parse --abbrev-ref HEAD
     *
     * @return returns the git commit hash or HEAD if git not present
     */
    public static String getCommitHash() {
        String resp = readCommandResponse("git rev-parse HEAD");
        if (resp != null) return resp;
        return "HEAD"; //for the env where git is not present
    }

    /**
     * Method returns the git branch or master if git not present
     *
     * @return returns the current git branch
     */
    public static String getBranch() {
        String resp = readCommandResponse("git rev-parse --abbrev-ref HEAD");
        if (resp != null) return resp;
        return "master"; //for the env where git is not present
    }

    private static String readCommandResponse(String command) {
        Runtime runtime = Runtime.getRuntime();
        try {
            Process process = runtime.exec(command);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            )) {
                return reader.readLine();
            }
        } catch (Exception e) {
            return null; //for the env where command is not recognized
        }
    }
}
