package beam.analysis;

import org.matsim.api.core.v01.Coord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;

/**
 * @author rwaraich
 */
public class R5NetworkReader {
    private static final Logger log = LoggerFactory.getLogger(R5NetworkReader.class);

    public static HashMap<String, R5NetworkLink> readR5Network(String path, boolean withCounties) {
        HashMap<String, R5NetworkLink> r5NetworkLinks = new HashMap<>();
        String line;
        String cvsSplitBy = withCounties ? "," : "\t";
        int offSet = 0;

        try(BufferedReader br = new BufferedReader(new FileReader(path))) {

            br.readLine(); // skipping header line
            while ((line = br.readLine()) != null) {

                String[] columns = line.split(cvsSplitBy);
                R5NetworkLink r5NetworkLink = new R5NetworkLink(columns[offSet], new Coord(Double.parseDouble(columns[offSet + 1]), Double.parseDouble(columns[offSet + 2])), Double.parseDouble(columns[offSet + 3]), withCounties ? columns[offSet + 4] : "");
                r5NetworkLinks.put(r5NetworkLink.linkId, r5NetworkLink);
            }

        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
        return r5NetworkLinks;
    }

}
