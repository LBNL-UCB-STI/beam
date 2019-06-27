package beam.utils.beamToVia

object EventsFromPathTraversal extends App {
  //val sourcePath = "D:/Work/BEAM/Via-fs-light/oddBus.xml"
  //val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.xml"
  //val sourcePath = "D:/Work/BEAM/Via-beamville/0.events.xml"
  val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.csv"

  val config = RunConfig.withoutFiltering(sourcePath)
  TransformAllPathTraversal.transformAndWrite(config)
}
