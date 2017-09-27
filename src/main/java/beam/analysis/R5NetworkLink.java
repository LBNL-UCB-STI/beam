package beam.analysis;

import org.matsim.api.core.v01.Coord;

public class R5NetworkLink {
    Integer linkId;
    Coord coord;
    double lengthInMeters;

    public R5NetworkLink(Integer linkId, Coord coord, double lengthInMeters) {
        this.linkId = linkId;
        this.coord = coord;
        this.lengthInMeters = lengthInMeters;
    }
}