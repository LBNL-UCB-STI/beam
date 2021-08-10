package beam.sim

import java.io.{BufferedWriter, File, FileWriter, IOException}

import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.{FileUtils, FilenameUtils}
import org.matsim.core.controler.events.ControlerEvent
import play.api.libs.json.{JsObject, Json}

import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.xml.{Elem, NodeBuffer}

/**
  * @author Bhavya Latha Bandaru.
  * Generates a HTML page to compare the graphs across all iterations.
  */

object BeamGraphComparator extends LazyLogging {

  /**
    * Generates the html page for graph comparison
    * @param files Map that maps file name to the absolute file path across all iterations.
    * @param iterationsCount Total number of iterations.
    * @return Graph html as scala elem
    */
  private def generateHtml(
    files: mutable.HashMap[(String, String), Map[String, Array[(String, File)]]],
    iterationsCount: Int,
    event: ControlerEvent
  ): Elem = {
    val scriptToDisplayAllImages =
      """function displayAllGraphs(images){
           var counter = 0;
           images.map(function(i) {
             var holder = document.getElementById('imageHolder' + counter);
             var name = document.getElementById('imageName' + counter);
             holder.src = i.path;
             name.innerHTML = i.name;
             counter++;
           })
           counter = 0;
         }
      """.stripMargin

    /**
      * On click listener for graph links
      * @param imageObjects A json object containing required details of the image file
      * @return
      */
    def displayAllGraphs(imageObjects: Array[JsObject]) =
      s"displayAllGraphs(${Json.stringify(Json.toJson(imageObjects))});"

    // Main menu for graph selection
    val graphMenu: Elem = <ul class="list-group">
      {
      ListMap(files.toSeq.sortBy(_._1._1): _*) map { grp =>
        <li class="list-group-item" style="word-wrap: break-word;">
          <strong>{grp._1._2}</strong>
          <ul>
            {
          ListMap(grp._2.toSeq.sortBy(_._1): _*) map { t =>
            <li>
                <h4><a href="javascript:" onclick={
              displayAllGraphs(t._2 map { f =>
                Json.obj(
                  "path" -> f._2.getCanonicalPath.replace(event.getServices.getControlerIO.getOutputPath + "/", ""),
                  "name" -> f._2.getName
                )
              })
            }>{t._1}</a></h4>
              </li>
          }
        }
          </ul>
        </li>
      }
    }
    </ul>

    // Generate holder blocks to display the selected images and their titles
    val imageHolders: Seq[NodeBuffer] = for (i <- 0 to iterationsCount) yield {
      val holderId = "imageHolder" + i
      val imageName = "imageName" + i
      <h4 align="center" id={imageName}></h4>
          <img id={holderId}/>
          <br/><br/><br/><hr/><br/>
    }

    // The final html code to display all graphs.
    <html>
      <head>
        <meta charset="utf-8"/>
        <meta http-equiv="X-UA-Compatible" content="IE=edge"/>
        <meta name="viewport" content="width=device-width, initial-scale=1"/>

        <title>Graph Comparisons</title>

        <link rel="stylesheet" href="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css"/>
        <link href="https://fonts.googleapis.com/css?family=Droid+Sans:400,700" rel="stylesheet"/>
        <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/baguettebox.js/1.8.1/baguetteBox.min.css"/>
        <link rel="stylesheet" href="gallery-clean.css"/>

        <script type="text/javascript">
          {scriptToDisplayAllImages}
        </script>
      </head>
      <body>
        <div class="container">
          <h1 class="text-center">Graph Comparisons</h1>
          <br/>
          <div class="row">
            <div class="col-sm-4">
              {graphMenu}
            </div>
            <div class="col-sm-8">
              <div class="imageholder" style="position: fixed; bottom: 0%; top: 10%">
                <div style="overflow-y: scroll;height: 100%;">
                  {imageHolders}
                </div>
              </div>
            </div>
          </div>
        </div>
      </body>
    </html>
  }

  /**
    * @param event A matsim controller event
    * @param firstIteration value of first iteration
    * @param lastIteration value of last iteration
    */
  def generateGraphComparisonHtmlPage(event: ControlerEvent, firstIteration: Int, lastIteration: Int): Unit = {
    val existingIterations = (firstIteration to lastIteration).filter { i =>
      FileUtils.getFile(new File(event.getServices.getControlerIO.getIterationPath(i))).listFiles() != null
    }
    // Yield all the png files (graph images) across all iterations
    val files: Seq[Array[File]] = for (i <- existingIterations) yield {
      (FileUtils
        .getFile(new File(event.getServices.getControlerIO.getIterationPath(i)))
        .listFiles
        .filterNot(_ == null)
        .filter(f => f.isDirectory && f.getName.equalsIgnoreCase("tripHistogram"))
        .flatMap(_.listFiles()) ++
      FileUtils.getFile(new File(event.getServices.getControlerIO.getIterationPath(i))).listFiles())
        .filter(f => FilenameUtils.getExtension(f.getName).equalsIgnoreCase("png"))
    }
    val numberOfIterations = files.size
    val fileNameRegex = "([0-9]*).(.*)(.png)".r
    // Group all yielded files based on the graph names (file name w/o iteration prefixes)
    val fileNames = files.reduce(_ ++ _) map { f =>
      (f.getName match {
        case fileNameRegex(_, name, _) => name
        case _                         => ""
      }) -> f
    }

    val knownChartPrefixes = List(
      "rideHail",
      "passengerPerTrip",
      "legHistogram",
      "averageTravelTimes",
      "energyUse",
      "modeChoice",
      "physsim",
      "realizedMode",
      "tripHistogram",
      "freeFlowSpeedDistribution"
    )
    val chartsGroupedByPrefix: Map[String, Map[String, Array[(String, File)]]] =
      fileNames.groupBy(_._1) groupBy (grouping =>
        knownChartPrefixes
          .collectFirst { case prefix if grouping._1.startsWith(prefix) => prefix.capitalize }
          .getOrElse("Misc")
      )
    val subGroups = mutable.HashMap.empty[(String, String), Map[String, Array[(String, File)]]]
    // set priorities for the grouped chart files
    chartsGroupedByPrefix.foreach(gc => {
      if (gc._2.size == 1) {
        val key = gc._2.headOption.map(_._1).getOrElse("")
        key match {
          case "modeChoice"   => subGroups.put("P01" -> key, gc._2)
          case "energyUse"    => subGroups.put("P02" -> key, gc._2)
          case "realizedMode" => subGroups.put("P03" -> key, gc._2)
          case "misc"         => subGroups.put("P50" -> key, gc._2)
          case _ =>
            val map = subGroups.getOrElse("P04" -> key, Map.empty)
            subGroups.put("P04" -> key, gc._2 ++ map)
        }
      } else
        subGroups.put("P04" + gc._1.headOption.getOrElse("") -> gc._1, gc._2)
    })
    //Generate graph comparison html element and write it to the html page at desired location
    val bw = new BufferedWriter(new FileWriter(event.getServices.getControlerIO.getOutputPath + "/comparison.html"))
    val htmlElem = this.generateHtml(subGroups, numberOfIterations, event)
    try {
      bw.write(htmlElem.mkString)
    } catch {
      case e: IOException => logger.error("exception occurred due to ", e)
    } finally {
      bw.close()
    }
  }

}
