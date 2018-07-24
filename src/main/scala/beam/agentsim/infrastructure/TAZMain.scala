package beam.agentsim.infrastructure

object TAZMain extends App {

  /*
  val shapeFile: String = "Y:\\tmp\\beam\\tl_2011_06_taz10\\tl_2011_06_taz10.shp"
  val taz=new TAZTreeMap(shapeFile, "TAZCE10")

// TODO: attriutes or xml from config file - allow specifying multiple files
    // create test attributes data for starting


  val tazParkingAttributesFilePath="Y:\\tmp\\beam\\infrastructure\\tazParkingAttributes.xml"

  val tazParkingAttributes: ObjectAttributes =new ObjectAttributes()

  tazParkingAttributes.putAttribute(Parking.PARKING_MANAGER, "className", "beam.agentsim.infrastructure.BayAreaParkingManager")

  for (tazVal:TAZ <-taz.tazQuadTree.values()){
    tazParkingAttributes.putAttribute(tazVal.tazId.toString, Parking.PARKING_TYPE, ParkingType.PARKING_WITH_CHARGER)
    tazParkingAttributes.putAttribute(tazVal.tazId.toString, Parking.PARKING_CAPACITY, 1.toString)
    tazParkingAttributes.putAttribute(tazVal.tazId.toString, Parking.HOURLY_RATE, 1.0.toString)
    tazParkingAttributes.putAttribute(tazVal.tazId.toString, Parking.CHARGING_LEVEL, ChargerLevel.L2)
  }

  //TODO: convert shape file to taz csv.
  // create script for that to use sometimes.
  //#TAZ params
  //  beam.agentsim.taz.file=""
  //#Parking params
  //  beam.agentsim.infrastructure.parking.attributesFilePaths=""




  ObjectAttributesUtils.writeObjectAttributesToCSV(tazParkingAttributes,tazParkingAttributesFilePath)

  println(shapeFile)

  println(taz.getId(-120.8043534,+35.5283106))
   */

  //TAZTreeMap.shapeFileToCsv("Y:\\tmp\\beam\\tl_2011_06_taz10\\tl_2011_06_taz10.shp","TAZCE10","Y:\\tmp\\beam\\taz-centers.csv")

  //  println("HELLO WORLD")
  //  val path = "d:/shape_out.csv.gz"
  //  val mapTaz = TAZTreeMap.fromCsv(path)
  //  print(mapTaz)

  //Test Write File
  if (null != args && 3 == args.length) {
    println("Running conversion")
    val pathFileShape = args(0)
    val tazIdName = args(1)
    val destination = args(2)

    println("Process Started")
    TAZTreeMap.shapeFileToCsv(pathFileShape, tazIdName, destination)
    println("Process Terminate...")
  } else {
    println("Please specify: shapeFilePath tazIDFieldName destinationFilePath")
  }

}
