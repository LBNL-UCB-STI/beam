package beam.utils.beamToVia.appsForVisualizations

import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamPathTraversal}
import beam.utils.beamToVia.beamEventsFilter.{MutableVehiclesFilter, VehicleTrip}
import beam.utils.beamToVia.viaEvent.ViaEvent
import beam.utils.beamToVia.{EventsProcessor, Writer}

import scala.collection.mutable

object visualization_35 extends App {
  val dirPath = "D:/Work/BEAM/visualizations/"
  val fileName = "v35.it3.events.csv"

  val beamEventsFilePath = dirPath + fileName
  val viaEventsFile = beamEventsFilePath + ".all.popSize1.via.xml"
  val viaIdFile = beamEventsFilePath + ".all.ids.txt"

  object Selector extends MutableVehiclesFilter.SelectNewVehicle {
    override def select(vehicleMode: String, vehicleType: String, vehicleId: String): Boolean = {
//      if (person1.vehicleIds.contains(vehicleId)) {
//        false
//      } else {
      vehicleMode match {
        case "CAR" | "BUS" => true
        case _             => false
      }
//      }
    }
  }

  def vehicleType(pte: BeamPathTraversal): String = {
    if (pte.vehicleId.contains("rideHail")) pte.mode + "_RH_P%03d".format(pte.numberOfPassengers)
    else pte.mode + "_P%03d".format(pte.numberOfPassengers)
  }

  def vehicleId(pte: BeamPathTraversal): String =
    vehicleType(pte) + "__" + pte.vehicleId

  val eventsWithAlternatives = {
    val (vehiclesEvents, _) = EventsProcessor.readWithFilter(beamEventsFilePath, MutableVehiclesFilter(Selector))
//    val alt0 = alternativePathToVehiclesTrips(person1.alternative0, "_alt0_")
//    val alt1 = alternativePathToVehiclesTrips(person1.alternative1, "_alt1_")
//    val alt2 = alternativePathToVehiclesTrips(person1.alternative2, "_alt2_")
//    val alt3 = alternativePathToVehiclesTrips(person1.alternative3, "_alt3_")

    vehiclesEvents //++ alt0 ++ alt1 ++ alt2 ++ alt3
  }

  val (events, typeToId) = EventsProcessor.transformPathTraversals(eventsWithAlternatives, vehicleId, vehicleType)

  Writer.writeViaEventsQueue[ViaEvent](events, _.toXml.toString, viaEventsFile)
  Writer.writeViaIdFile(typeToId, viaIdFile)

  def alternativePathToVehiclesTrips(path: Seq[BeamPathTraversal], idPrefix: String): Iterable[VehicleTrip] = {
    val trips = mutable.Map.empty[String, VehicleTrip]
    path.foreach(
      pte => {
        val vId = idPrefix + pte.vehicleId
        val pteWithCorrectVehicleId = new BeamPathTraversal(
          pte.time,
          vId,
          pte.driverId,
          pte.vehicleType,
          pte.mode,
          pte.numberOfPassengers,
          pte.arrivalTime,
          pte.linkIds,
          pte.linkTravelTime
        )

        trips.get(vId) match {
          case Some(trip) => trip.trip += pteWithCorrectVehicleId
          case None       => trips(vId) = VehicleTrip(vId, pteWithCorrectVehicleId)
        }
      }
    )

    trips.values
  }

  object person1 {

    /*
    person1 :
      from 08:09:46
      CAR_RH_*__rideHailVehicle-026200-2016000744660-0-4385427
     */

    val alternative0Cost =
      "cost: 7.2624803574, time: 2.5660917853, tansfer: 0, utility: -10.8285721427, expUtility: 1.9824893891225256E-5"

    val alternative0: Seq[BeamPathTraversal] = Seq(
      BeamPathTraversal(
        29487,
        "rideHailVehicle-026200-2016000744660-0-4385427",
        "rideHailAgent-026200-2016000744660-0-4385427",
        "Car-rh-only",
        "CAR",
        1,
        Seq[Int](3127, 88933, 3125, 88929, 55759, 88859, 55757, 88855, 55755, 88833, 55753, 45453, 88809, 45451, 45449,
          45447, 45445, 45443, 88769, 45441, 88867, 45439, 45437, 45435, 45433, 64869, 45429, 34046, 86756, 50712,
          50714, 48880, 68702, 23320, 23322, 23324, 23326, 23328, 23330, 23332, 23334, 23336, 55702, 55704, 55706,
          55834, 55836, 36312, 91316, 23316, 23318, 70272, 70274, 15676, 15678, 90810, 15680, 9088, 9090, 65442, 28837,
          28834, 15376, 15378, 15380, 15382, 83292, 23382, 46088, 83304, 65472, 65474, 65470, 55898, 55900, 39258,
          83300, 65206, 7056, 65208, 54122, 54124, 65244, 65216, 89550, 65218, 65220, 65222, 65224, 65226, 65228, 65230,
          65232, 65234, 65236, 65238, 65240, 65242, 70848, 70846, 34470, 34472, 89546, 39268, 39270, 34476, 34478,
          34480, 25004, 64407, 29503, 29500, 17851, 17849, 17847, 17845, 17843, 17841, 17839, 17837, 25013, 25011,
          25009, 25007),
        Seq[Double](3, 6, 2, 1, 3, 5, 1, 1, 1, 6, 1, 1, 1, 1, 1, 2, 2, 2, 4, 1, 1, 5, 1, 1, 2, 2, 1, 1, 1, 3, 1, 2, 2,
          5, 10, 5, 6, 4, 5, 3, 3, 3, 4, 3, 3, 3, 3, 1, 8, 1, 4, 1, 1, 1, 2, 1, 2, 4, 2, 2, 3, 3, 4, 2, 2, 2, 1, 3, 3,
          1, 3, 3, 3, 4, 3, 1, 8, 2, 7, 2, 24, 3, 2, 1, 11, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 1, 1, 2, 1, 1, 1, 4, 1, 1,
          2, 1, 1, 3, 2, 6, 3, 13, 8, 8, 1, 3, 2, 7, 9, 3, 7, 3, 4)
      ),
      BeamPathTraversal(
        29859,
        "rideHailVehicle-026200-2016000744660-0-4385427",
        "rideHailAgent-026200-2016000744660-0-4385427",
        "Car-rh-only",
        "CAR",
        1,
        Seq[Int](25006, 25008, 25010, 25012, 17836, 17838, 17840, 17842, 17844, 17846, 17848, 17850, 29504, 29502,
          64406, 69216, 25002, 70788, 34482, 90500, 34484, 64386, 64388, 64380, 34474, 90490, 36462, 51128, 39472,
          29332, 29334, 52529, 84333, 51363, 43345, 43343, 30525, 30523, 50385, 36323, 36480, 36482, 18401, 18399,
          18405, 18403, 18406, 69036, 9302, 64494, 48474, 93884, 50130, 93824, 50132, 50134, 50136, 64800, 51150, 51152,
          69058, 69060, 64808, 64810, 69094, 69096, 75102, 84030, 69104, 74740, 74738, 47318, 47320, 47322, 47324,
          70086, 50120, 74746, 17972, 17974, 23372, 23374, 70092, 65502, 53074, 36849, 74701, 79807, 79805, 70075,
          78309, 79809, 79815, 79813, 48023, 70073, 48025, 70083, 78307, 78305, 19771, 23398, 23400, 70088, 70090,
          70080, 54682, 23402, 25108, 68680, 70076, 36776, 70079, 36775, 2148, 93642, 74612, 54458, 22508, 22510, 22512,
          75290, 4728, 4730, 4732, 57066, 85210, 57068, 57070, 57074, 52656),
        Seq[Double](7, 3, 7, 3, 9, 7, 2, 3, 1, 8, 8, 13, 3, 6, 2, 2, 1, 1, 2, 1, 1, 1, 3, 2, 1, 5, 4, 1, 2, 1, 3, 2, 1,
          8, 4, 12, 8, 8, 9, 8, 4, 4, 8, 8, 1, 6, 2, 1, 5, 2, 1, 6, 1, 2, 1, 1, 3, 3, 3, 1, 1, 3, 1, 1, 1, 5, 2, 1, 2,
          2, 2, 1, 1, 1, 2, 2, 2, 1, 4, 2, 2, 9, 1, 2, 2, 1, 2, 2, 1, 1, 7, 1, 2, 2, 1, 3, 1, 2, 2, 2, 2, 2, 1, 4, 1, 1,
          2, 4, 2, 2, 1, 3, 1, 3, 5, 1, 2, 4, 3, 3, 2, 1, 1, 1, 1, 4, 3, 1, 3, 26, 24)
      )
    )

    val alternative1Cost =
      "cost: 9.6283657793, time: 1.1867397845, tansfer: 0, utility: -10.8151055637, expUtility: 2.009367309730039E-5"

    val alternative1: Seq[BeamPathTraversal] = Seq(
      BeamPathTraversal(
        29183,
        "rideHailVehicle-600401-2014001249408-0-5794423",
        "rideHailAgent-600401-2014001249408-0-5794423",
        "Car-rh-only",
        "CAR",
        1,
        Seq[Int](88933, 3125, 88929, 55759, 88859, 55757, 88855, 55755, 88833, 55753, 45453, 88809, 45451, 45449, 45447,
          45445, 45443, 88769, 45441, 88867, 45439, 45437, 45435, 45433, 64869, 45429, 34046, 86756, 50712, 50714,
          48880, 68702, 23320, 23322, 23324, 23326, 23328, 23330, 23332, 23334, 23336, 55702, 55704, 55706, 55834,
          55836, 36312, 91316, 23316, 23318, 70272, 70274, 12614, 65440, 55844, 70270, 70268, 49504, 49506, 63398,
          70852, 70854, 448, 49546, 49548, 29722, 6668, 22516, 35466, 20618, 28846, 28848, 54682, 23402, 25108, 68680,
          70076, 36776, 70079, 36775, 2148, 93642, 74612, 54458, 22508, 22510, 22512, 75290, 4728, 4730, 4732, 57066,
          85210, 57068, 57070, 57074),
        Seq[Double](7, 2, 1, 3, 6, 1, 1, 1, 7, 1, 1, 1, 1, 1, 2, 2, 2, 4, 1, 1, 6, 1, 1, 2, 2, 1, 1, 1, 3, 1, 2, 2, 6,
          11, 6, 7, 4, 6, 3, 3, 3, 4, 3, 3, 3, 3, 1, 9, 1, 4, 1, 1, 11, 2, 13, 2, 1, 6, 3, 2, 6, 4, 9, 1, 6, 8, 18, 15,
          22, 40, 8, 2, 2, 4, 2, 2, 1, 3, 1, 3, 6, 1, 2, 4, 3, 3, 2, 1, 1, 1, 1, 4, 3, 1, 3, 29)
      )
    )

    val alternative2Cost =
      "cost: 0, time: 18.8791038489, tansfer: 0, utility: -18.8791038489, expUtility: 6.322798988706303E-9"

    val alternative2: Seq[BeamPathTraversal] = Seq(
      BeamPathTraversal(
        29160,
        "body-026303-2015000142533-0-3364370",
        "026303-2015000142533-0-3364370",
        "BODY-TYPE-DEFAULT",
        "WALK",
        0,
        Seq[Int](88933, 3125, 27548, 34050, 50490, 50492, 50494, 50496, 50498, 50500, 50502, 50504, 50506, 50508, 55750,
          10863, 10861, 10859, 10857, 27578, 43138, 43140, 52074, 88515, 52087, 88495, 88523, 52085, 52083, 88535,
          52081, 52079, 52077, 88505, 52073, 8909, 8907, 8905, 34160, 34162, 34164, 91368, 34166, 91364, 34168, 34170,
          34172, 91336, 91340, 34174, 34176, 91408, 34178, 91404, 34180, 34182, 34184, 34186, 91360, 34188, 91356,
          34190, 34192, 34194, 91328, 34196, 91372, 34198, 91384, 34200, 91380, 34202, 34204, 34206, 34208, 91420,
          20664, 25238, 20662, 36504, 91332, 36506, 36508, 36510, 36512, 36514, 91324, 36516, 93448, 36518, 91426,
          36520, 36522, 36524, 91416, 91412, 64350, 64352, 64354, 89736, 36467, 36465, 1119, 1117, 1115, 87617, 87621,
          87613, 48169, 4921, 4919, 4917, 4915, 4913, 4911, 15713, 54695, 54693, 54511, 54509, 54507, 54504, 57066,
          85210, 57068, 57070, 57074),
        Seq[Double](51, 11, 67, 96, 151, 57, 10, 45, 31, 40, 35, 36, 37, 38, 13, 24, 38, 37, 78, 21, 50, 18, 11, 23, 88,
          17, 60, 6, 155, 149, 7, 156, 155, 7, 149, 155, 155, 158, 77, 33, 66, 8, 8, 65, 73, 71, 9, 16, 52, 83, 65, 8,
          7, 65, 47, 24, 24, 26, 23, 23, 27, 22, 72, 45, 22, 23, 52, 36, 6, 7, 23, 71, 56, 15, 67, 5, 10, 59, 10, 24, 4,
          72, 57, 17, 40, 7, 28, 17, 6, 45, 8, 49, 9, 110, 15, 9, 7, 66, 71, 15, 33, 34, 66, 116, 148, 10, 10, 125, 11,
          66, 66, 66, 66, 67, 65, 46, 24, 60, 4, 4, 5, 95, 38, 23, 2, 21, 245)
      )
    )

    val alternative3Cost =
      "cost: 2.75, time: 7.9343806533, tansfer: 1, utility: -12.0843806533, expUtility: 5.647030911698824E-6"

    val alternative3: Seq[BeamPathTraversal] = Seq(
      BeamPathTraversal(
        29347,
        "body-026303-2015000142533-0-3364370",
        "026303-2015000142533-0-3364370",
        "BODY-TYPE-DEFAULT",
        "WALK",
        0,
        Seq[Int](88933, 3125, 88929, 55759, 88859, 55757, 88855, 88857),
        Seq[Double](51, 11, 8, 53, 98, 8, 8, 2)
      ),
      // BUS agency:SFMTA, route:SF:12298, vehicle:SF:7597760, 5->8
      // SF:7597760 from 88857 to 88908
      BeamPathTraversal(
        29586, //original was 29535
        "SF:7597760",
        "TransitDriverAgent-SF:7597760",
        "DEFAULT",
        "BUS",
        1,
        Seq[Int](
          88855, 55755, 88833, 55753, 45453, 88809, 45451, 45449, 45447, 45445, 45443, 88769, 45441, 88867, 45439,
          45437, 45435, 45433, 64869, 45429, 88825, 50857, 55065, 55063, 55061, 55059, 88849, 55057, 88845, 55055,
          55053, 55051, 55049, 55047, 55045, 88881, 55043, 55041, 88901, 50855, 50853, 88885, 49845, 52031, 88875,
          88789, 52029, 52037, 52035, 52033, 88813, 88803, 22631, 22629, 22627, 22625, 22623, 22621
        ),
        Seq[Double](7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8,
          8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 12)
      ),
      BeamPathTraversal(
        30060,
        "body-026303-2015000142533-0-3364370",
        "026303-2015000142533-0-3364370",
        "BODY-TYPE-DEFAULT",
        "WALK",
        0,
        Seq[Int](88908, 34214, 91399),
        Seq[Double](2, 8, 30240 - 30060 - 2 - 8)
      ),
      // BUS agency:SFMTA, route:SF:12323, vehicle:SF:7686588, 29->60
      // SF:7686588 from 91399 to 93204
      BeamPathTraversal(
        30240,
        "SF:7686588",
        "TransitDriverAgent-SF:7686588",
        "DEFAULT",
        "BUS",
        1,
        Seq[Int](34214, 91396, 91392, 34148, 10030, 91376, 91377, 10031, 34150, 93716, 34152, 34154, 34156, 91352,
          34158, 91348, 34160, 34162, 34164, 91368, 34166, 91364, 34168, 34170, 34172, 91336, 91340, 34174, 34176,
          91408, 34178, 91404, 34180, 34182, 34184, 34186, 91360, 34188, 91356, 34190, 34192, 34194, 91328, 34196,
          91372, 34198, 91384, 34200, 91380, 34202, 34204, 34206, 34208, 91420, 20664, 25238, 20662, 36504, 91332,
          36506, 36508, 36510, 36512, 36514, 91324, 36516, 93448, 36518, 91426, 36520, 36522, 36524, 91416, 91412,
          64350, 64352, 64354, 89736, 34238, 34239, 89733, 19111, 89729, 19109, 89725, 89717, 47279, 47250, 47252,
          38644, 54703, 54701, 54699, 54696, 64360, 89664, 89713, 19115, 89709, 89701, 89705, 19113, 87793, 8941, 87789,
          88547, 8939, 15750, 15736, 15737, 15752, 52350, 52352, 93026, 93030, 7725, 6860, 6861, 87199, 7723, 87177,
          87181, 7721, 87383, 1077, 87379, 1075, 88573, 93297, 3041, 88585, 88581, 3039, 3037, 3035, 88577, 3033, 39976,
          85798, 39978, 85826, 74810, 74812),
        Seq[Double](8, 8, 8, 8, 8, 4, 9, 9, 9, 9, 9, 9, 5, 9, 9, 9, 9, 9, 9, 9, 2, 13, 13, 13, 14, 12, 12, 12, 12, 12,
          12, 12, 12, 12, 8, 8, 8, 8, 8, 8, 7, 8, 8, 7, 10, 10, 10, 10, 10, 10, 10, 13, 13, 11, 11, 11, 11, 8, 8, 8, 8,
          8, 8, 8, 8, 6, 10, 10, 10, 10, 10, 10, 10, 8, 8, 8, 8, 13, 12, 12, 12, 10, 10, 10, 10, 10, 9, 9, 9, 9, 8, 9,
          9, 12, 12, 12, 12, 12, 8, 8, 12, 8, 8, 8, 8, 9, 7, 7, 7, 12, 10, 10, 10, 10, 14, 6, 6, 6, 5, 7, 7, 7, 7, 7, 7,
          5, 5, 5, 4, 12, 12, 12, 10, 20, 20, 20, 17, 17, 18, 10, 10, 10, 8)
      ),
      BeamPathTraversal(
        31616,
        "body-026303-2015000142533-0-3364370",
        "026303-2015000142533-0-3364370",
        "BODY-TYPE-DEFAULT",
        "WALK",
        0,
        Seq[Int](93204, 93202, 88605, 88601, 52765, 52763, 88597, 88593, 52761, 52759),
        Seq[Double](1, 10, 5, 16, 11, 106, 10, 8, 115, 4)
      )
    )

    lazy val vehicleIds: mutable.HashSet[String] = {
      def getVehicleId(pte: BeamPathTraversal) = pte.vehicleId
      val alts = {
        alternative0.map(getVehicleId) ++
        alternative1.map(getVehicleId) ++
        alternative2.map(getVehicleId) ++
        alternative3.map(getVehicleId)
      }

      mutable.HashSet[String](alts: _*)
    }
  }
}
