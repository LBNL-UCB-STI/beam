package beam.playground.r5;

import com.conveyal.r5.api.ProfileResponse;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.transit.TransportNetwork;
import org.joda.time.DateTime;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.ZoneId;

/**
 * Created by ahmar.nadeem on 6/12/2017.
 */
public class TripPlannerTest {

    private static TripPlanner tripPlanner;

    /**
     * Input directory from where the system will read the osm data files.
     */
    private static final String INPUT_FILES_DIRECTORY = "C:\\Users\\ahmar_000\\beam\\input";

    /**
     * Output directory where the system will generate the network.dat and mapdb files.
     */
    private static final String OUTPUT_FILES_DIRECTORY = "C:\\Users\\ahmar_000\\beam\\output";

//    private double fromLatitude = 45.547716775429045;
//    private double fromLongitude = -122.68020629882812;
//    private double toLatitude = 45.554628830194815;
//    private double toLongitude = -122.66613006591795;
    private int bikeTrafficStress = 4;

    /**
     * NYC data.
     */
    private double fromLatitude = 40.689823;
    private double fromLongitude = -73.832639;
    private double toLatitude = 40.672770;
    private double toLongitude = -73.895639;

    @BeforeClass
    public static void beforeClass() {
        tripPlanner = new TripPlanner();
    }

    @Test
    public void testInit() throws Exception {

        String[] networkDirectory = {OUTPUT_FILES_DIRECTORY};
        TransportNetwork network = tripPlanner.init(networkDirectory);
        Assert.assertNotNull(network);

        boolean isWheelChair = false;
        boolean isInTrasit = false;

        ProfileRequest request = tripPlanner.buildRequest(isInTrasit, fromLatitude,
                fromLongitude, toLatitude, toLongitude,
                isWheelChair, bikeTrafficStress, DateTime.now(), DateTime.now(), network.getTimeZone());
        Assert.assertNotNull(request);
        ProfileResponse response = tripPlanner.calcRoute2(network, request);
        Assert.assertNotNull(response);
        tripPlanner.logProfileResponse(response);
    }

    @Test
    public void testBuildRequest(){
        boolean isWheelChair = false;
        boolean isInTrasit = false;
        ProfileRequest request = tripPlanner.buildRequest(isInTrasit, fromLatitude,
                fromLongitude, toLatitude, toLongitude,
                isWheelChair, bikeTrafficStress, DateTime.now(), DateTime.now(), ZoneId.systemDefault());
        Assert.assertNotNull(request);
        Assert.assertEquals(String.valueOf(request.fromLat), String.valueOf(fromLatitude));
        Assert.assertEquals(String.valueOf(request.fromLon), String.valueOf(fromLongitude));
    }
}
