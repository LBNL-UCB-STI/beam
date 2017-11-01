package json.converter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;

public class JavaTest {

    public static void main(String[] args) throws Exception {
//        String ruta = "d:/output.json";
//        String content = readTextContentFile(ruta);
//        List<TazOutput.TazStructure> jsonStructure = TncToday.processJsonJava(content);
//
//        for(TazOutput.TazStructure item : jsonStructure){
//            System.out.println("Taz: " + item.taz());
//            System.out.println("Geometry: " + item.geometry());
//            for(TazOutput.Coordinates coordinate: item.geometry().coordinates()){
//                System.out.println("Lat: " + coordinate.lat());
//                System.out.println("Lon: " + coordinate.lon());
//            }
//        }

        List<TazOutput.TazStats> stats = new LinkedList<>();

        stats.add(new TazOutput.TazStats(1l,0,"00:00:00",0,0));
        stats.add(new TazOutput.TazStats(1l,0,"01:00:00",0.2,0));
        stats.add(new TazOutput.TazStats(1l,0,"02:00:00",0,0));
        stats.add(new TazOutput.TazStats(1l,0,"03:00:00",0.4,0.6));
        stats.add(new TazOutput.TazStats(1l,0,"04:00:00",0.2,0.2));

        TncToday.saveJsonStructure(stats, "d:/stats_test.json", "d:/statsTotals_test.json");
    }

    public static String readTextContentFile(String input) throws Exception{

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

}
