package beam.utils.data.synthpop

import beam.utils.csv.GenericCsvReader
import beam.utils.data.synthpop.models.Models.{GeoId, Household}
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
    val numOfPersons = Option(rec.get("NP")).map(_.toInt).getOrElse(0)
    val numOfVehicles = Option(rec.get("VEH")).map(_.toDouble.toInt).getOrElse(0)
    val income = Option(rec.get("FINCP")).map(_.toDouble).getOrElse(0.0)
    val numOfChildren = Option(rec.get("NOC")).map(_.toDouble.toInt).getOrElse(0)
    val numOfWorkers = Option(rec.get("WIF")).map(_.toDouble.toInt).getOrElse(0)

    // Read geoid
    val state = GenericCsvReader.getIfNotNull(rec, "state").toString
    val county = GenericCsvReader.getIfNotNull(rec, "county").toString
    val tract = GenericCsvReader.getIfNotNull(rec, "tract").toString
    val blockGroupId = GenericCsvReader.getIfNotNull(rec, "block group").toString
    val geoId = GeoId(state = state, county = county, tract = tract)

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
