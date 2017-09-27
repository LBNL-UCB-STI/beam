package beam.analysis;

import org.matsim.api.core.v01.Coord;

/**
 * @author rwaraich
 */
public class R5NetworkLink {
    String linkId;
    Coord coord;
    double lengthInMeters;
    String countyName;

    public R5NetworkLink(String linkId, Coord coord, double lengthInMeters, String countyName) {
        this.linkId = linkId;
        this.coord = coord;
        this.lengthInMeters = lengthInMeters;
        this.countyName = countyName;
    }
}