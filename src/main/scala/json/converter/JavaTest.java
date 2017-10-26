package json.converter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

public class JavaTest {

    public static void main(String[] args) throws Exception {
        String ruta = "d:/output.json";
        String content = readTextContentFile(ruta);
        List<TazOutput.TazViz> jsonStructure = TncToday.processJsonJava(content);

        for(TazOutput.TazViz item : jsonStructure){
            System.out.println("Taz: " + item.taz());
        }
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
