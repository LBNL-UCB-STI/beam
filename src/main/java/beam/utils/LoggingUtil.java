package beam.utils;

//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.core.Appender;
//import org.apache.logging.log4j.core.LoggerContext;
//import org.apache.logging.log4j.core.appender.AsyncAppender;
//import org.apache.logging.log4j.core.appender.FileAppender;
//import org.apache.logging.log4j.core.config.AppenderRef;
//import org.apache.logging.log4j.core.config.Configuration;
//import org.apache.logging.log4j.core.layout.PatternLayout;

import ch.qos.logback.classic.LoggerContext;
import org.apache.log4j.LogManager;
import org.slf4j.LoggerFactory;

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
//        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
//        Configuration config = ctx.getConfiguration();
//
//        PatternLayout layout = PatternLayout.newBuilder()
//                .withConfiguration(config)
//                .withPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n")
//                .build();
//
//        Appender appender = FileAppender.newBuilder()
//                .setConfiguration(config)
//                .withName("BeamFile")
//                .withLayout(layout)
//                .withFileName(String.format("%s/beam-log.out", outputDirectory))
//                .build();
//
//        appender.start();
//        config.addAppender(appender);
//
//        AppenderRef[] refs = new AppenderRef[]{AppenderRef.createAppenderRef(appender.getName(), null, null)};
//        Appender asyncAppender = AsyncAppender.newBuilder()
//                .setConfiguration(config)
//                .setName("BeamAsync")
//                .setAppenderRefs(refs)
//                .build();
//
//        asyncAppender.start();
//
//        config.addLoggerAppender((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger(), asyncAppender);
//        ctx.updateLoggers();
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
