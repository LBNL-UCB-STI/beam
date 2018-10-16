package beam.sim

import java.io.{BufferedWriter, File, FileWriter, IOException}

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

object BeamGraphComparator {

  /**
    * Generates the html page for graph comparison
    * @param files Map that maps file name to the absolute file path across all iterations.
    * @param iterationsCount Total number of iterations.
    * @return Graph html as scala elem
    */
  private def generateHtml(files : mutable.HashMap[(String,String), Map[String, Array[(String, File)]]],iterationsCount : Int): Elem = {
    val scriptAllImages =
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
    def displayAllGraphs(images: Array[JsObject]) = s"displayAllGraphs(${Json.stringify(Json.toJson(images))});"
    val graphs: Elem = <ul class="list-group">
      {
      ListMap(files.toSeq.sortBy(_._1._1): _*) map { grp =>
        <li class="list-group-item">
          <strong>{grp._1._2}</strong>
          <ul>
            {
            ListMap(grp._2.toSeq.sortBy(_._1): _*) map { t =>
              <li>
                <h4><a href="javascript:" onclick={displayAllGraphs(t._2 map { f =>
                  Json.obj("path" -> f._2.getAbsolutePath,
                  "name" -> f._2.getName)
                })}>{t._1}</a></h4>
              </li>
            }
            }
          </ul>
        </li>
      }
      }
    </ul>
    val imageHolders: Seq[NodeBuffer] = for(i <- 0 to iterationsCount) yield {
      val holderId = "imageHolder" + i
      val imageName = "imageName" + i
      <h4 align="center" id={imageName}></h4>
          <img id={holderId}/>
        <br/><br/><br/><hr/><br/>
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
      (FileUtils.getFile(new File(event.getServices.getControlerIO.getIterationPath(i))).listFiles(f =>
        f.isDirectory && f.getName.equalsIgnoreCase("tripHistogram")).flatMap(_.listFiles()) ++
        FileUtils.getFile(new File(event.getServices.getControlerIO.getIterationPath(i))).listFiles())
        .filter(f => FilenameUtils.getExtension(f.getName).equalsIgnoreCase("png"))
    }
    val numberOfIterations = files.size
    val fileNameRegex = "([0-9]*).(.*)(.png)".r
    // Group all yielded files based on the graph names (file name w/o iteration prefixes)
    val fileNames = files.reduce(_ ++ _) map { f =>
      (f.getName match {
        case fileNameRegex(_,name,_) => name
        case _ => ""
      }) -> f
    }
    //Group chart files by name (2 level group)
    val groupedCharts: Map[String, Map[String, Array[(String, File)]]] = fileNames.groupBy(_._1).groupBy(f => {
      val index = f._1.indexOf("_")
      if(index == -1)
        f._1
      else
        f._1.substring(0,index)
    })
    val mapper = mutable.HashMap.empty[(String ,String), Map[String, Array[(String, File)]]]
    // set priorities for the grouped chart files
    groupedCharts.foreach(gc => {
      if (gc._2.size == 1){
        val key = gc._2.headOption.map(_._1).getOrElse("")
        key match {
          case "mode_choice" => mapper.put("P01" -> key,gc._2)
          case "energy_use" => mapper.put("P02" -> key,gc._2)
          case "realized_mode" => mapper.put("P03" -> key, gc._2)
          case _ =>
            val map = mapper.getOrElse("P99" -> "Misc",Map.empty)
            mapper.put("P99" -> "Misc",gc._2 ++ map )
        }
      }
      else
        mapper.put("P04"+gc._1.headOption.getOrElse("") -> gc._1,gc._2)
    })
    //Generate graph comparison html element and write it to the html page at desired location
    val bw = new BufferedWriter(new FileWriter(event.getServices.getControlerIO.getOutputPath + "/comparison.html"))
    val htmlElem = this.generateHtml(mapper,numberOfIterations)
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
