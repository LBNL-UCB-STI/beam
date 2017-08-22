package beam.physsim.model;

import java.io.Serializable;

/**
 * Created by salma_000 on 8/18/2017.
 */
public class LinkTime implements Serializable{
    private int linkId;
    private long time;

    public LinkTime(int linkId, long time) {
        this.linkId = linkId;
        this.time = time;
    }

    public int getLinkId() {
        return linkId;
    }

    public long getTime() {
        return time;
    }
}
