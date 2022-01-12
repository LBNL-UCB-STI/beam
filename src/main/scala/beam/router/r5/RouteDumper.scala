package beam.router.r5

import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{EmbodyWithCurrentTravelTime, RoutingRequest, RoutingResponse}
import beam.router.model.BeamLeg
import beam.sim.BeamServices
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import org.apache.avro.{Schema, SchemaBuilder}
import org.apache.avro.Schema.Type
import org.apache.avro.generic.GenericData
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.ParquetWriter
import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.JavaConverters._
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.events.{IterationEndsEvent, IterationStartsEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, IterationStartsListener}

import java.util.Objects
import scala.reflect.ClassTag

/**
  * Set `beam.outputs.writeR5RoutesInterval` config option to a number more than 0
  * to write all router requests and responses to parquet file.
  *
  * That number means how frequent routes will be written,
  * `1` - each iteration
  * `2` - every second iteration.
  *
  * Each row in the resulting file will correspond to one Beam leg for each itinerary in RoutingResponse
  * So for one RoutingResponse there will be multiple rows.
  *
  * and so on
  */
class RouteDumper(beamServices: BeamServices)
    extends BasicEventHandler
    with IterationStartsListener
    with IterationEndsListener {
  import RouteDumper._

  private val controllerIO: OutputDirectoryHierarchy = beamServices.matsimServices.getControlerIO

  @volatile
  private var routingRequestWriter: Option[ParquetWriter[GenericData.Record]] = None

  @volatile
  private var embodyWithCurrentTravelTimeWriter: Option[ParquetWriter[GenericData.Record]] = None

  @volatile
  private var routingResponseWriter: Option[ParquetWriter[GenericData.Record]] = None

  @volatile
  private var currentIteration: Int = 0

  def shouldWrite(iteration: Int): Boolean = {
    val interval = beamServices.beamConfig.beam.outputs.writeR5RoutesInterval
    interval > 0 && iteration % interval == 0
  }

  override def handleEvent(event: Event): Unit = {
    if (shouldWrite(currentIteration)) {
      event match {
        case event: RoutingRequestEvent =>
          routingRequestWriter.foreach(_.write(RouteDumper.toRecord(event.routingRequest)))
        case event: EmbodyWithCurrentTravelTimeEvent =>
          val record = RouteDumper.toRecord(event.embodyWithCurrentTravelTime)
          embodyWithCurrentTravelTimeWriter.foreach(_.write(record))
        case event: RoutingResponseEvent =>
          val records =
            RouteDumper.toRecords(event.routingResponse)
          routingResponseWriter.foreach { writer =>
            records.forEach(x => writer.write(x))
          }
        case _ =>
      }
    }
  }

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    currentIteration = event.getIteration
    if (shouldWrite(currentIteration)) {
      routingRequestWriter = Some(
        createWriter(
          controllerIO.getIterationFilename(event.getIteration, "routingRequest.parquet"),
          RouteDumper.routingRequestSchema
        )
      )
      embodyWithCurrentTravelTimeWriter = Some(
        createWriter(
          controllerIO.getIterationFilename(event.getIteration, "embodyWithCurrentTravelTime.parquet"),
          RouteDumper.embodyWithCurrentTravelTimeSchema
        )
      )
      routingResponseWriter = Some(
        createWriter(
          controllerIO.getIterationFilename(event.getIteration, "routingResponse.parquet"),
          RouteDumper.routingResponseSchema
        )
      )
    } else {
      routingRequestWriter = None
      embodyWithCurrentTravelTimeWriter = None
      routingResponseWriter = None
    }
  }

  private def createWriter(path: String, schema: Schema): ParquetWriter[GenericData.Record] = {
    AvroParquetWriter
      .builder[GenericData.Record](
        new Path(path)
      )
      .withSchema(schema)
      .withCompressionCodec(CompressionCodecName.SNAPPY)
      .build()
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    routingRequestWriter.foreach(_.close())
    embodyWithCurrentTravelTimeWriter.foreach(_.close())
    routingResponseWriter.foreach(_.close())
  }
}

object RouteDumper {

  case class RoutingRequestEvent(routingRequest: RoutingRequest) extends Event(routingRequest.departureTime) {
    override def getEventType: String = "RoutingRequestEvent"
  }

  case class EmbodyWithCurrentTravelTimeEvent(embodyWithCurrentTravelTime: EmbodyWithCurrentTravelTime)
      extends Event(embodyWithCurrentTravelTime.leg.startTime) {
    override def getEventType: String = "EmbodyWithCurrentTravelTimeEvent"
  }

  case class RoutingResponseEvent(routingResponse: RoutingResponse)
      extends Event(
        routingResponse.itineraries.headOption.flatMap(_.beamLegs.headOption).map(_.startTime.toDouble).getOrElse(-1.0)
      ) {
    override def getEventType: String = "RoutingResponseEvent"
  }

  import scala.reflect.classTag

  def requestIdField: Schema.Field = {
    new Schema.Field("requestId", Schema.create(Type.INT), "requestId", null.asInstanceOf[Any])
  }

  def toRecord(spaceTime: SpaceTime): GenericData.Record = {
    val record = new GenericData.Record(spaceTimeSchema)
    record.put("loc_x", spaceTime.loc.getX)
    record.put("loc_y", spaceTime.loc.getY)
    record.put("time", spaceTime.time)
    record
  }

  def toRecord(streetVehicle: StreetVehicle): GenericData.Record = {
    val record = new GenericData.Record(streetVehicleSchema)
    record.put("id", streetVehicle.id.toString)
    record.put("vehicleTypeId", streetVehicle.vehicleTypeId.toString)
    record.put("locationUTM_X", streetVehicle.locationUTM.loc.getX)
    record.put("locationUTM_Y", streetVehicle.locationUTM.loc.getY)
    record.put("locationUTM_time", streetVehicle.locationUTM.time)
    record.put("mode", streetVehicle.mode.value)
    record.put("asDriver", streetVehicle.asDriver)
    record
  }

  def toRecord(streetVehicles: IndexedSeq[StreetVehicle]): GenericData.Array[Any] = {
    val arr = new GenericData.Array[Any](streetVehicles.length, Schema.createArray(streetVehicleSchema))
    streetVehicles.zipWithIndex.foreach { case (sv, idx) =>
      arr.add(idx, toRecord(sv))
    }
    arr
  }

  def toRecord(householdAttributes: HouseholdAttributes): GenericData.Record = {
    val record = new GenericData.Record(householdAttributesSchema)
    record.put("householdId", householdAttributes.householdId)
    record.put("householdIncome", householdAttributes.householdIncome)
    record.put("householdSize", householdAttributes.householdSize)
    record.put("numCars", householdAttributes.numCars)
    record.put("numBikes", householdAttributes.numBikes)
    record
  }

  def toRecord(attributesOfIndividual: AttributesOfIndividual): GenericData.Record = {
    val record = new GenericData.Record(attributesOfIndividualSchema)
    record.put("householdAttributes", toRecord(attributesOfIndividual.householdAttributes))
    record.put("modalityStyle", attributesOfIndividual.modalityStyle.orNull)
    record.put("isMale", attributesOfIndividual.isMale)
    record.put("availableModes", attributesOfIndividual.availableModes.map(_.value).mkString(" "))
    record.put("valueOfTime", attributesOfIndividual.valueOfTime)
    attributesOfIndividual.age.foreach(record.put("age", _))
    attributesOfIndividual.income.foreach(record.put("income", _))
    record
  }

  def toRecord(routingRequest: RoutingRequest): GenericData.Record = {
    val record = new GenericData.Record(routingRequestSchema)
    record.put("requestId", routingRequest.requestId)
    record.put("originUTM_X", routingRequest.originUTM.getX)
    record.put("originUTM_Y", routingRequest.originUTM.getY)
    record.put("destinationUTM_X", routingRequest.destinationUTM.getX)
    record.put("destinationUTM_Y", routingRequest.destinationUTM.getY)
    record.put("departureTime", routingRequest.departureTime)
    record.put("withTransit", routingRequest.withTransit)
    record.put("streetVehicles", toRecord(routingRequest.streetVehicles))
    routingRequest.attributesOfIndividual.foreach { attibs =>
      record.put("attributesOfIndividual", toRecord(attibs))
    }
    record.put("streetVehiclesUseIntermodalUse", routingRequest.streetVehiclesUseIntermodalUse.toString)
    record.put("initiatedFrom", routingRequest.initiatedFrom)
    record
  }

  def toRecord(embodyWithCurrentTravelTime: EmbodyWithCurrentTravelTime): GenericData.Record = {
    val record = new GenericData.Record(embodyWithCurrentTravelTimeSchema)
    record.put("requestId", embodyWithCurrentTravelTime.requestId)
    record.put("vehicleId", Option(embodyWithCurrentTravelTime.vehicleId).map(_.toString).orNull)
    record.put("vehicleTypeId", Option(embodyWithCurrentTravelTime.vehicleTypeId).map(_.toString).orNull)

    // We add leg fields to this object - kind of explode it so easier to query the data
    addToRecord(record, embodyWithCurrentTravelTime.leg)

    record
  }

  def toRecords(routingResponse: RoutingResponse): java.util.ArrayList[GenericData.Record] = {
    val records = new java.util.ArrayList[GenericData.Record]
    routingResponse.itineraries.zipWithIndex.foreach { case (itinerary, itineraryIndex) =>
      itinerary.beamLegs.zipWithIndex.foreach { case (leg, legIndex) =>
        val record = new GenericData.Record(routingResponseSchema)
        record.put("requestId", routingResponse.requestId)
        record.put("isEmbodyWithCurrentTravelTime", routingResponse.isEmbodyWithCurrentTravelTime)

        record.put("itineraryIndex", itineraryIndex)
        record.put("router", itinerary.router.mkString)
        record.put("costEstimate", itinerary.costEstimate)
        record.put("tripClassifier", itinerary.tripClassifier.value)
        record.put("replanningPenalty", itinerary.replanningPenalty)
        record.put("totalTravelTimeInSecs", itinerary.totalTravelTimeInSecs)

        record.put("legIndex", legIndex)
        addToRecord(record, leg)
        records.add(record)
      }
    }
    records
  }

  def addToRecord(record: GenericData.Record, beamLeg: BeamLeg): Unit = {
    record.put("startTime", beamLeg.startTime)
    record.put("mode", beamLeg.mode.value)
    record.put("duration", beamLeg.duration)
    record.put("linkIds", beamLeg.travelPath.linkIds.toArray)
    record.put("linkTravelTime", beamLeg.travelPath.linkTravelTime.toArray)
    beamLeg.travelPath.transitStops.foreach { transitStop =>
      record.put("transitStops_agencyId", transitStop.agencyId)
      record.put("transitStops_routeId", transitStop.routeId)
      record.put("transitStops_vehicleId", transitStop.vehicleId.toString)
      record.put("transitStops_fromIdx", transitStop.fromIdx)
      record.put("transitStops_toIdx", transitStop.toIdx)
    }
    record.put("startPoint_X", beamLeg.travelPath.startPoint.loc.getX)
    record.put("startPoint_Y", beamLeg.travelPath.startPoint.loc.getY)
    record.put("startPoint_time", beamLeg.travelPath.startPoint.time)
    record.put("endPoint_X", beamLeg.travelPath.endPoint.loc.getX)
    record.put("endPoint_Y", beamLeg.travelPath.endPoint.loc.getY)
    record.put("endPoint_time", beamLeg.travelPath.endPoint.time)
    record.put("distanceInM", beamLeg.travelPath.distanceInM)
  }

  def beamLegFields: List[Schema.Field] = {
    val startTime = {
      new Schema.Field("startTime", Schema.create(Type.INT), "startTime", null.asInstanceOf[Any])
    }
    val mode = {
      new Schema.Field("mode", Schema.create(Type.STRING), "mode", null.asInstanceOf[Any])
    }
    val duration = {
      new Schema.Field("duration", Schema.create(Type.INT), "duration", null.asInstanceOf[Any])
    }
    val linkIds = {
      new Schema.Field("linkIds", Schema.createArray(Schema.create(Type.INT)), "linkIds", null.asInstanceOf[Any])
    }
    val linkTravelTime = {
      new Schema.Field(
        "linkTravelTime",
        Schema.createArray(Schema.create(Type.DOUBLE)),
        "linkTravelTime",
        null.asInstanceOf[Any]
      )
    }
    val transitStops = {
      List(
        new Schema.Field("transitStops_agencyId", nullable[String], "transitStops_agencyId", null.asInstanceOf[Any]),
        new Schema.Field("transitStops_routeId", nullable[String], "transitStops_routeId", null.asInstanceOf[Any]),
        new Schema.Field("transitStops_vehicleId", nullable[String], "transitStops_vehicleId", null.asInstanceOf[Any]),
        new Schema.Field("transitStops_fromIdx", nullable[Int], "transitStops_fromIdx", null.asInstanceOf[Any]),
        new Schema.Field("transitStops_toIdx", nullable[Int], "transitStops_toIdx", null.asInstanceOf[Any])
      )
    }
    val startPoint_X = {
      new Schema.Field("startPoint_X", Schema.create(Type.DOUBLE), "startPoint_X", null.asInstanceOf[Any])
    }
    val startPoint_Y = {
      new Schema.Field("startPoint_Y", Schema.create(Type.DOUBLE), "startPoint_Y", null.asInstanceOf[Any])
    }
    val startPoint_time = {
      new Schema.Field("startPoint_time", Schema.create(Type.INT), "startPoint_time", null.asInstanceOf[Any])
    }
    val endPoint_X = {
      new Schema.Field("endPoint_X", Schema.create(Type.DOUBLE), "endPoint_X", null.asInstanceOf[Any])
    }
    val endPoint_Y = {
      new Schema.Field("endPoint_Y", Schema.create(Type.DOUBLE), "endPoint_Y", null.asInstanceOf[Any])
    }
    val endPoint_time = {
      new Schema.Field("endPoint_time", Schema.create(Type.INT), "endPoint_time", null.asInstanceOf[Any])
    }
    val distanceInM = {
      new Schema.Field("distanceInM", Schema.create(Type.DOUBLE), "distanceInM", null.asInstanceOf[Any])
    }
    List(
      startTime,
      mode,
      duration,
      linkIds,
      linkTravelTime,
      startPoint_X,
      startPoint_Y,
      startPoint_time,
      endPoint_X,
      endPoint_Y,
      endPoint_time,
      distanceInM
    ) ++ transitStops
  }

  val routingResponseSchema: Schema = {
    val isEmbodyWithCurrentTravelTime = new Schema.Field(
      "isEmbodyWithCurrentTravelTime",
      Schema.create(Type.BOOLEAN),
      "isEmbodyWithCurrentTravelTime",
      null.asInstanceOf[Any]
    )

    val itineraryIndex =
      new Schema.Field("itineraryIndex", Schema.create(Type.INT), "itineraryIndex", null.asInstanceOf[Any])
    val router =
      new Schema.Field("router", Schema.create(Type.STRING), "router", null.asInstanceOf[Any])
    val costEstimate =
      new Schema.Field("costEstimate", Schema.create(Type.DOUBLE), "costEstimate", null.asInstanceOf[Any])
    val tripClassifier =
      new Schema.Field("tripClassifier", nullable[String], "tripClassifier", null.asInstanceOf[Any])
    val replanningPenalty =
      new Schema.Field("replanningPenalty", Schema.create(Type.DOUBLE), "replanningPenalty", null.asInstanceOf[Any])
    val totalTravelTimeInSecs = new Schema.Field(
      "totalTravelTimeInSecs",
      Schema.create(Type.INT),
      "totalTravelTimeInSecs",
      null.asInstanceOf[Any]
    )

    val legIndex = new Schema.Field("legIndex", Schema.create(Type.INT), "legIndex", null.asInstanceOf[Any])

    val fields = List(
      requestIdField,
      isEmbodyWithCurrentTravelTime,
      itineraryIndex,
      router,
      costEstimate,
      tripClassifier,
      replanningPenalty,
      totalTravelTimeInSecs,
      legIndex
    ) ++ beamLegFields
    Schema.createRecord("routingResponse", "", "", false, fields.asJava)
  }

  val embodyWithCurrentTravelTimeSchema: Schema = {
    val vehicleId = {
      new Schema.Field("vehicleId", nullable[String], "vehicleId", null.asInstanceOf[Any])
    }
    val vehicleTypeId = {
      new Schema.Field("vehicleTypeId", nullable[String], "vehicleTypeId", null.asInstanceOf[Any])
    }
    val fields = List(requestIdField, vehicleId, vehicleTypeId) ++ beamLegFields
    Schema.createRecord("embodyWithCurrentTravelTime", "", "", false, fields.asJava)
  }

  val householdAttributesSchema: Schema = {
    val fields = List(
      new Schema.Field("householdId", nullable[String], "householdId", null.asInstanceOf[Any]),
      new Schema.Field("householdIncome", nullable[Double], "householdIncome", null.asInstanceOf[Any]),
      new Schema.Field("householdSize", nullable[Int], "householdSize", null.asInstanceOf[Any]),
      new Schema.Field("numCars", nullable[Int], "numCars", null.asInstanceOf[Any]),
      new Schema.Field("numBikes", nullable[Int], "numBikes", null.asInstanceOf[Any])
    )
    Schema.createRecord("HouseholdAttributes", "", "", false, fields.asJava)
  }

  val attributesOfIndividualSchema: Schema = {
    val fields = List(
      new Schema.Field("householdAttributes", householdAttributesSchema, "householdAttributes", null.asInstanceOf[Any]),
      new Schema.Field("modalityStyle", nullable[String], "modalityStyle", null.asInstanceOf[Any]),
      new Schema.Field("isMale", nullable[Boolean], "isMale", null.asInstanceOf[Any]),
      new Schema.Field("availableModes", nullable[String], "availableModes", null.asInstanceOf[Any]),
      new Schema.Field("valueOfTime", nullable[Double], "valueOfTime", null.asInstanceOf[Any]),
      new Schema.Field("age", nullable[Int], "age", null.asInstanceOf[Any]),
      new Schema.Field("income", nullable[Double], "income", null.asInstanceOf[Any])
    )
    Schema.createRecord("AttributesOfIndividual", "", "", false, fields.asJava)
  }

  val spaceTimeSchema: Schema = {
    val fields = List(
      new Schema.Field("loc_x", nullable[Double], "loc_x", null.asInstanceOf[Any]),
      new Schema.Field("loc_y", nullable[Double], "loc_y", null.asInstanceOf[Any]),
      new Schema.Field("time", nullable[Int], "time", null.asInstanceOf[Any])
    )
    Schema.createRecord("SpaceTimeSchema", "", "", false, fields.asJava)
  }

  val streetVehicleSchema: Schema = {
    val fields = List(
      new Schema.Field("id", nullable[String], "id", null.asInstanceOf[Any]),
      new Schema.Field("vehicleTypeId", nullable[String], "vehicleTypeId", null.asInstanceOf[Any]),
      //new Schema.Field("locationUTM", spaceTimeSchema, "locationUTM", null.asInstanceOf[Any]),
      new Schema.Field("locationUTM_X", nullable[Double], "locationUTM_X", null.asInstanceOf[Any]),
      new Schema.Field("locationUTM_Y", nullable[Double], "locationUTM_Y", null.asInstanceOf[Any]),
      new Schema.Field("locationUTM_time", nullable[Int], "locationUTM_time", null.asInstanceOf[Any]),
      new Schema.Field("mode", nullable[String], "mode", null.asInstanceOf[Any]),
      new Schema.Field("asDriver", nullable[Boolean], "asDriver", null.asInstanceOf[Any])
    )
    Schema.createRecord("StreetVehicle", "", "", false, fields.asJava)
  }

  val routingRequestSchema: Schema = {
    val originUTM_X = {
      new Schema.Field("originUTM_X", Schema.create(Type.DOUBLE), "originUTM_X", null.asInstanceOf[Any])
    }
    val originUTM_Y = {
      new Schema.Field("originUTM_Y", Schema.create(Type.DOUBLE), "originUTM_Y", null.asInstanceOf[Any])
    }
    val destinationUTM_X = {
      new Schema.Field("destinationUTM_X", Schema.create(Type.DOUBLE), "destinationUTM_X", null.asInstanceOf[Any])
    }
    val destinationUTM_Y = {
      new Schema.Field("destinationUTM_Y", Schema.create(Type.DOUBLE), "destinationUTM_Y", null.asInstanceOf[Any])
    }
    val departureTime = {
      new Schema.Field("departureTime", Schema.create(Type.INT), "departureTime", null.asInstanceOf[Any])
    }
    val withTransit = {
      new Schema.Field("withTransit", Schema.create(Type.BOOLEAN), "withTransit", null.asInstanceOf[Any])
    }
    val streetVehicles = {
      new Schema.Field(
        "streetVehicles",
        Schema.createArray(streetVehicleSchema),
        "streetVehicles",
        null.asInstanceOf[Any]
      )
    }
    val attributesOfIndividual = {
      new Schema.Field(
        "attributesOfIndividual",
        SchemaBuilder.unionOf().nullType().and().`type`(attributesOfIndividualSchema).endUnion(),
        "attributesOfIndividual",
        null.asInstanceOf[Any]
      )
    }
    val streetVehiclesUseIntermodalUse = {
      new Schema.Field(
        "streetVehiclesUseIntermodalUse",
        Schema.create(Type.STRING),
        "streetVehiclesUseIntermodalUse",
        null.asInstanceOf[Any]
      )
    }
    val initiatedFrom = {
      new Schema.Field("initiatedFrom", Schema.create(Type.STRING), "initiatedFrom", null.asInstanceOf[Any])
    }
    val fields = List(
      requestIdField,
      originUTM_X,
      originUTM_Y,
      destinationUTM_X,
      destinationUTM_Y,
      departureTime,
      withTransit,
      streetVehicles,
      attributesOfIndividual,
      streetVehiclesUseIntermodalUse,
      initiatedFrom
    )
    Schema.createRecord("routingRequest", "", "", false, fields.asJava)
  }

  def nullable[T](implicit ct: ClassTag[T]): Schema = {
    val nullType = SchemaBuilder.unionOf().nullType()
    ct match {
      case ClassTag.Boolean =>
        nullType.and().booleanType().endUnion()
      case ClassTag.Int =>
        nullType.and().intType().endUnion()
      case ClassTag.Long =>
        nullType.and().longType().endUnion()
      case ClassTag.Float =>
        nullType.and().floatType().endUnion()
      case ClassTag.Double =>
        nullType.and().doubleType().endUnion()
      case x if Objects.equals(x, classTag[String]) =>
        nullType.and().stringType().endUnion()
      case x =>
        throw new IllegalStateException(s"Don't know what to do with $x")
    }
  }
}
