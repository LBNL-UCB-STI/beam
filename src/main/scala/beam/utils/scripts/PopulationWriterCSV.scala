package beam.utils.scripts

import java.io.{BufferedWriter, IOException}

import beam.sim.MapStringDouble
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.population.{Activity, Person, Population}
import org.matsim.core.api.internal.MatsimWriter
import org.matsim.core.population.io.PopulationWriterHandler
import org.matsim.core.utils.geometry.CoordinateTransformation
import org.matsim.core.utils.io.{AbstractMatsimWriter, UncheckedIOException}

/**
  * BEAM
  */
class PopulationWriterCSV(
  val coordinateTransformation: CoordinateTransformation,
  val population: Population,
  val network: Network,
  val write_person_fraction: Double
) extends AbstractMatsimWriter
    with MatsimWriter {

  /**
    * Creates a new PlansWriter to write out the specified plans to the specified file and with
    * the specified version.
    * If plans-streaming is on, the file will already be opened and the file-header be written.
    * If plans-streaming is off, the file will not be created until `#write(java.lang.String)` is called.
    *
    **/

  val handler: PopulationWriterHandler = new PopulationWriterHandler {
    override def writeHeaderAndStartElement(out: BufferedWriter): Unit =
      out.write("id,type,x,y,end.time,customAttributes\n")

    override def writeSeparator(out: BufferedWriter): Unit = out.flush()

    override def startPlans(plans: Population, out: BufferedWriter): Unit = out.flush()

    override def endPlans(out: BufferedWriter): Unit = out.flush()

    override def writePerson(person: Person, out: BufferedWriter): Unit = {
      val planAttribs = person.getSelectedPlan.getAttributes
      val modalityStyle = if (planAttribs.getAttribute("modality-style") != null) {
        planAttribs.getAttribute("modality-style")
      } else { "" }
      val modalityScores = if (planAttribs.getAttribute("scores") != null) {
        val scoreMap = planAttribs.getAttribute("scores").asInstanceOf[MapStringDouble].data
        scoreMap.keySet.toVector.sorted
          .map(key => Vector(key, scoreMap(key).toString).mkString(","))
          .mkString(",")
      } else { "" }
      var planAttribsString = s"$modalityStyle,$modalityScores"
      person.getSelectedPlan.getPlanElements.forEach {
        case activity: Activity =>
          out.write(
            s"${person.getId},${activity.getType},${activity.getCoord.getX},${activity.getCoord.getY},${activity.getEndTime},$planAttribsString\n"
          )
          planAttribsString = "" // only write for first activity to avoid dups
        case _ =>
      }
    }
  }

  /**
    * Writes all plans to the file.
    */
  override def write(filename: String): Unit = {
    try {
      this.openFile(filename)
      this.handler.writeHeaderAndStartElement(this.writer)
      this.population.getPersons.values().forEach { person =>
        this.handler.writePerson(person, writer)
      }
      this.handler.endPlans(this.writer)
    } catch {
      case e: IOException =>
        throw new UncheckedIOException(e)
    } finally {
      this.close()
    }
  }

}

object PopulationWriterCSV {

  def apply(population: Population): PopulationWriterCSV =
    new PopulationWriterCSV(null, population, null, 1.0)

}
