package beam.utils.json

import akka.actor.ActorRef
import beam.agentsim.agents.vehicles.PassengerSchedule.Manifest
import beam.agentsim.agents.vehicles.PersonIdWithActorRef
import io.circe.Decoder.Result
import io.circe.{Decoder, Encoder, HCursor, Json}
import org.matsim.api.core.v01.Id

object ManifestFormat extends Encoder[Manifest] with Decoder[Manifest] {

  override def apply(a: Manifest): Json = {
    Json.obj(
      ("riders", Json.fromValues(a.riders.map(r => Json.fromString(r.personId.toString)))),
      ("boarders", Json.fromValues(a.boarders.map(r => Json.fromString(r.personId.toString)))),
      ("alighters", Json.fromValues(a.alighters.map(r => Json.fromString(r.personId.toString))))
    )
  }

  override def apply(c: HCursor): Result[Manifest] = {
    for {
      riders <- c
        .downField("riders")
        .as[List[String]]
        .map(xs => xs.map(r => PersonIdWithActorRef(Id.createPersonId(r), ActorRef.noSender)).toSet)
      boarders <- c
        .downField("boarders")
        .as[List[String]]
        .map(xs => xs.map(r => PersonIdWithActorRef(Id.createPersonId(r), ActorRef.noSender)).toSet)
      alighters <- c
        .downField("alighters")
        .as[List[String]]
        .map(xs => xs.map(r => PersonIdWithActorRef(Id.createPersonId(r), ActorRef.noSender)).toSet)
    } yield {
      Manifest(riders = riders, boarders = boarders, alighters = alighters)
    }
  }
}
