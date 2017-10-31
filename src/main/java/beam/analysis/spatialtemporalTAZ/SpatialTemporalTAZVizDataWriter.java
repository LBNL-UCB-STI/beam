package beam.analysis.spatialtemporalTAZ;

import json.converter.TazOutput;
import json.converter.TncToday;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SpatialTemporalTAZVizDataWriter {

    class TazKey{
        Long tazId;
        Integer hourOfDay;

        public TazKey(Long tazId, Integer hourOfDay) {
            this.tazId = tazId;
            this.hourOfDay = hourOfDay;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TazKey)) return false;

            TazKey tazKey = (TazKey) o;

            if (tazId != null ? !tazId.equals(tazKey.tazId) : tazKey.tazId != null) return false;
            return hourOfDay != null ? hourOfDay.equals(tazKey.hourOfDay) : tazKey.hourOfDay == null;
        }

        @Override
        public int hashCode() {
            int result = tazId != null ? tazId.hashCode() : 0;
            result = 31 * result + (hourOfDay != null ? hourOfDay.hashCode() : 0);
            return result;
        }
    }

    private List<TazOutput.TazStructure> jsonStructure;
    private Map<TazKey, Double> dataMap = new HashMap<>();

    public SpatialTemporalTAZVizDataWriter(String filePath) throws Exception {
        String jsonContent = readTextContentFile(filePath);
        jsonStructure = TncToday.processJsonJava(jsonContent);
    }

    public String readTextContentFile(String input) throws Exception{
        InputStream is = new FileInputStream(input);
        BufferedReader buf = new BufferedReader(new InputStreamReader(is));
        String line = buf.readLine();
        StringBuilder sb = new StringBuilder();
        while(line != null){
            sb.append(line).append("\n");
            line = buf.readLine();
        }

        return sb.toString();
    }

    public void addDataPoint(Double x, Double y, Long seconds, Double dataValue){
        Long tazId = getTaz(x, y);
        Integer hourOfDay = getHourDayFromSeconds(seconds);
        addToMap(tazId, hourOfDay, dataValue);
    }

    //TODO
    private Long getTaz(Double x, Double y){
        return null;
    }

    //TODO
    private Integer getHourDayFromSeconds(Long seconds){
        return 0;
    }

    private void addToMap(Long tazId, Integer hourOfDay, Double dataValue){
        TazKey key = new TazKey(tazId, hourOfDay);
        Double current = dataMap.get(key);
        if(null == current)
            current = 0d;
        current += dataValue;

        dataMap.put(key, current);
    }

    // TODO: add parameters, e.g. remove tazs below a certain level, in order to reduce data?

    // TODO: what is easiest way to read a json file in java?
        // https://stackoverflow.com/questions/2591098/how-to-parse-json

    // TODO: create constructor with input: TAZ data -> use Geotools or other lib?
        //https://stackoverflow.com/questions/8721406/how-to-determine-if-a-point-is-inside-a-2d-convex-polygon


    // TODO: create method for adding points and data -> data gets aggregated to correct TAZ

    // TODO: create method for writing out json file to disk
        // also totals file needs to be written??

    // TODO: create tests for this!

}
