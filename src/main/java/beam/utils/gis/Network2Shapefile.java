package beam.utils.gis;

import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkReaderMatsimV2;
import org.matsim.utils.gis.matsim2esri.network.Links2ESRIShape;

public class Network2Shapefile {
    public static void main(String[] args) {
        /* input:
         * [1] matsim network file you want to convert
         * [2] CRS of network
         * [3] output network filename
         */
        Network n = NetworkUtils.createNetwork();
        NetworkReaderMatsimV2 reader = new NetworkReaderMatsimV2(n);
        reader.readFile(args[0]);
        Links2ESRIShape links2ESRIShape = new Links2ESRIShape(n, args[1], args[2]);
        links2ESRIShape.write();
    }
}
