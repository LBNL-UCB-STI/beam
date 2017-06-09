package beam.network;

import beam.playground.r5.NetworkBuilder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.BeforeClass;

/**
 * Created by ahmar.nadeem on 6/4/2017.
 */
public class NetworkBuilderTest {

    private static NetworkBuilder builderMain;

    private static final String DIRECTORY_PATH = "src/test/resources/router";

    @BeforeClass
    public static void beforeClass(){
        builderMain = new NetworkBuilder();
    }

    @Test
    @Ignore
    public void testNetworkBuilder(){
        builderMain.buildNetwork(DIRECTORY_PATH);
    }
}
