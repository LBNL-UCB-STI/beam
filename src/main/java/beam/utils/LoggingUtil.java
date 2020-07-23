package beam.utils;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.ContextInitializer;
import ch.qos.logback.core.joran.spi.JoranException;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.SubstituteLoggerFactory;

import java.io.IOException;
import java.io.InputStream;

public class LoggingUtil {

    public static final String LOG_OUTPUT_DIRECTORY_KEY = "log-path";

    public static void initLogger(String outputDirectory, boolean keepConsoleAppenderOn) throws JoranException, IOException {
        String logbackConfigFile = System.getProperty(ContextInitializer.CONFIG_FILE_PROPERTY);

        if (logbackConfigFile != null) {
            // https://logback.qos.ch/faq.html#sharedConfiguration
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

            // THIS IS PURE FOR THE TEST BECAUSE THERE IS LEAKAGE
            // https://imgur.com/a/v9qiASC
            // ################################################
            if (LoggerFactory.getILoggerFactory() instanceof SubstituteLoggerFactory) {
                SubstituteLoggerFactory factory = (SubstituteLoggerFactory) LoggerFactory.getILoggerFactory();
                factory.clear();
            }
            // ################################################

            InputStream resourceAsStream = context.getClass().getClassLoader().getResourceAsStream(logbackConfigFile);
            if (resourceAsStream != null) {
                try {
                    context.reset();
                    context.putProperty(LOG_OUTPUT_DIRECTORY_KEY, outputDirectory);

                    JoranConfigurator jc = new JoranConfigurator();
                    jc.setContext(context);
                    jc.doConfigure(resourceAsStream);
                    if (!keepConsoleAppenderOn) {
                        context.getLoggerList().forEach(Logger::detachAndStopAllAppenders);
                    }
                } finally {
                    resourceAsStream.close();
                }
            } else {
                System.err.println(String.format("Could not find resource '%s' in classpath. Logger is not properly configured!", logbackConfigFile));
            }
        }
    }
}