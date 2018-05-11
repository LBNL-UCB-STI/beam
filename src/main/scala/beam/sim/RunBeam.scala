package beam.sim

import java.io.{ByteArrayOutputStream, FileOutputStream, ObjectOutputStream}
import java.time.ZonedDateTime

import akka.actor.{ActorSystem, ExtendedActorSystem}
import beam.router.BeamRouter.RoutingResponse
import beam.router.RoutingModel.{BeamLeg, EmbodiedBeamLeg, EmbodiedBeamTrip}
import com.romix.akka.serialization.kryo.KryoSerializer
import org.matsim.api.core.v01.Id

object RunBeam extends BeamHelper with App {
  print(
    """
  ________
  ___  __ )__________ _______ ___
  __  __  |  _ \  __ `/_  __ `__ \
  _  /_/ //  __/ /_/ /_  / / / / /
  /_____/ \___/\__,_/ /_/ /_/ /_/

 _____________________________________

 """)

//
//  val itineraries = Vector[EmbodiedBeamTrip](
//    EmbodiedBeamTrip(Vector[EmbodiedBeamLeg](
//      EmbodiedBeamLeg(BeamLeg.dummyWalk(1), beamVehicleId = Id.createVehicleId("ASDASD"),
//        asDriver = true, passengerSchedule = None, cost = BigDecimal(10), unbecomeDriverOnCompletion = true)
//    ))
//  )

//  val res = RoutingResponse(itineraries = itineraries, requestReceivedAt = ZonedDateTime.now(),
//    requestCreatedAt = ZonedDateTime.now(),
//    createdAt = ZonedDateTime.now())
//            val bos = new FileOutputStream("1.bin")
//            val out = new ObjectOutputStream(bos)
//            out.writeObject(res)
//            out.flush()
//
//  val a = new KryoSerializer(ActorSystem().asInstanceOf[ExtendedActorSystem])
//  val bos1 = new FileOutputStream("2.bin")
//  bos1.write(a.toBinary(res))
//  bos1.flush()
//  bos1.close()
// println(a.toBinary(res).length)
//
  val argsMap = parseArgs(args)

  runBeamWithConfigFile(argsMap)
  logger.info("Exiting BEAM")
}
