package beam.side.speed.parser.composer

import beam.side.speed.model.{
  AllHoursDaysDTO,
  AllHoursWeightedDTO,
  BeamLengthDTO,
  BeamSpeed,
  LinkSpeed,
  MaxHourPointsDTO,
  OsmNodeSpeed
}
import beam.side.speed.parser.operation.ObservationComposer

trait BeamComposerInterpreters {
  implicit object MaxHoursBeamSpeedComposer
      extends ObservationComposer[Option,
                                  (OsmNodeSpeed, MaxHourPointsDTO),
                                  BeamSpeed] {
    override def compose(
        input: (OsmNodeSpeed, MaxHourPointsDTO)): Option[BeamSpeed] = {
      val (osm, uber) = input
      Option(uber.speedMax)
        .filter(_ > 11)
        .filter(speed =>
          speed >= osm.speed || (uber.points > 10 && ((osm.speed - speed) / osm.speed) > 0.3))
        .map(speed => BeamSpeed(osm.id, speed, osm.lenght))
    }
  }

  implicit object MaxHoursLinkSpeedComposer
      extends ObservationComposer[Option,
                                  (OsmNodeSpeed, MaxHourPointsDTO),
                                  LinkSpeed] {
    override def compose(
        input: (OsmNodeSpeed, MaxHourPointsDTO)): Option[LinkSpeed] = {
      val (osm, uber) = input
      Some(LinkSpeed(osm.eId, None, Some(uber.speedMax), None))
    }
  }

  implicit object AllHoursWeightLinkSpeedComposer
      extends ObservationComposer[Option,
                                  (OsmNodeSpeed, AllHoursWeightedDTO),
                                  LinkSpeed] {
    override def compose(
        input: (OsmNodeSpeed, AllHoursWeightedDTO)): Option[LinkSpeed] = {
      val (osm, uber) = input
      Some(LinkSpeed(osm.eId, None, uber.speedMedian, None))
    }
  }

  implicit object AllHoursWeightBeamSpeedComposer
      extends ObservationComposer[Option,
                                  (OsmNodeSpeed, AllHoursWeightedDTO),
                                  BeamSpeed] {
    override def compose(
        input: (OsmNodeSpeed, AllHoursWeightedDTO)): Option[BeamSpeed] = {
      val (osm, uber) = input
      Some(BeamSpeed(osm.eId, uber.speedMedian.get, osm.lenght))
    }
  }

  implicit object AllHoursDayLinkSpeedComposer
      extends ObservationComposer[Option,
                                  (OsmNodeSpeed, AllHoursDaysDTO),
                                  LinkSpeed] {
    override def compose(
        input: (OsmNodeSpeed, AllHoursDaysDTO)): Option[LinkSpeed] = {
      val (osm, uber) = input
      Some(LinkSpeed(osm.eId, None, uber.speedMedian, None))
    }
  }

  implicit object AllHoursDayBeamSpeedComposer
      extends ObservationComposer[Option,
                                  (OsmNodeSpeed, AllHoursDaysDTO),
                                  BeamSpeed] {
    override def compose(
        input: (OsmNodeSpeed, AllHoursDaysDTO)): Option[BeamSpeed] = {
      val (osm, uber) = input
      Some(BeamSpeed(osm.eId, uber.speedMedian.get, osm.lenght))
    }
  }

  implicit object BeamLengthLinkSpeed
      extends ObservationComposer[Option,
                                  (OsmNodeSpeed, BeamLengthDTO),
                                  LinkSpeed] {
    override def compose(
        input: (OsmNodeSpeed, BeamLengthDTO)): Option[LinkSpeed] = {
      val (osm, uber) = input
      Some(LinkSpeed(osm.eId, None, uber.speedAvg, None))
    }
  }

  implicit object BeamLengthBeamSpeed
      extends ObservationComposer[Option,
                                  (OsmNodeSpeed, BeamLengthDTO),
                                  BeamSpeed] {
    override def compose(
        input: (OsmNodeSpeed, BeamLengthDTO)): Option[BeamSpeed] = {
      val (osm, uber) = input
      Some(BeamSpeed(osm.eId, uber.speedAvg.get, osm.lenght))
    }
  }

}
