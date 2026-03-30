package beam.router.skim.urbansim

import beam.agentsim.infrastructure.geozone.GeoIndex
import com.conveyal.r5.api.util.TransitModes

import java.util

/**
  * Transit mode categories for mode-filtered R5 routing.
  * Allows generating separate skims for bus-only, rail-only, and all-transit options.
  */
sealed trait TransitModeCategory {
  def toR5TransitModes: util.EnumSet[TransitModes]
}

object TransitModeCategory {

  /**
    * Bus-only transit routing
    */
  case object BUS_ONLY extends TransitModeCategory {

    override def toR5TransitModes: util.EnumSet[TransitModes] =
      util.EnumSet.of(TransitModes.BUS)
  }

  /**
    * Rail-only transit routing (includes RAIL, SUBWAY, TRAM)
    */
  case object RAIL_ONLY extends TransitModeCategory {

    override def toR5TransitModes: util.EnumSet[TransitModes] =
      util.EnumSet.of(TransitModes.RAIL, TransitModes.SUBWAY, TransitModes.TRAM)
  }

  /**
    * All transit modes (current default behavior)
    */
  case object ALL_TRANSIT extends TransitModeCategory {

    override def toR5TransitModes: util.EnumSet[TransitModes] =
      util.EnumSet.allOf(classOf[TransitModes])
  }

  val allCategories: Seq[TransitModeCategory] = Seq(BUS_ONLY, RAIL_ONLY, ALL_TRANSIT)
}

/**
  * Trip direction for handling return trip vehicle location.
  * Used to model WLK_*_DRV path types where vehicle is parked at transit stop.
  */
sealed trait TripDirection

object TripDirection {

  /**
    * Normal outbound trip: vehicle starts at origin.
    * Produces DRV_*_WLK path types for drive-transit.
    */
  case object Outbound extends TripDirection

  /**
    * Return trip: vehicle is parked at transit stop near destination (home).
    * Produces WLK_*_DRV path types for walk-transit-drive.
    */
  case object Return extends TripDirection
}

/**
  * Work item for OD skim generation that includes transit mode category and trip direction.
  *
  * @param srcIndex       Origin geo index (H3 or TAZ)
  * @param dstIndex       Destination geo index (H3 or TAZ)
  * @param time           Departure time in seconds from midnight
  * @param transitCategory Transit mode category for mode-filtered routing
  * @param tripDirection  Trip direction for return trip parking handling
  */
case class ODWorkItem(
  srcIndex: GeoIndex,
  dstIndex: GeoIndex,
  time: Int,
  transitCategory: Option[TransitModeCategory] = None,
  tripDirection: TripDirection = TripDirection.Outbound
)
