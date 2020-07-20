package beam.utils.data.synthpop

import java.io.{File, FileFilter}

import beam.utils.data.synthpop.models.Models.{Household, Person}
import com.typesafe.scalalogging.StrictLogging

class SythpopReader(val houseHoldsWithPeople: Seq[(File, File)]) {

  def read(): Map[Household, Seq[Person]] = {
    val (_, allHouseholdsWithPeople) = houseHoldsWithPeople.foldLeft((0, Map[Household, Seq[Person]]())) {
      case ((totalNumberOfPeople, resultMap), (hhFile, personFile)) =>
        val households =
          new HouseholdReader(hhFile.getAbsolutePath).read().groupBy(x => x.id).map {
            case (hhId, xs) => hhId -> xs.head
          }

        val householdIdToPersons =
          new PopulationReader(personFile.getAbsolutePath).read().zipWithIndex.groupBy { case (p, _) => p.householdId }
        val householdWithPersons = householdIdToPersons.map {
          case (hhId, persons) =>
            val household = households(hhId)
            // We can't rely on person id from people_*CSV file, so let's generate new one
            val updatedPersons = persons.map {
              case (p, idx) =>
                val globalOffset = idx + totalNumberOfPeople
                val newPersonId = s"${household.fullId}:${p.id}:$globalOffset"
                p.copy(householdId = household.fullId, id = newPersonId)
            }
            (household, updatedPersons)
        }
        val diff = resultMap.keySet.diff(householdWithPersons.keySet)
        require(diff == resultMap.keySet)
        val newMap = resultMap ++ householdWithPersons
        val newTotalNumberOfPeople = totalNumberOfPeople + householdWithPersons.map(_._2.size).sum
        (newTotalNumberOfPeople, newMap)
    }
    allHouseholdsWithPeople
  }
}

object SythpopReader extends StrictLogging {

  def apply(folderToScan: String): SythpopReader = {
    val householdFiles = new File(folderToScan)
      .listFiles(new FileFilter {
        override def accept(pathname: File): Boolean = {
          pathname.getName.matches("household_.+\\.csv")
        }
      })
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
    val hhWithPeople = householdFiles.zip(peopleFiles)
    hhWithPeople.foreach {
      case (hh, people) =>
        logger.info(s"Household: $hh")
        logger.info(s"People   : $people")
    }
    new SythpopReader(hhWithPeople)
  }
}
