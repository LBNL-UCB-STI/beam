package beam.utils.scripts

import sys.process._

object PythonExecutor {

  def apply(scriptPath: String, path: String, quad: String, coord: String): Unit = {
    val result = s"python3 $scriptPath $path $quad $coord" ! ProcessLogger(stdout append _, stderr append _)
  }

  def main(args: Array[String]): Unit = {

    val path = "/home/rajnikant/IdeaProjects/beam/output/beamville/beamville__2019-05-14_23-00-34/ITERS/it.0"
    val coord = "0.coord_output.csv"
    val quad = "0.quad_output.csv"
    val script = "/home/rajnikant/IdeaProjects/beam/src/main/python/graphs/debug/repositionVisualization.py"
    PythonExecutor(script, path, quad, coord)
  }
}
