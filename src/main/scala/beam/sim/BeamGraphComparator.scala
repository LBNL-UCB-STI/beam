package beam.sim

import java.io.{BufferedWriter, File, FileWriter, IOException}

import org.apache.commons.io.{FileUtils, FilenameUtils}
import org.matsim.core.controler.events.ControlerEvent

import scala.collection.immutable.ListMap
import scala.xml.Elem

/**
  * @author Bhavya Latha Bandaru.
  * Generates a HTML page to compare the graphs across all iterations.
  */

object BeamGraphComparator {

  /**
    * Generates the html page for graph comparison
    * @param files Map that maps file name to the absolute file path across all iterations.
    * @param iterationsCount Total number of iterations.
    * @return Graph html as scala elem
    */
  private def generateHtml(files : Map[String, Array[(String, File)]],iterationsCount : Int): Elem = {
    val scriptAllImages =
      """function displayAllGraphs(images){
           var counter = 0;
           images.map(function(i) {
             var holder = document.getElementById('imageHolder' + counter);
             holder.src = i;
             counter++;
           })
           counter = 0;
         }
      """.stripMargin
    def displayAllGraphs(images: Array[String]) = s"displayAllGraphs([${images.map(i =>s"\'$i\'").mkString(",")}]);"
    val graphs: Elem = <ul class="list-group">
      {
      ListMap(files.toSeq.sortBy(_._1): _*) map { t =>
        <li class="list-group-item">
          <h4><a href="javascript:" onclick={displayAllGraphs(t._2.map(_._2.getAbsolutePath))}>{t._1}</a></h4>
        </li>
      }
      }
    </ul>
    val imageHolders: Seq[Elem] = for(i <- 0 to 3) yield{
      val id = "imageHolder" + i
        <img id={id}/>
    }
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
          {scriptAllImages}
        </script>
      </head>
      <body>
        <div class="container">
          <h1 class="text-center">Graph Comparisons</h1>
          <br/>
          <div class="row">
            <div class="col-sm-4">
              {graphs}
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
    *
    * @param event A matsim controller event
    * @param firstIteration value of first iteration
    * @param lastIteration value of last iteration
    */
  def generateGraphComparisonHtmlPage(event: ControlerEvent,firstIteration: Int,lastIteration: Int): Unit = {
    // Yield all the png files (graph images) across all iterations
    val files: Seq[Array[File]] = for(i <- firstIteration to lastIteration) yield {
      FileUtils.getFile(new File(event.getServices.getControlerIO.getIterationPath(i))).listFiles()
        .filter(f => FilenameUtils.getExtension(f.getName).equalsIgnoreCase("png"))
    }
    val numberOfIterations = files.size
    val fileNameRegex = "([0-9]*).(.*)(.png)".r
    // Group all yielded files based on the graph names (file name w/o iteration prefixes)
    val groupedCharts : Map[String, Array[(String, File)]] = (files.reduce(_ ++ _) map { f =>
      (f.getName match {
        case fileNameRegex(_,name,_) => name
        case _ => ""
      }) -> f
    }).groupBy(_._1)
    //Generate graph comparison html element and write it to the html page at desired location
    val bw = new BufferedWriter(new FileWriter(event.getServices.getControlerIO.getOutputPath + "/comparison.html"))
    val htmlElem = this.generateHtml(groupedCharts,numberOfIterations)
    try {
      bw.write(htmlElem.mkString)
    }
    catch {
      case e: IOException =>
        e.printStackTrace()
    } finally {
      bw.close()
    }
  }
}
