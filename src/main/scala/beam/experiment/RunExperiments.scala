package beam.experiment

import java.io.IOException
import java.nio.file.{Files, Path, Paths}

import beam.sim.BeamHelper

class RunExperiments extends App with BeamHelper {
  val dirPath: String = args(0)
  val maxDepth = 2
  var stream: java.util.stream.Stream[Path] = _
  try {
    stream = Files.find(Paths.get(dirPath), maxDepth, (path: Path, _) => path.endsWith(".conf"))

      stream.forEach(fileName => {
        println("Going to run config " + fileName)
        runBeamWithConfigFile(Option.apply(fileName.toString));
      })
    } catch {
      case e: IOException =>
        e.printStackTrace()
    } finally if (stream != null) stream.close()

}
