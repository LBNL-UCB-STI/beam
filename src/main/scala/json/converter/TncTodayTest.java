package json.converter;

import beam.analysis.spatialtemporalTAZ.SpatialTemporalTAZVizDataWriter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.*;

public class TncTodayTest {

    public static void main(String[] args) throws Exception {
        System.out.println("Reading input file");
        List<List<String>> lines = readCsv("D:\\beam_test\\input1.txt");

        SpatialTemporalTAZVizDataWriter writer = new SpatialTemporalTAZVizDataWriter("D:\\beam_test\\taz_boudaries.json", "Car");
        System.out.println("Adding points");
        for(List<String> line : lines){
            Long hour = Long.parseLong(line.get(1));
            String cat = line.get(2);
            Double value = Double.parseDouble(line.get(3));
            Double xcoord = Double.parseDouble(line.get(7));
            Double ycoord = Double.parseDouble(line.get(8));
            writer.addDataPoint(cat, xcoord, ycoord, hour * (3600), value);
        }
        writer.saveToDisk("D:\\beam_test\\stats_out.json", "D:\\beam_test\\totals_out.json");
    }

    static List<List<String>>  readCsv(String fileName) throws Exception {
        List<List<String>> lines = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new FileReader(fileName));

        try{
            boolean skipFirst = false;
            String line = null;
//            Scanner scanner = null;
            while((line = reader.readLine()) != null){
                if(!skipFirst){
                    skipFirst = true;
                    continue;
                }
//                scanner = new Scanner(line);
//                scanner.useDelimiter("\t");
                String[] values = line.split("\t");
                // this adds the currently parsed line to the 2-dimensional string array
                lines.add(Arrays.asList(values));
            }
            reader.close();
        }catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        return lines;
    }
}
