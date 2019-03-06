package beam.utils.csv.readers;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class CsvToMap implements ICsvReader{

    @Override
    public Map<String, Map<String, String>> read(BufferedReader bufferedReader) throws IOException {

        String[] headers = null;
        Map<String, Map<String, String>> map = new TreeMap<>();

        String line;

        int i = 0;

        while ((line = bufferedReader.readLine()) != null) {

            //System.outWriter.println(line);
            if(i == 0){
                headers = line.split(",");
            }else {
                Map<String, String> row = new TreeMap<>();
                String[] data = line.split(",", -1);

                for(int j=0; j<headers.length; j++){

                    row.put(headers[j], data[j]);
                }
                map.put(data[0], row);
            }

            i++;
        }

        return map;
    }

    public Map<String, List<Map<String, String>>> readListOfMaps(BufferedReader bufferedReader) throws IOException {

        String[] headers = null;
        Map<String, List<Map<String, String>>> map = new TreeMap<>();

        String line;

        int i = 0;

        while ((line = bufferedReader.readLine()) != null) {

            //System.outWriter.println(line);
            if(i == 0){
                headers = line.split(",");
            }else {
                Map<String, String> row = new TreeMap<>();
                String[] data = line.split(",", -1);

                for(int j=0; j<headers.length; j++){

                    row.put(headers[j], data[j]);
                }

                List<Map<String, String>> listOfMaps = map.get(data[0]);

                if(listOfMaps == null){
                    listOfMaps = new ArrayList<>();
                }

                listOfMaps.add(row);
                map.put(data[0], listOfMaps);
            }

            i++;
        }

        return map;
    }
}
