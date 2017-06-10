package beam.playground.r5;

import beam.playground.r5.NetworkBuilder;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.BeforeClass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Created by ahmar.nadeem on 6/4/2017.
 */
public class NetworkBuilderTest {

    private static NetworkBuilder builderMain;

    /** Input directory from where the system will read the osm data files. */
    private static final String INPUT_FILES_DIRECTORY = "C:\\Users\\ahmar_000\\beam\\input";

    /** Output directory where the system will generate the network.dat and mapdb files. */
    private static final String OUTPUT_FILES_DIRECTORY = "C:\\Users\\ahmar_000\\beam\\output";

    @BeforeClass
    public static void beforeClass(){
        builderMain = new NetworkBuilder();
    }

    @Ignore
    @Test
    public void testNetworkBuilder() throws IOException {

        Files.createDirectories(Paths.get(OUTPUT_FILES_DIRECTORY));
        builderMain.buildNetwork(INPUT_FILES_DIRECTORY, OUTPUT_FILES_DIRECTORY);
        Assert.assertTrue(Files.exists(Paths.get(OUTPUT_FILES_DIRECTORY, "network.dat")));
    }

//    @Ignore
    @Test
    public void testNetworkBuilderWhenNoCustomDirectoriesProvided() throws IOException {

        builderMain.buildNetwork(null, null);
        Assert.assertTrue(Files.exists(Paths.get(OUTPUT_FILES_DIRECTORY, "network.dat")));
    }
}
