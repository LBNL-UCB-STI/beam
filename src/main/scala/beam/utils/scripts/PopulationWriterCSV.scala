package beam.utils.scripts

import java.io.BufferedWriter
import java.io.IOException

import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.population.{Activity, Person, Population, PopulationFactory}
import org.matsim.core.api.internal.MatsimWriter
import org.matsim.core.population.io.PopulationWriterHandler
import org.matsim.core.utils.geometry.CoordinateTransformation
import org.matsim.core.utils.io.AbstractMatsimWriter
import org.matsim.core.utils.io.UncheckedIOException

import scala.collection.JavaConverters._

/**
  * BEAM
  */
class PopulationWriterCSV(val coordinateTransformation: CoordinateTransformation, val population: Population, val network: Network, val write_person_fraction: Double)  extends AbstractMatsimWriter with MatsimWriter {


  /**
    * Creates a new PlansWriter to write out the specified plans to the specified file and with
    * the specified version.
    * If plans-streaming is on, the file will already be opened and the file-header be written.
    * If plans-streaming is off, the file will not be created until {@link #write(java.lang.String)} is called.
    *
    * @param coordinateTransformation transformation from the internal CRS to the CRS in which the file should be written
    * @param population               the population to write to file
    * @param fraction                 of persons to write to the plans file
    */

    val handler = new PopulationWriterHandler {
      override def writeHeaderAndStartElement(out: BufferedWriter): Unit = out.write("id,type,x,y,end.time")

      override def writeSeparator(out: BufferedWriter): Unit = out.flush()

      override def startPlans(plans: Population, out: BufferedWriter): Unit = out.flush()

      override def endPlans(out: BufferedWriter): Unit = out.flush()

      override def writePerson(person: Person, out: BufferedWriter): Unit = {
        person.getSelectedPlan.getPlanElements.forEach { elem =>
          if (elem.isInstanceOf[Activity]) {
            val activity = elem.asInstanceOf[Activity]
            out.write(s"${person.getId},${activity.getType},${activity.getCoord.getX},${activity.getCoord.getY},${activity.getEndTime}\n")
          }
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
        this.population.getPersons.values().forEach{ person =>
          this.handler.writePerson(person,writer)
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

object PopulationWriterCSV{
  def apply(population: Population): PopulationWriterCSV = new PopulationWriterCSV(null, population, null, 1.0)

}
