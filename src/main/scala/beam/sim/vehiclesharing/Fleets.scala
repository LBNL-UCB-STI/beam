package beam.sim.vehiclesharing

object Fleets {

  def lookup(id: String): FleetType = {
    id match {
      case "inexhaustible-reserving" =>
        InexhaustibleReservingFleet()
      case "fixed-non-reserving" =>
        FixedNonReservingFleet()
      case _ =>
        throw new RuntimeException("Unknown fleet type")
    }
  }

}
