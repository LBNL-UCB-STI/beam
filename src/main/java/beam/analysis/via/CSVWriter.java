package beam.analysis.via;

import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;

public class CSVWriter {
    private final Logger log = LoggerFactory.getLogger(CSVWriter.class);

    private final BufferedWriter out;

    public CSVWriter(String outputFileName) {
        this.out = IOUtils.getBufferedWriter(outputFileName);
    }

    public BufferedWriter getBufferedWriter() {
        return out;
    }

    protected void flushBuffer() {
        try {
            this.out.flush();
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

    public void closeFile() {
        try {
            this.out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
