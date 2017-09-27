package beam.analysis;

import org.matsim.api.core.v01.Coord;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;

public class R5NetworkReader {

    public static HashMap<Integer, R5NetworkLink> readR5Network(String path) {
        HashMap<Integer, R5NetworkLink> r5NetworkLinks = new HashMap<Integer, R5NetworkLink>();
        String csvFile = path;
        BufferedReader br = null;
        String line = "";
        String cvsSplitBy = "\t";

        try {
            br = new BufferedReader(new FileReader(csvFile));
            while ((line = br.readLine()) != null) {

                String[] columns = line.split(cvsSplitBy);
                R5NetworkLink r5NetworkLink = new R5NetworkLink(Integer.parseInt(columns[0]), new Coord(Double.parseDouble(columns[1]), Double.parseDouble(columns[2])), Double.parseDouble(columns[3]));
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
