package beam.analysis.via;

import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.io.UncheckedIOException;

import java.io.BufferedWriter;
import java.io.IOException;

public class CSVWriter {

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
            e.printStackTrace();
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
