package beam.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AsyncAppender;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class LoggingUtil {
    /**
     * Creates a File based appender to create a log file in output dir
     * and adds into root logger to pu all the logs into output directory
     *
     * @param outputDirectory path of ths output directory
     */
    public static void createFileLogger(String outputDirectory) {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();

        PatternLayout layout = PatternLayout.newBuilder()
                .withConfiguration(config)
                .withPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n")
                .build();

        Appender appender = FileAppender.newBuilder()
                .setConfiguration(config)
                .withName("BeamFile")
                .withLayout(layout)
                .withFileName(String.format("%s/beam-log.out", outputDirectory))
                .build();

        appender.start();
        config.addAppender(appender);

        AppenderRef[] refs = new AppenderRef[] { AppenderRef.createAppenderRef(appender.getName(), null, null) };
        Appender asyncAppender = AsyncAppender.newBuilder()
                .setConfiguration(config)
                .setName("BeamAsync")
                .setAppenderRefs(refs)
                .build();

        asyncAppender.start();

        config.addLoggerAppender((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger(), asyncAppender);
        ctx.updateLoggers();
    }

    /**
     * Method returns the git commit hash or HEAD if git not present
     *
     * @return returns the git commit hash or HEAD if git not present
     */
    public static String getCommitHash() {
        Runtime runtime = Runtime.getRuntime();
        try {
            Process process = runtime.exec("git rev-parse HEAD");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            )) {
                return reader.readLine();
            }
        } catch (Exception e) {
            return "HEAD"; //for the env where git is not present
        }
    }
}
