package beam.analysis;

import org.matsim.api.core.v01.Coord;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;

/**
 * @author rwaraich
 */
public class R5NetworkReader {

    public static HashMap<String, R5NetworkLink> readR5Network(String path, boolean withCounties) {
        HashMap<String, R5NetworkLink> r5NetworkLinks = new HashMap<String, R5NetworkLink>();
        String csvFile = path;
        BufferedReader br = null;
        String line = "";
        String cvsSplitBy = withCounties ? "," : "\t";
        int offSet = withCounties ? 1 : 0;

        try {
            br = new BufferedReader(new FileReader(csvFile));
            line = br.readLine(); // skipping header line
            while ((line = br.readLine()) != null) {

                String[] columns = line.split(cvsSplitBy);
                R5NetworkLink r5NetworkLink = new R5NetworkLink(columns[offSet + 0], new Coord(Double.parseDouble(columns[offSet + 1]), Double.parseDouble(columns[offSet + 2])), Double.parseDouble(columns[offSet + 3]), withCounties ? columns[5] : "");
                r5NetworkLinks.put(r5NetworkLink.linkId, r5NetworkLink);
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
        return r5NetworkLinks;
    }

}
