package beam.utils.data.synthpop

import beam.utils.csv.GenericCsvReader
import beam.utils.data.synthpop.models.Models.{Gender, Person}
import com.typesafe.scalalogging.StrictLogging

import scala.util.Try

class PopulationReader(val pathToPopulationFile: String) extends StrictLogging {

  def read(): Seq[Person] = {
    val (it, toClose) = GenericCsvReader.readAs[Person](pathToPopulationFile, toPerson, x => true)
    try {
      it.toVector
    } finally {
      Try(toClose.close())
    }
  }

  private[synthpop] def toPerson(rec: java.util.Map[String, String]): Person = {
    // Get the details of columns from https://www2.census.gov/programs-surveys/acs/tech_docs/pums/data_dict/PUMS_Data_Dictionary_2017.pdf?
    val age = Option(rec.get("AGEP")).map(_.toInt).getOrElse(0)
    val gender = Option(rec.get("SEX")).map(_.toInt).getOrElse(1) match {
      case 1 => Gender.Male
      case 2 => Gender.Female
    }

    val compoundHouseholdId = {
      val serialNo = GenericCsvReader.getIfNotNull(rec, "serialno")
      val householdId = GenericCsvReader.getIfNotNull(rec, "hh_id")
      HouseholdReader.getCompoundHouseholdId(serialNo, householdId)
    }
    val id = GenericCsvReader.getIfNotNull(rec, "id").toString
    Person(id = id, age = age, gender = gender, householdId = compoundHouseholdId)
  }
}

object PopulationReader {

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Provide the path to CSV file as first argument")

    val pathToFile = args(0)
    val rdr = new PopulationReader(pathToFile)
    val households = rdr.read()
    println(s"Read ${households.size} persons")
  }
}
