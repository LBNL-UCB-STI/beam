package beam.analysis.spatialtemporalTAZ;

import json.converter.TazOutput;
import json.converter.TncToday;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class SpatialTemporalTAZVizDataWriter {

    private final List<TazOutput.TazStructure> jsonStructure;
    private final Map<TazKey, TazValue> dataMap = new HashMap<>();
    private final String mainCat;
    public SpatialTemporalTAZVizDataWriter(String filePath, String mainCat) throws Exception {
        this.mainCat = mainCat;
        String jsonContent = readTextContentFile(filePath);
        jsonStructure = TncToday.processJsonJava(jsonContent);
    }

    private String readTextContentFile(String input) throws Exception {
        InputStream is = new FileInputStream(input);
        BufferedReader buf = new BufferedReader(new InputStreamReader(is));
        String line = buf.readLine();
        StringBuilder sb = new StringBuilder();
        while (line != null) {
            sb.append(line).append("\n");
            line = buf.readLine();
        }

        return sb.toString();
    }

    public void addDataPoint(String cat, Double x, Double y, Long seconds, Double dataValue) {
        Long tazId = getTaz(x, y);
        if (tazId == null)
            return;
        int hourOfDay = getHourDayFromSeconds(seconds);
        int dayOfWeek = getDayFromSeconds(seconds);
        addToMap(cat, tazId, hourOfDay, dataValue, dayOfWeek);
    }

    private Long getTaz(Double x, Double y) {
        TazOutput.Coordinates point = new TazOutput.Coordinates(x, y);
        for (TazOutput.TazStructure tazObject : jsonStructure) {
            TazOutput.Coordinates[] coordinatesTaz = tazObject.geometry().coordinates();
            if (coordinateInRegion(point, coordinatesTaz)) {
                return tazObject.taz();
            }
        }
        return null;
    }

    public void saveToDisk(String statsPath, String totalsPath) {
        System.out.println("Map " + dataMap.size());
        List<TazOutput.TazStats> values = new LinkedList<>();

        for (TazKey k : dataMap.keySet()) {
            TazValue value = dataMap.get(k);
            String time = String.format("%02d:00:00", k.hourOfDay);
            TazOutput.TazStats item = new TazOutput.TazStats(k.tazId, value.dayOfWeek, time, value.mainValue, value.secValue);
            values.add(item);
        }

        TncToday.saveJsonStructure(values, statsPath, totalsPath);
    }

    private boolean coordinateInRegion(TazOutput.Coordinates coord, TazOutput.Coordinates[] region) {
        int i, j;
        boolean isInside = false;
        //create an array of coordinates from the region boundary list
        int sides = region.length;
        for (i = 0, j = sides - 1; i < sides; j = i++) {
            //verifying if your coordinate is inside your region
            if (
                    (
                            (
                                    (region[i].lon() <= coord.lon()) && (coord.lon() < region[j].lon())
                            ) || (
                                    (region[j].lon() <= coord.lon()) && (coord.lon() < region[i].lon())
                            )
                    ) &&
                            (coord.lat() < (region[j].lat() - region[i].lat()) * (coord.lon() - region[i].lon()) / (region[j].lon() - region[i].lon()) + region[i].lat())
                    ) {
                isInside = !isInside;
            }
        }
        return isInside;
    }

    private int getHourDayFromSeconds(Long seconds) {
        Long hours = seconds % 86400 / 3600;
        return hours.intValue();
    }

    private int getDayFromSeconds(Long seconds) {
        Long days = seconds % 604800 / 86400;
        return days.intValue();
    }

    private void addToMap(String cat, Long tazId, Integer hourOfDay, Double dataValue, Integer dayOfWeek) {
        TazKey key = new TazKey(tazId, hourOfDay);
        TazValue current = dataMap.get(key);
        if (null == current)
            current = new TazValue(0d, 0d, dayOfWeek);
        if (cat.equals(mainCat)) {
            current.mainValue += dataValue;
        } else {
            current.secValue += dataValue;
        }

        dataMap.put(key, current);
    }

    static class TazKey {
        final Long tazId;
        final Integer hourOfDay;

        public TazKey(Long tazId, Integer hourOfDay) {
            this.tazId = tazId;
            this.hourOfDay = hourOfDay;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TazKey)) return false;

            TazKey tazKey = (TazKey) o;

            if (!tazId.equals(tazKey.tazId)) return false;
            return hourOfDay.equals(tazKey.hourOfDay);
        }

        @Override
        public int hashCode() {
            int result = tazId.hashCode();
            result = 31 * result + hourOfDay.hashCode();
            return result;
        }
    }

    static class TazValue {
        Double mainValue;
        Double secValue;
        final Integer dayOfWeek;

        public TazValue(Double mainValue, Double secValue, Integer dayOfWeek) {
            this.mainValue = mainValue;
            this.secValue = secValue;
            this.dayOfWeek = dayOfWeek;
        }
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
