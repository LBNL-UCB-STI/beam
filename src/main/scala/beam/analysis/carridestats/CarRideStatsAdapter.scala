package beam.analysis.carridestats

import beam.analysis.PythonProcess

object CarRideStatsAdapter {

  def generateGoogleMapsLinks(request: CarRideStatsRequest): PythonProcess = {
    val scriptPath = "src/main/python/calibration/generate_rides_with_google_maps.py"
    beam.analysis.AnalysisProcessor.firePython3ScriptAsync(
      scriptPath,
      request.args: _*
    )
  }

}
