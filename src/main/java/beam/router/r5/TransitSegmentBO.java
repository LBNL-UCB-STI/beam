package beam.router.r5;

import org.matsim.api.core.v01.Coord;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by ahmar.nadeem on 7/18/2017.
 */
public class TransitSegmentBO {

    private String activeLinkId;
    private Coord activeCoord;
    private Long activeTime;

    public String getActiveLinkId() {
        return activeLinkId;
    }

    public void setActiveLinkId(String activeLinkId) {
        this.activeLinkId = activeLinkId;
    }

    public Coord getActiveCoord() {
        return activeCoord;
    }

    public void setActiveCoord(Coord activeCoord) {
        this.activeCoord = activeCoord;
    }

    public Long getActiveTime() {
        return activeTime;
    }

    public void setActiveTime(Long activeTime) {
        this.activeTime = activeTime;
    }
}
