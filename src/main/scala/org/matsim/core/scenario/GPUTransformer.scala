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

  val personsFilePath = "/Users/crixal/work/2566/gpu/od_demand_5to12_with_dep_times_based_on_routes.csv"
  val networkPath = "/Users/crixal/work/2566/gpu/nodes.csv"
  val edgesPath = "/Users/crixal/work/2566/gpu/edges.csv"
  val routesPath = "/Users/crixal/work/2566/gpu/0_route_with_edges.csv"

  val populationOutputFile = "/Users/crixal/work/2566/population-output.xml"
  val networkOutputFile = "/Users/crixal/work/2566/network-output.xml"

  val network = NetworkUtils.createNetwork()
  val population = PopulationUtils.createPopulation(ConfigUtils.createConfig(), network)

  val geoUtils = new beam.sim.common.GeoUtils {
    override def localCRS: String = "epsg:26910"
  }

  private def fillNetwork(): Unit = {
    val (iter, toClose) = GenericCsvReader.readAs(
      networkPath,
      (m: java.util.Map[String, String]) =>
        new NodeGPU(
          m.get("osmid").toLong,
          m.get("x").toDouble,
          m.get("y").toDouble
      ), { _: NodeGPU =>
        true
      }
    )

    try {
      iter.foreach { n =>
        network.addNode(NetworkUtils.createNode(Id.createNodeId(n.id), geoUtils.wgs2Utm(new Coord(n.x, n.y))))
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
      ), { _: EdgeGPU =>
        true
      }
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
      (m: java.util.Map[String, String]) => {
        val origin = m.get("origin")
        if (origin == null) {
          None
        } else {
          Some(
            new DemandGPU(
              m.get("SAMPN").toLong,
              m.get("origin").toDouble.toLong,
              m.get("destination").toDouble.toLong,
              m.get("dep_time").toDouble
            )
          )
        }
      }, { _: Option[DemandGPU] =>
        true
      }
    )

    val routePattern = ":\\[(.*),]".r
    val routesBuffer = Source.fromFile(routesPath).bufferedReader()
    // skip first line
    routesBuffer.readLine()

    var counter = 0L

    try {
      while (pIter.hasNext) {
        val line = routesBuffer.readLine()

        pIter.next() match {
          case Some(demandGPU) =>
            val routesGPU = routePattern.findFirstMatchIn(line) match {
              case Some(m) => m.group(1).split(',').map(Id.createLinkId)
              case None    => throw new IllegalStateException(s"No routes found '$line'")
            }

            val personId = Id.createPersonId(demandGPU.rowId)
            val person = if (population.getPersons.containsKey(personId)) {
              population.getPersons.get(personId)
            } else {
              val newPerson = PopulationUtils.getFactory.createPerson(Id.createPersonId(demandGPU.rowId))
              newPerson.addPlan(population.getFactory.createPlan())
              population.addPerson(newPerson)
              newPerson
            }

            val links = routesGPU.map { Id.createLinkId }
            val startLinkId = links.head
            val endLinkId = links.last

            val activity = population.getFactory.createActivityFromLinkId("DummyActivity", startLinkId)
            activity.setEndTime(demandGPU.depTime)
            person.getSelectedPlan.addActivity(activity)

            val leg = population.getFactory.createLeg("car")
            leg.setDepartureTime(demandGPU.depTime)
            leg.getAttributes.putAttribute(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME, demandGPU.depTime.toInt)
            person.getSelectedPlan.addLeg(leg)


            if (network.getLinks.get(startLinkId).getFromNode.getId != Id.createNodeId(demandGPU.originId) ||
                network.getLinks.get(endLinkId).getToNode.getId != Id.createNodeId(demandGPU.destinationId)) {
              throw new IllegalStateException(
                s"Nodes from routes are not equals to nodes from person (${demandGPU.rowId})"
              )
            }

            leg.setRoute(RouteUtils.createLinkNetworkRouteImpl(startLinkId, links, endLinkId))

            counter+=1
            if (counter % 10000 == 0) {
              println(s"Processed rows: $counter")
            }

          case None =>
        }
      }
      println(s"Processed rows: $counter")
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

class DemandGPU(val rowId: Long, val originId: Long, val destinationId: Long, val depTime: Double)

class NodeGPU(val id: Long, val x: Double, val y: Double)

class EdgeGPU(val id: Long, val from: Long, val to: Long, val length: Double, val lanes: Double, val speedMph: Double)

class RouteGPU(val routes: List[Long])
