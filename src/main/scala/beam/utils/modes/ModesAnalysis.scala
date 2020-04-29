package beam.utils.modes

import beam.utils.modes.EventReader.{AttributeFilter, Filter}

import scala.language.higherKinds
import beam.utils.modes.network.{Network, Node}

import scala.collection.mutable
import cats._
import cats.implicits._

object ModesAnalysis {
  private def beamPrepend(path: String) = "/Users/alex/Documents/Projects/Simultion/beam/" + path
  private def eventFileName(simName: String) = beamPrepend("output/munich/" + simName + "/ITERS/it.0/0.events.xml")
  private val networkPath: String = beamPrepend("test/input/munich/r5/physsim-network.xml")
  private val simName = "munich__2020-04-29_21-53-50_yqj"
  private val personId = "010100-2012000596480-0-179581"

  def main(args: Array[String]): Unit = {
    val pathMode = "car"
    val linkModes = Seq("car")

    println("\nReading network...")
    val network = NetworkReader.readNetwork(networkPath)
    println("Network has been read")

    println("\n|----------------------------------------------------------|")

    println("\nStarting network analysis...")
    networkAnalysis(network, pathMode)
    println("Network analysis has been done")

    val linkNotAllowed = network.notAllowedFor("car").head
    println(s"The example of such a link: $linkNotAllowed")

    println("\n|----------------------------------------------------------|")

    println(s"\nStarting eventFileName analysis...")
    val counter = eventAnalysis(eventFileName(simName), network, pathMode, linkModes)
    printEventAnalysis(pathMode, counter, network.notAllowedFor(pathMode).size)
    println("\nEvents analysis has been done")

    println("\n|----------------------------------------------------------|")

    println(s"\nStarting traversal person path...")
    personPath(network, eventFileName(simName), personId)
    println("\nPerson path has been traversed")
  }

  private def findNodeNotAccessibleBy(network: Network, mode: String): Node = {
    val linksToNode = network.links.groupBy(_.to)
    val nodesById = network.nodes.groupBy(_.id).mapValues(_.head)

    val notAccesibleByModeNOdes = linksToNode.iterator
      .filter {
        case (_, value) if value.nonEmpty => value.forall(_.notAllowedFor(mode))
      }
      .map(_._1)

    notAccesibleByModeNOdes.flatMap(nodesById.get).next()
  }

  private def networkAnalysis(network: Network, mode: String) = {
    val amountOfLinks = network.links.size
    val amountOfCarNotAllowedLinks = network.notAllowedFor(mode).size
    val percentageOfLinks = amountOfCarNotAllowedLinks * 100 / amountOfLinks
    val nodeNotAccessibleByMode = findNodeNotAccessibleBy(network, mode)

    printf("\nAmount of links: %d\n", amountOfLinks)
    printf("Amount of links that not allowed for mode=%s: %d\n", mode, amountOfCarNotAllowedLinks)
    printf("Percentage of links that not allowed for mode=%s: %d\n", mode, percentageOfLinks)
    printf("Node not accessible by mode=%s: %s\n", mode, nodeNotAccessibleByMode)
  }

  private def eventAnalysis(eventFileName: String, network: Network, pathMode: String, linkModes: Seq[String]): Counter = {
    val notAllowedFor = network.notAllowedFor(pathMode)
    val counter = Counter()

    val pathTraversableEvents = AttributeFilter("type", _ == "PathTraversal")

    EventReader.readEvents(eventFileName, List(pathTraversableEvents)).filter(_.isAnyModeOf(linkModes: _*)).foreach {
      ev =>
        val links = ev.links
        val len = links.length

        if (len > 0) {
          val head = links.head
          counter.notAllowedBeginSet ++= metrics(head.some, notAllowedFor)
        }

        if (len > 1) {
          val end = links.last
          counter.notAllowedEndSet ++= metrics(end.some, notAllowedFor)
        }

        if (len > 2) {
          val middle = ev.links.slice(1, len - 1)
          counter.notAllowedMiddleSet ++= metrics(middle.toList, notAllowedFor)
        }

    }

    counter
  }

  private def printEventAnalysis(mode: String, counter: Counter, totalLinksAmount: Int) = {
    val count = totalLinksAmount
    printf("Amount of paths proceed: %d\n", count)

    val notAllowedBegin = counter.notAllowedBeginSet.size
    printf("\nAmount of paths that not allowed for mode=%s in the BEGINNING of the way: %d\n", mode, notAllowedBegin)
    printf(
      "Percentage of paths that not allowed for mode=%s in the BEGINNING of the way: %f\n",
      mode,
      notAllowedBegin * 100d / count
    )
    counter.notAllowedBeginSet.headOption.foreach { linkId =>
      printf("Example of such a link for mode=%s in the BEGINNING of the way: %d\n", mode, linkId)
    }

    val notAllowedMiddle = counter.notAllowedMiddleSet.size
    printf("\nAmount of paths that not allowed for mode=%s in the MIDDLE of the way: %d\n", mode, notAllowedMiddle)
    printf(
      "Percentage of paths that not allowed for mode=%s in the MIDDLE of the way: %f\n",
      mode,
      notAllowedMiddle * 100d / count
    )
    counter.notAllowedMiddleSet.headOption.foreach { linkId =>
      printf("Example of such a link for mode=%s in the in the MIDDLE of the way: %d\n", mode, linkId)
    }

    val notAllowedEnd = counter.notAllowedEndSet.size
    printf("\nAmount of paths that not allowed for mode=%s in the END of the way: %d\n", mode, notAllowedEnd)
    printf(
      "Percentage of paths that not allowed for mode=%s in the END of the way: %f\n",
      mode,
      notAllowedEnd * 100d / count
    )
    counter.notAllowedEndSet.headOption.foreach { linkId =>
      printf("Example of such a link for mode=%s in the in the END of the way: %d\n", mode, linkId)
    }
  }

  private def personPath(network: Network, eventFileName: String, personId: String) = {
    val personIdAttribute =
      Filter.or(AttributeFilter("person", _ == personId), AttributeFilter("driver", _ == personId))
    val eventType = AttributeFilter("type", _ == "PathTraversal")
    println("\nEvents: ")

    val edges = for {
      event <- EventReader.readEvents(eventFileName, List(personIdAttribute, eventType))
      link  <- event.links.flatMap(network.linksMap)
    } yield PathLink(event.mode, link.id, link.modes.mkString("[", ",", "]"))

    edges.foreach(println)
  }

  private def metrics[F[_]: FunctorFilter](eventLinks: F[Int], notAllowedSet: Set[Int]): F[Int] =
    FunctorFilter[F].filter(eventLinks)(notAllowedSet.contains)

  case class Counter(
    var notAllowedBeginSet: mutable.HashSet[Int] = new mutable.HashSet[Int](),
    var notAllowedMiddleSet: mutable.HashSet[Int] = new mutable.HashSet[Int](),
    var notAllowedEndSet: mutable.HashSet[Int] = new mutable.HashSet[Int]()
  )

  case class PathLink(mode: String, linkId: Int, linkMode: String)
}
