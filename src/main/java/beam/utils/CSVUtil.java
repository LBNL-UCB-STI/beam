package beam.utils;

import beam.charging.infrastructure.ChargingInfrastructureManagerImpl;
import org.apache.log4j.Logger;
import org.jfree.data.io.CSV;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * @Author mygreencar.
 */
public class CSVUtil {
    private static final Logger log = Logger.getLogger(CSVUtil.class);
    private static final char DEFAULT_SEPARATOR = ',';

    public static void writeLine(Writer w, List<String> values) throws IOException{
        writeLine(w, values, DEFAULT_SEPARATOR, ' ');
    }

    public static void writeLine(Writer w, List<String> values, char separators) throws IOException {
        writeLine(w, values, separators, ' ');
    }

    //https://tools.ietf.org/html/rfc4180
    private static String followCVSformat(String value) {

        String result = value;
        if (result.contains("\"")) {
            result = result.replace("\"", "\"\"");
        }
        return result;

    }

    public static void writeLine(Writer w, List<String> values, char separators, char customQuote) throws IOException {

        boolean first = true;

        //default customQuote is empty

        if (separators == ' ') {
            separators = DEFAULT_SEPARATOR;
        }

        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (!first) {
                sb.append(separators);
            }
            if (customQuote == ' ') {
                sb.append(followCVSformat(value));
            } else {
                sb.append(customQuote).append(followCVSformat(value)).append(customQuote);
            }

            first = false;
        }
        sb.append("\n");
        w.append(sb.toString());


    }

    static public String getValue(String columnName, String[] row, LinkedHashMap<String, Integer> headerMap){
        String returnValue = "";
        if(!headerMap.containsKey(columnName)){
            log.error("Cannot find column named \""+columnName+"\" in CSV file with header "+headerMap.keySet().toString());
        }else if (row[headerMap.get(columnName)].startsWith("\"")) {
            returnValue = row[headerMap.get(columnName)].substring(1, row[headerMap.get(columnName)].length() - 1);
        }else{
            returnValue = row[headerMap.get(columnName)];
        }
        return returnValue.trim();
    }
}
