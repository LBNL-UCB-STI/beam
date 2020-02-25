package beam.utils.data.synthpop

import beam.utils.csv.GenericCsvReader
import beam.utils.data.synthpop.models.Models.{BlockGroupGeoId, Household}
import com.typesafe.scalalogging.StrictLogging

import scala.util.Try

class HouseholdReader(val pathToHouseholdFile: String) extends StrictLogging {

  def read(): Seq[Household] = {
    val (it, toClose) = GenericCsvReader.readAs[Household](pathToHouseholdFile, toHousehold, x => true)
    try {
      it.toVector
    } finally {
      Try(toClose.close())
    }
  }

  private[synthpop] def toHousehold(rec: java.util.Map[String, String]): Household = {
    // Get the details of columns from https://www2.census.gov/programs-surveys/acs/tech_docs/pums/data_dict/PUMS_Data_Dictionary_2017.pdf?

    val id = GenericCsvReader.getIfNotNull(rec, "id").toString
    val numOfPersons = Option(rec.get("NP")).map(_.toInt).getOrElse {
      logger.warn(s"Could not find `NP` field in ${rec.toString}")
      0
    }
    val numOfVehicles = Option(rec.get("VEH")).map(_.toDouble.toInt).getOrElse {
      logger.warn(s"Could not find `VEH` field in ${rec.toString}")
      0
    }
    val income = Option(rec.get("HINCP")).map(_.toDouble).getOrElse {
      logger.warn(s"Could not find `HINCP` field in ${rec.toString}")
      0.0
    }
    // TODO FIX SythPop, https://github.com/LBNL-UCB-STI/synthpop/blob/master/synthpop/recipes/starter2.py to make sure we have exact number of children.
    // For now we assume than if there `hh_children == yes` then it is 1 otherwise 0
    val numOfChildren = if (Option(rec.get("hh_children")).contains("yes")) 1 else 0
    val numOfWorkers = Option(rec.get("workers")).map(_.toDouble.toInt).getOrElse {
      logger.warn(s"Could not find `workers` field in ${rec.toString}")
      0
    }

    // Read geoid
    val state = GenericCsvReader.getIfNotNull(rec, "state").toString
    val county = GenericCsvReader.getIfNotNull(rec, "county").toString
    val tract = GenericCsvReader.getIfNotNull(rec, "tract").toString
    val blockGroupId = GenericCsvReader.getIfNotNull(rec, "block group").toString
    val geoId = BlockGroupGeoId(state = state, county = county, tract = tract, blockGroup = blockGroupId)

    Household(
      id = id,
      geoId = geoId,
      numOfPersons = numOfPersons,
      numOfVehicles = numOfVehicles,
      income = income,
      numOfChildren = numOfChildren,
      numOfWorkers = numOfWorkers
    )
  }
}

object HouseholdReader {

  def main(args: Array[String]): Unit = {
    require(args.size == 1, "Provide the path to CSV file as first argument")

    val pathToFile = args(0)
    val rdr = new HouseholdReader(pathToFile)
    val households = rdr.read()
    println(s"Read ${households.size} households")
  }
}
