package beam.utils;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
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
                .withFileName(String.format("%s/beam-log.out", outputDirectory))
                .build();

        appender.start();
        config.addAppender(appender);

        AppenderRef ref = AppenderRef.createAppenderRef("BeamFile", null, null);
        AppenderRef[] refs = new AppenderRef[] { ref };

        LoggerConfig loggerConfig = LoggerConfig
                .createLogger(false, Level.INFO, "beam", "true", refs, null, config, null);
        loggerConfig.addAppender(appender, Level.INFO, null);
        config.addLogger("beam", loggerConfig);
        ctx.updateLoggers();
    }
}
