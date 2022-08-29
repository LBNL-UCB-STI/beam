package beam.utils.data.synthpop

import java.io.{File, FileFilter}

import beam.utils.csv.CsvWriter
import beam.utils.data.synthpop.models.Models.Gender

import scala.util.Try

object SythpopOutputUnion {

  def main(args: Array[String]): Unit = {
    require(args.length == 2)
    val folderToScan = if (args(0).endsWith("/")) args(0) else args(0) + "/"
    val outputFolder = args(1)

    val householdFiles = new File(folderToScan).listFiles(new FileFilter {
      override def accept(pathname: File): Boolean = {
        pathname.getName.matches("household_.+\\.csv")
      }
    })

    householdFiles
      .map { f =>
        val name = f.getName.replaceAll("household_", "")
        name -> f
      }
      .sortBy { case (name, _) => name }
      .map(_._2)

    val peopleFiles = new File(folderToScan)
      .listFiles(new FileFilter {
        override def accept(pathname: File): Boolean = {
          pathname.getName.matches("people_.+\\.csv")
        }
      })
      .map { f =>
        val name = f.getName.replaceAll("people_", "")
        name -> f
      }
      .sortBy { case (name, _) => name }
      .map(_._2)

    householdFiles.zip(peopleFiles)

    val householdCsvWriter = new CsvWriter(
      outputFolder + "/all_households.csv",
      Vector("NP", "VEH", "HINCP", "hh_children", "workers", "state", "county", "tract", "block group", "id")
    )
    val peopleCsvWriter = new CsvWriter(outputFolder + "/all_people.csv", Vector("AGEP", "SEX", "hh_id"))

    try {
      var totalNumberOfHouseholds: Int = 0
      householdFiles.zip(peopleFiles).foreach { case (householdFile, peopleFile) =>
        println(s"householdFile: $householdFile")
        println(s"peopleFile: $peopleFile")

        @SuppressWarnings(Array("UnsafeTraversableMethods"))
        val households =
          new HouseholdReader(householdFile.getAbsolutePath).read().groupBy(x => x.id).map { case (hhId, xs) =>
            hhId -> xs.head
          }
        val householdIdToPersons = new PopulationReader(peopleFile.getAbsolutePath).read().groupBy(x => x.householdId)
        val householdWithPersons = householdIdToPersons.map { case (hhId, persons) =>
          val household = households(hhId)
          val newHouseholdId = household.id.toInt + totalNumberOfHouseholds
          val updatedHousehold = household.copy(id = newHouseholdId.toString)
          val updatedPersons = persons.map(p => p.copy(householdId = updatedHousehold.id))
          (updatedHousehold, updatedPersons)
        }
        totalNumberOfHouseholds += households.values.size

        householdWithPersons.foreach { case (household, persons) =>
          val hh_children_val = if (household.numOfChildren >= 1) "yes" else "no"
          householdCsvWriter.write(
            household.numOfPersons,
            household.numOfVehicles,
            household.income,
            hh_children_val,
            household.numOfWorkers,
            household.geoId.state,
            household.geoId.county,
            household.geoId.tract,
            household.geoId.blockGroup,
            household.id
          )

          persons.foreach { person =>
            val gender = person.gender match {
              case Gender.Male   => 1
              case Gender.Female => 2
            }
            peopleCsvWriter.write(person.age, gender, person.householdId)
          }
        }
      }
    } finally {
      Try(householdCsvWriter.close())
      Try(peopleCsvWriter.close())
    }
  }
}
