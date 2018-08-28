package beam.agentsim.agents.ridehail.graph

trait EventAnalyzer {
  def eventFile(iteration: Int): Unit
}
