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

/**
 * @author rwaraich
 */
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

                    if (line.contains("\"")) {
                        // line contains links starting and ending with quotes, furthermore separated by ',' (which is used as column separater as well)
                        // replace ',' in linkIds with ";" before split (to not interpret ',' as columns in column split)
                        String[] lineSplit = line.split("\"");
                        line = lineSplit[0] + lineSplit[1].replaceAll(",", ";") + lineSplit[2];
                    }

                    HashMap<String, String> attributes = new HashMap();

                    String[] columns = line.split(cvsSplitBy);

                    for (int i = 0; i < columns.length; i++) {

                        if (columns[i].contains(";")) {
                            columns[i] = columns[i].replaceAll(";", ","); // undo introduction of ';' and replace again with ','
                        }

                        attributes.put(columnLabelMapping.get(i), columns[i]);
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
