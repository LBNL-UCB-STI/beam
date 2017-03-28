package beam.agentsim.playground.sid.usecases

/**
  * Examples of how to use the scala.xml package to simplify xml processing
  *
  * Created by sfeygin on 1/27/17.
  */
object xmlParsing {

  import scala.xml.XML

  val xml = XML.loadFile("/Users/sfeygin/current_code/java/research/beam/test/output/beam/basicTests/sf-bay/sf-bay_2017-03-28_14-08-45/output_events.xml")
  val population = xml \\ "population" \ "person"
  val num = population.length
  val ids = population.map(i => i \ "@id")

  // Flatmap necessary here
  val scores = (population flatMap (i => i \\ "@score")).text.map(j => j.toDouble).sum

  // Example of for comprehension
  val filteredPop = for {
    person <- population \\ "person"
    plan <- person \ "plan"
    if (plan \\ "@selected").text equals "yes"
  } yield (plan \\ "@score").text.map(_.toDouble)

  val filteredSum = filteredPop.flatten.sum
}
