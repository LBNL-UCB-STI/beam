package beam.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;

public class LoggingUtil {
    public static void createFileLogger(String outputDirectory) {
        LoggerContext  ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();

        PatternLayout layout = PatternLayout.newBuilder()
                .withConfiguration(config)
                .withPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n")
                .build();

        Appender appender = FileAppender.newBuilder()
                .setConfiguration(config)
                .withName("BeamFile")
                .withLayout(layout)
                .withFileName(String.format("%s/beam.log", outputDirectory))
                .build();

        appender.start();
        config.addAppender(appender);
    }
}
