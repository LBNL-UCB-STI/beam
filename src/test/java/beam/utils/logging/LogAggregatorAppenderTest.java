package beam.utils.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

public class LogAggregatorAppenderTest {

    private static final Logger logger = (Logger) LoggerFactory.getLogger("someTest");

    @Before
    public void setup() {
        logger.setLevel(Level.ALL);
    }

    @Test
    public void loadWarnings() throws Exception {
        try (Stream<String> lines = getLines("/logaggregator/warn.log")) {
            lines.forEach(logger::warn);
        }
    }

    @Test
    public void loadErrors() throws Exception {
        try (Stream<String> lines = getLines("/logaggregator/error.log")) {
            lines.forEach(logger::error);
        }
    }

    @Test
    public void loadInfo() throws Exception {
        try (Stream<String> lines = getLines("/logaggregator/info.log")) {
            lines.forEach(logger::info);
        }
    }

    private Stream<String> getLines(final String fileName) throws Exception {
        URL url = getClass().getResource(fileName);
        Path path = Paths.get(url.toURI());
        return Files.lines(path);
    }

    @After
    public void tearDown() {
        logger.info("STOP");
        logger.detachAndStopAllAppenders(); // TODO: is not working
    }

}
