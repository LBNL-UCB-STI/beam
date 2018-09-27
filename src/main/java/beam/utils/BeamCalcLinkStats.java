/* *********************************************************************** *
 * project: org.matsim.*
 * CalcLinkStats.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */
package beam.utils;


import org.matsim.analysis.CalcLinkStats;
import org.matsim.analysis.VolumesAnalyzer;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BeamCalcLinkStats {

    private final static Logger log = LoggerFactory.getLogger(CalcLinkStats.class);
    private static final int MIN = 0;
    private static final int MAX = 1;
    private static final int SUM = 2;
    private static final String[] statType = {"MIN", "MAX", "AVG"};
    private static final int NOF_STATS = 3;
    private final Map<Id<Link>, LinkData> linkData;
    private final int nofHours;
    private final Network network;
    private int count = 0;
    private double minCarSpeedInMetersPerSecond;

    @Inject
    public BeamCalcLinkStats(final Network network, double minCarSpeedInMetersPerSecond) {
        this.network = network;
        this.linkData = new ConcurrentHashMap<>();
        this.nofHours = 24;
        reset();
        this.minCarSpeedInMetersPerSecond = minCarSpeedInMetersPerSecond;
    }

    public void addData(final VolumesAnalyzer analyzer, final TravelTime ttimes) {
        this.count++;
        // TODO verify ttimes has hourly timeBin-Settings

        // go through all links
        for (Id<Link> linkId : this.linkData.keySet()) {

            // retrieve link from link ID
            Link link = this.network.getLinks().get(linkId);

            // get the volumes for the link ID from the analyzier
            double[] volumes = analyzer.getVolumesPerHourForLink(linkId);

            // get the destination container for the data from link data (could have gotten this through iterator right away)
            LinkData data = this.linkData.get(linkId);

            // prepare the sum variables (for volumes);
            long sumVolumes = 0; // daily (0-24) sum

            // go through all hours:
            for (int hour = 0; hour < this.nofHours; hour++) {

                // get travel time for hour
                double ttime = ttimes.getLinkTravelTime(link, hour * 3600, null, null);

                // add for daily sum:
                sumVolumes += volumes[hour];

                // the following has something to do with the fact that we are doing this for multiple iterations.  So there are variations.
                // this collects min and max.  There is, however, no good control over how many iterations this is collected.
                if (this.count == 1) {
                    data.volumes[MIN][hour] = volumes[hour];
                    data.volumes[MAX][hour] = volumes[hour];
                    data.ttimes[MIN][hour] = ttime;
                    data.ttimes[MAX][hour] = ttime;
                } else {
                    if (volumes[hour] < data.volumes[MIN][hour]) data.volumes[MIN][hour] = volumes[hour];
                    if (volumes[hour] > data.volumes[MAX][hour]) data.volumes[MAX][hour] = volumes[hour];
                    if (ttime < data.ttimes[MIN][hour]) data.ttimes[MIN][hour] = ttime;
                    if (ttime > data.ttimes[MAX][hour]) data.ttimes[MAX][hour] = ttime;
                }

                // this is the regular summing up for each hour
                data.volumes[SUM][hour] += volumes[hour];
                data.ttimes[SUM][hour] += volumes[hour] * ttime;
            }
            // dataVolumes[.][nofHours] are daily (0-24) values
            if (this.count == 1) {
                data.volumes[MIN][this.nofHours] = sumVolumes;
                data.volumes[SUM][this.nofHours] = sumVolumes;
                data.volumes[MAX][this.nofHours] = sumVolumes;
            } else {
                if (sumVolumes < data.volumes[MIN][this.nofHours]) data.volumes[MIN][this.nofHours] = sumVolumes;
                data.volumes[SUM][this.nofHours] += sumVolumes;
                if (sumVolumes > data.volumes[MAX][this.nofHours]) data.volumes[MAX][this.nofHours] = sumVolumes;
            }
        }
    }

    public void reset() {
        this.linkData.clear();
        this.count = 0;
        log.info(" resetting `count' to zero.  This info is here since we want to check when this" +
                " is happening during normal simulation runs.  kai, jan'11");

        // initialize our data-table
        for (Link link : this.network.getLinks().values()) {
            LinkData data = new LinkData(new double[NOF_STATS][this.nofHours + 1], new double[NOF_STATS][this.nofHours]);
            this.linkData.put(link.getId(), data);
        }

    }

    public void writeFile(final String filename) {
        BufferedWriter out = null;
        try {
            out = IOUtils.getBufferedWriter(filename);

            // write header
            out.write("link,from,to,hour,length,freespeed,capacity,stat,volume,traveltime");

            out.write("\n");

            // write data
            for (Map.Entry<Id<Link>, LinkData> entry : this.linkData.entrySet()) {

                for (int i = 0; i <= this.nofHours; i++) {
                    for (int j = MIN; j <= SUM; j++) {
                        Id<Link> linkId = entry.getKey();
                        LinkData data = entry.getValue();
                        Link link = this.network.getLinks().get(linkId);

                        out.write(linkId.toString());
                        out.write("," + link.getFromNode().getId().toString());
                        out.write("," + link.getToNode().getId().toString());

                        //WRITE HOUR
                        if (i < this.nofHours) {
                            out.write("," + Double.toString(i));
                        } else {
                            out.write("," + Double.toString(0) + " - " + Double.toString(this.nofHours));
                        }

                        out.write("," + Double.toString(link.getLength()));
                        out.write("," + Double.toString(link.getFreespeed()));
                        out.write("," + Double.toString(link.getCapacity()));

                        //WRITE STAT_TYPE
                        out.write("," + statType[j]);

                        //WRITE VOLUME
                        double volume = (j == SUM) ?
                                data.volumes[j][i] / this.count
                                :
                                data.volumes[j][i];
                        out.write("," + Double.toString(volume));

                        //WRITE TRAVELTIME
                        if(i < this.nofHours) {
                            Double travelTime;
                            if (j == MIN) {
                                travelTime = data.ttimes[MIN][i];

                            } else if (j == SUM) {
                                double ttimesMin = data.ttimes[MIN][i];
                                double ttimesSum = data.ttimes[SUM][i];
                                double volumesSum = data.volumes[SUM][i];

                                if (ttimesSum == 0) {
                                    travelTime = ttimesMin;
                                } else {
                                    if(volumesSum == 0) {
                                        travelTime = ttimesSum;
                                    } else {
                                        travelTime = ttimesSum / volumesSum;
                                    }
                                }
                            } else { // Always MAX
                                travelTime = data.ttimes[MAX][i];
                            }

                            if(travelTime > (link.getLength() / minCarSpeedInMetersPerSecond)) travelTime = link.getLength() / minCarSpeedInMetersPerSecond;

                            out.write("," + Double.toString(travelTime));
                        }

                        out.write("\n");
                    }
                }
            }

            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    log.warn("Could not close output-stream.", e);
                }
            }
        }
    }

    private static class LinkData {
        final double[][] volumes;
        final double[][] ttimes;

        LinkData(final double[][] linksVolumes, final double[][] linksTTimes) {
            this.volumes = linksVolumes.clone();
            this.ttimes = linksTTimes.clone();
        }
    }
}
