package beam.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Created by ahmar.nadeem on 6/7/2017.
 */
public final class DefaultPathUtil {

    /**
     * This is a helper function to get the default input directory if not provided to the system.
     *
     * @return
     * @throws IOException
     */
    public static Path getDefaultInputDirectory() throws IOException {
        String inputDirectory = System.getenv("INPUT_DIR");
        if (inputDirectory != null) {
            return Paths.get(inputDirectory);
        }

        Path path = Paths.get(System.getProperty("user.home"), "beam", "input");
        Files.createDirectories(path);
        return path;
    }

    /**
     * This is a helper function to get the default output directory if not provided to the system.
     * It is however important to note that the output directory may not be symmetrical to the input directory.
     * It means maybe the user has created a system variable for either of the input or output directory.
     * The best approach is to create a system variable for both input and output directories.
     *
     * @return
     * @throws IOException
     */
    public static Path getDefaultOutputDirectory() throws IOException {
        String outputDirectory = System.getenv("OUTPUT_DIR");
        if (outputDirectory != null) {
            return Paths.get(outputDirectory);
        }

        Path path = Paths.get(System.getProperty("user.home"), "beam", "output");
        Files.createDirectories(path);
        return path;
    }
}
