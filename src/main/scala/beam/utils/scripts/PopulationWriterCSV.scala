package beam.utils.scripts

import java.io.{BufferedWriter, IOException}

import beam.sim.MapStringDouble
import beam.sim.population.AttributesOfIndividual
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.population.{Activity, Person, Population}
import org.matsim.core.api.internal.MatsimWriter
import org.matsim.core.population.io.PopulationWriterHandler
import org.matsim.core.utils.geometry.CoordinateTransformation
import org.matsim.core.utils.io.{AbstractMatsimWriter, UncheckedIOException}
import org.matsim.utils.objectattributes.ObjectAttributes

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
      out.write("personId,age,isFemale,householdId,houseHoldRank,excludedModes\n")

    override def writeSeparator(out: BufferedWriter): Unit = out.flush()

    override def startPlans(plans: Population, out: BufferedWriter): Unit = out.flush()

    override def endPlans(out: BufferedWriter): Unit = out.flush()

    override def writePerson(person: Person, out: BufferedWriter): Unit = {
      val personAttrib: ObjectAttributes = population.getPersonAttributes
      val customAttributes: AttributesOfIndividual = person.getCustomAttributes.get("beam-attributes").asInstanceOf[AttributesOfIndividual]
      val values = Seq(
        person.getId,
        customAttributes.age.getOrElse(""),
        !customAttributes.isMale,
        customAttributes.householdAttributes.householdId,
        personAttrib.getAttribute(person.getId.toString, "rank"),  // TODO: correct way?
        personAttrib.getAttribute(person.getId.toString, "excluded-modes"), // TODO: examples are empty
        "\n"
      )
      out.write(values.mkString(","))
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

  def apply(population: Population): PopulationWriterCSV = {
    new PopulationWriterCSV(null, population, null, 1.0)
  }

}
