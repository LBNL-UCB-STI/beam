package beam.router.r5

import beam.router.BeamRouter.{EmbodyWithCurrentTravelTime, RoutingRequest, RoutingResponse}
import beam.router.model.BeamLeg
import beam.sim.BeamServices
import org.apache.avro.{Schema, SchemaBuilder}
import org.apache.avro.Schema.Type
import org.apache.avro.generic.GenericData
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.ParquetWriter
import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.JavaConverters._
import beam.utils.json.AllNeededFormats._
import io.circe.syntax._
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.events.{IterationEndsEvent, IterationStartsEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, IterationStartsListener}

import scala.reflect.ClassTag

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

class RouteDumper(beamServices: BeamServices)
    extends BasicEventHandler
    with IterationStartsListener
    with IterationEndsListener {
  val controllerIO: OutputDirectoryHierarchy = beamServices.matsimServices.getControlerIO

  @volatile
  var routingRequestWriter: Option[ParquetWriter[GenericData.Record]] = None

  @volatile
  var embodyWithCurrentTravelTimeWriter: Option[ParquetWriter[GenericData.Record]] = None

  @volatile
  var routingResponseWriter: Option[ParquetWriter[GenericData.Record]] = None

  @volatile
  var currentIteration: Int = 0

  def shouldWrite(iteration: Int): Boolean = {
    iteration % beamServices.beamConfig.beam.outputs.writeEventsInterval == 0
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
            RouteDumper.toRecords(event.routingResponse, event.routingResponse.isEmbodyWithCurrentTravelTime)
          routingResponseWriter.foreach { writer =>
            records.forEach(x => writer.write(x))
          }
        case _ =>
      }
    }
  }

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    currentIteration = event.getIteration
    routingRequestWriter = if (shouldWrite(currentIteration)) {
      Some(
        AvroParquetWriter
          .builder[GenericData.Record](
            new Path(controllerIO.getIterationFilename(event.getIteration, "routingRequest.parquet"))
          )
          .withSchema(RouteDumper.routingRequestSchema)
          .withCompressionCodec(CompressionCodecName.SNAPPY)
          .build()
      )
    } else { None }
    embodyWithCurrentTravelTimeWriter = if (shouldWrite(currentIteration)) {
      Some(
        AvroParquetWriter
          .builder[GenericData.Record](
            new Path(controllerIO.getIterationFilename(event.getIteration, "embodyWithCurrentTravelTime.parquet"))
          )
          .withSchema(RouteDumper.embodyWithCurrentTravelTimeSchema)
          .withCompressionCodec(CompressionCodecName.SNAPPY)
          .build()
      )
    } else {
      None
    }
    routingResponseWriter = if (shouldWrite(currentIteration)) {
      Some(
        AvroParquetWriter
          .builder[GenericData.Record](
            new Path(controllerIO.getIterationFilename(event.getIteration, "routingResponse.parquet"))
          )
          .withSchema(RouteDumper.routingResponseSchema)
          .withCompressionCodec(CompressionCodecName.SNAPPY)
          .build()
      )
    } else {
      None
    }

  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    routingRequestWriter.foreach(_.close())
    embodyWithCurrentTravelTimeWriter.foreach(_.close())
    routingResponseWriter.foreach(_.close())
  }
}

object RouteDumper {
  import scala.reflect.classTag

  def requestIdField: Schema.Field = {
    new Schema.Field("requestId", Schema.create(Type.INT), "requestId", null.asInstanceOf[Any])
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
    record.put("streetVehicles", routingRequest.streetVehicles.asJson.noSpaces)
    record.put("attributesOfIndividual", routingRequest.attributesOfIndividual.map(_.asJson.noSpaces).orNull)
    record.put("streetVehiclesUseIntermodalUse", routingRequest.streetVehiclesUseIntermodalUse.toString)
    record
  }

  def toRecord(embodyWithCurrentTravelTime: EmbodyWithCurrentTravelTime): GenericData.Record = {
    val record = new GenericData.Record(embodyWithCurrentTravelTimeSchema)
    record.put("requestId", embodyWithCurrentTravelTime.requestId)
    record.put("vehicleId", Option(embodyWithCurrentTravelTime.vehicleId).map(_.toString).orNull)
    record.put("vehicleTypeId", Option(embodyWithCurrentTravelTime.vehicleTypeId).map(_.toString).orNull)

    addToRecord(record, embodyWithCurrentTravelTime.leg)

    record
  }

  def toRecords(
    routingResponse: RoutingResponse,
    isEmbodyWithCurrentTravelTime: Boolean
  ): java.util.ArrayList[GenericData.Record] = {
    val records = new java.util.ArrayList[GenericData.Record]
    routingResponse.itineraries.zipWithIndex.foreach {
      case (itinerary, itineraryIndex) =>
        itinerary.beamLegs.zipWithIndex.foreach {
          case (leg, legIndex) =>
            val record = new GenericData.Record(routingResponseSchema)
            record.put("requestId", routingResponse.requestId)
            record.put("isEmbodyWithCurrentTravelTime", isEmbodyWithCurrentTravelTime)

            record.put("itineraryIndex", itineraryIndex)
            record.put("costEstimate", itinerary.costEstimate)
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
    record.put("mode", beamLeg.mode.toString)
    record.put("duration", beamLeg.duration)
    record.put("linkIds", beamLeg.travelPath.linkIds.asJavaCollection)
    record.put("linkTravelTime", beamLeg.travelPath.linkTravelTime.asJavaCollection)
    record.put("transitStops", beamLeg.travelPath.transitStops.map(_.asJson.noSpaces).orNull)
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
        Schema.createArray(Schema.create(Type.INT)),
        "linkTravelTime",
        null.asInstanceOf[Any]
      )
    }
    val transitStops = {
      new Schema.Field("transitStops", nullable[String], "transitStops", null.asInstanceOf[Any])
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
      transitStops,
      startPoint_X,
      startPoint_Y,
      startPoint_time,
      endPoint_X,
      endPoint_Y,
      endPoint_time,
      distanceInM
    )
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
    val costEstimate =
      new Schema.Field("costEstimate", Schema.create(Type.DOUBLE), "costEstimate", null.asInstanceOf[Any])
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
      costEstimate,
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
      new Schema.Field("streetVehicles", Schema.create(Type.STRING), "streetVehicles", null.asInstanceOf[Any])
    }
    val attributesOfIndividual = {
      new Schema.Field("attributesOfIndividual", nullable[String], "attributesOfIndividual", null.asInstanceOf[Any])
    }
    val streetVehiclesUseIntermodalUse = {
      new Schema.Field(
        "streetVehiclesUseIntermodalUse",
        Schema.create(Type.STRING),
        "streetVehiclesUseIntermodalUse",
        null.asInstanceOf[Any]
      )
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
      streetVehiclesUseIntermodalUse
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
      case x if x == classTag[String] =>
        nullType.and().stringType().endUnion()
      case x =>
        throw new IllegalStateException(s"Don't know what to do with ${x}")
    }
  }
}
