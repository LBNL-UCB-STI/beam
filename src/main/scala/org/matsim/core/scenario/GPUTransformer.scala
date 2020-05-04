package org.matsim.core.scenario

import beam.agentsim.events.PathTraversalEvent
import beam.utils.csv.GenericCsvReader
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.network.NetworkUtils
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.routes.RouteUtils

import scala.io.Source

object GPUTransformer extends App {

  val personsFilePath = "/Users/crixal/work/2566/gpu/od_demand_5to12_with_dep_times.csv"
  val networkPath = "/Users/crixal/work/2566/gpu/nodes.csv"
  val edgesPath = "/Users/crixal/work/2566/gpu/edges.csv"
  val routesPath = "/Users/crixal/work/2566/gpu/0_route_with_edges.csv"

  val populationOutputFile = "/Users/crixal/work/2566/population-output.xml"
  val networkOutputFile = "/Users/crixal/work/2566/network-output.xml"

  val network = NetworkUtils.createNetwork()
  val population = PopulationUtils.createPopulation(ConfigUtils.createConfig(), network)

  private def fillNetwork(): Unit = {
    val (iter, toClose) = GenericCsvReader.readAs(
      networkPath,
      (m: java.util.Map[String, String]) =>
        new NodeGPU(
          m.get("osmid").toLong,
          m.get("x").toDouble,
          m.get("y").toDouble
        )
    )

    try {
      iter.foreach { n =>
        network.addNode(NetworkUtils.createNode(Id.createNodeId(n.id), new Coord(n.x, n.y)))
      }
    } finally {
      toClose.close()
    }
  }

  private def fillEdges(): Unit = {
    val (iter, toClose) = GenericCsvReader.readAs(
      edgesPath,
      (m: java.util.Map[String, String]) =>
        new EdgeGPU(
          m.get("uniqueid").toLong,
          m.get("u").toLong,
          m.get("v").toLong,
          m.get("length").toDouble,
          m.get("lanes").toDouble,
          m.get("speed_mph").toDouble
        )
    )

    try {
      iter.foreach { e =>
        NetworkUtils.createAndAddLink(
          network,
          Id.createLinkId(e.id),
          network.getNodes.get(Id.createNodeId(e.from)),
          network.getNodes.get(Id.createNodeId(e.to)),
          e.length,
          e.speedMph,
          1.toDouble,
          e.lanes
        )
      }
    } finally {
      toClose.close()
    }
  }

  private def fillPopulation(): Unit = {
    val (pIter, pToClose) = GenericCsvReader.readAs(
      personsFilePath,
      (m: java.util.Map[String, String]) =>
        new PersonGPU(
          m.get("id").toLong,
          m.get("SAMPN").toLong,
          m.get("PERNO").toInt,
          m.get("origin").toLong,
          m.get("destination").toLong,
          m.get("dep_time").toDouble
        )
    )

    val routePattern = ":\\[(.*),]".r
    val routesBuffer = Source.fromFile(routesPath).bufferedReader()
    // skip first line
    routesBuffer.readLine()

    try {
      while (pIter.hasNext) {
        val personGPU = pIter.next()
        val line = routesBuffer.readLine()
        val routesGPU = routePattern.findFirstMatchIn(line) match {
          case Some(m) => m.group(1).split(',').map(Id.createLinkId)
          case None => throw new IllegalStateException(s"No routes found '$line'")
        }

        val personId = Id.createPersonId(personGPU.id)
        val person = if (population.getPersons.containsKey(personId)) {
          population.getPersons.get(personId)
        } else {
          val newPerson = PopulationUtils.getFactory.createPerson(Id.createPersonId(personGPU.id))
          newPerson.addPlan(population.getFactory.createPlan())
          population.addPerson(newPerson)
          newPerson
        }

        val activity = population.getFactory.createActivityFromLinkId("DummyActivity", Id.createLinkId(personGPU.originId))
        activity.setEndTime(personGPU.depTime)
        person.getSelectedPlan.addActivity(activity)

        val leg = population.getFactory.createLeg("car")
        leg.setDepartureTime(personGPU.depTime)
        leg.getAttributes.putAttribute(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME, personGPU.depTime.toInt)
        leg.getAttributes.putAttribute(PathTraversalEvent.ATTRIBUTE_NUM_PASS, personGPU.perno)
        person.getSelectedPlan.addLeg(leg)

        val links = routesGPU.map {
          Id.createLinkId
        }
        val startLinkId = links.head
        val endLinkId = links.last

        if (network.getLinks.get(startLinkId).getFromNode.getId != Id.createNodeId(personGPU.originId) ||
          network.getLinks.get(endLinkId).getToNode.getId != Id.createNodeId(personGPU.destinationId)) {
          throw new IllegalStateException(s"Nodes from routes are not equals to nodes from person (${personGPU.rowId})")
        }

        val route = RouteUtils.createLinkNetworkRouteImpl(startLinkId, links, endLinkId)
        // FIXME calculate links and real distance
        route.setDistance(10)
        leg.setRoute(route)
      }
    } finally {
      pToClose.close()
      routesBuffer.close()
    }
  }

  fillNetwork()
  fillEdges()
  fillPopulation()

  PopulationUtils.writePopulation(population, populationOutputFile)
  NetworkUtils.writeNetwork(network, networkOutputFile)
}

class PersonGPU(val rowId: Long, val id: Long, val perno: Int, val originId: Long, val destinationId: Long, val depTime: Double)

class NodeGPU(val id: Long, val x: Double, val y: Double)

class EdgeGPU(val id: Long, val from: Long, val to: Long, val length: Double, val lanes: Double, val speedMph: Double)

class RouteGPU(val routes: List[Long])
