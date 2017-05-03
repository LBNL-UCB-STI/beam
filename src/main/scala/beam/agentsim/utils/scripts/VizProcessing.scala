package beam.agentsim.utils.scripts

import beam.agentsim.utils.JsonUtils

/**
  * Created by sfeygin on 3/28/17.
  */
object VizProcessing extends App{
    JsonUtils.processEventsFileVizData(args(0),args(1))
}
