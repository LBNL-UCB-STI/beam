package beam.utils.gtfs;

import java.io.File;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Function utilities for GTFS data handling.
 * <p>
 * Created by sfeygin on 11/11/16.
 */
public class GtfsFunctions {
    static final Predicate<String> isFilePresent = s -> new File(s).exists();
    static final BiFunction<String, String, String> opNameToPath = (outputDir, opName) -> String.format("%s%s%s%s", outputDir, File.separator, opName, File.separator);
}
