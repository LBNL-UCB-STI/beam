package beam.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * @author rwaraich
 */
public class PathTraversalEventGenerationFromCsv {
    private static final Logger log = LoggerFactory.getLogger(PathTraversalEventGenerationFromCsv.class);

    public static void main(String[] args) {

    }

    public static void generatePathTraversalEventsAndForwardToHandler(String filePath, PathTraversalSpatialTemporalTableGenerator handler) {

        try(BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            // skipping header line
            String line = br.readLine();
            
            String cvsSplitBy = ",";
            String[] columnLabels = line.split(cvsSplitBy);
            Map<Integer, String> columnLabelMapping = new HashMap<>();
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

                    Map<String, String> attributes = new HashMap<>();

                    String[] columns = line.split(cvsSplitBy);

                    for (int i = 0; i < columns.length; i++) {

                        if (columns[i].contains(";")) {
                            columns[i] = columns[i].replaceAll(";", ","); // undo introduction of ';' and replace again with ','
                        }

                        attributes.put(columnLabelMapping.get(i), columns[i]);
                    }

                    handler.handleEvent(Double.parseDouble(columns[columnIndexTime]), attributes);
                    //   String[] columns = line.split(cvsSplitBy);
                    //   R5NetworkLink r5NetworkLink = new R5NetworkLink(columns[offSet+0], new Coord(Double.parseDouble(columns[offSet+1]), Double.parseDouble(columns[offSet+2])), Double.parseDouble(columns[offSet+3]),withCounties?columns[5]:"");
                    //   r5NetworkLinks.put(r5NetworkLink.linkId, r5NetworkLink);
                }
            }
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }
}
