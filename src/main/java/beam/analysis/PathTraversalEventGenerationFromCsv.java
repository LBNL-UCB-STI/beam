package beam.analysis;

import beam.agentsim.events.PathTraversalEvent;
import beam.sim.config.BeamConfig;
import beam.utils.DebugLib;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.events.handler.BasicEventHandler;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;

public class PathTraversalEventGenerationFromCsv {

    public static void main(String[] args) {

    }

    public static void generatePathTraversalEventsAndForwardToHandler(String filePath, PathTraversalSpatialTemporalTableGenerator handler) {
        String csvFile = filePath;
        BufferedReader br = null;
        String line = "";

        try {
            br = new BufferedReader(new FileReader(csvFile));
            line = br.readLine(); // skipping header line


            String cvsSplitBy = ",";
            String[] columnLabels = line.split(cvsSplitBy);
            HashMap<Integer, String> columnLabelMapping = new HashMap();
            int columnIndexTime = 0;
            for (int i = 0; i < columnLabels.length; i++) {
                columnLabelMapping.put(i, columnLabels[i]);

                if (columnLabels[i].equalsIgnoreCase("time")) {
                    columnIndexTime = i;
                }
            }

            while ((line = br.readLine()) != null) {

                if (line.contains("PathTraversal")) {


                    String[] lineSplit = line.split("\"");

                    if (lineSplit.length == 3) {
                        line = lineSplit[0] + lineSplit[1].replaceAll(",", ";") + lineSplit[2];
                    }


                    HashMap<String, String> attributes = new HashMap();

                    String[] columns = line.split(cvsSplitBy);



                    for (int i = 0; i < columnLabels.length; i++) {
                        if (i<=18){ // there was a bug in one of the csv's (contained "," after vehicle id)
                            attributes.put(columnLabelMapping.get(i), columns[i]);
                        }
                    }


                    handler.handleEvent(Double.parseDouble(columns[columnIndexTime]), attributes);
                    DebugLib.emptyFunctionForSettingBreakPoint();
                    //   String[] columns = line.split(cvsSplitBy);
                    //   R5NetworkLink r5NetworkLink = new R5NetworkLink(columns[offSet+0], new Coord(Double.parseDouble(columns[offSet+1]), Double.parseDouble(columns[offSet+2])), Double.parseDouble(columns[offSet+3]),withCounties?columns[5]:"");
                    //   r5NetworkLinks.put(r5NetworkLink.linkId, r5NetworkLink);
                }
            }

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }


}
