package beam.utils;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import org.slf4j.LoggerFactory;

public class LoggingUtil {
    private static boolean keepConsoleAppenderOn = true;

    public static void initLogger(String outputDirectory, boolean keepConsoleAppenderOn) throws JoranException {
        LoggingUtil.keepConsoleAppenderOn = keepConsoleAppenderOn;

        // https://logback.qos.ch/faq.html#sharedConfiguration
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        JoranConfigurator jc = new JoranConfigurator();
        jc.setContext(context);
        context.reset(); // override default configuration
        // inject the path `log-path` property of the LoggerContext
        context.putProperty("log-path", outputDirectory);
        jc.doConfigure(context.getClass().getClassLoader().getResourceAsStream("logback.xml"));

        if(!keepConsoleAppenderOn)
            context.getLoggerList().forEach(Logger::detachAndStopAllAppenders);
    }
}