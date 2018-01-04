package beam.sim;

import scala.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

public class RunBeamGroup {

    public static void main(String[] args){

        String dirPath = args[0];
        int maxDepth = 2;

        RunBeam$ runBeam = RunBeam$.MODULE$;
        try (Stream<Path> stream = Files.find(Paths.get(dirPath), maxDepth,
                (path, attr) -> path.getFileName().toString().endsWith(".conf") )) {

            stream.forEach(
                fileName -> {
                    System.out.println("Going to run config " + fileName);
                    scala.Option<String> _fileName = Option.apply(fileName.toString());
                    runBeam.rumBeamWithConfigFile(_fileName);
                }
            );
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
