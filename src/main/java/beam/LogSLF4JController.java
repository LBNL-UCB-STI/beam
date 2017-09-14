package beam;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import scala.collection.immutable.List;


public class LogSLF4JController {
    public void cutOff (List<String> classList,String loggerLevels){
        LoggerContext root = (LoggerContext) LoggerFactory.getILoggerFactory();
        scala.collection.Iterator classIterator = classList.iterator();
        while (classIterator.hasNext()) {
            String className = classIterator.next().toString();
            Logger rootLogger = root.getLogger(className);
            rootLogger.setLevel(Level.OFF);

            if("INFO".equals(loggerLevels)){
                //configures the root logger for INFO
                rootLogger.setLevel(Level.INFO);
            } else if("TRACE".equals(loggerLevels)){
                //configures the root logger for TRACE
                rootLogger.setLevel(Level.TRACE);
            } else if("WARN".equals(loggerLevels)){
                //configures the root logger for WARN
                rootLogger.setLevel(Level.WARN);
            } else if("ERROR".equals(loggerLevels)){
                //configures the root logger for ERROR
                rootLogger.setLevel(Level.ERROR);
            } else if("DEBUG".equals(loggerLevels)){
                //configures the root logger for DEBUG
                rootLogger.setLevel(Level.DEBUG);
            }
        }
    }
}

