package beam.analysis.physsim

import beam.physsim.{LinkPickUpsDropOffs, PickUpDropOffCollector, TimeToValueCollection}
import beam.utils.{BeamVehicleUtils, EventReader}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class PickUpDropOffCollectorTest extends AnyFunSuite with Matchers {

  test("TimeToLinkCollection should work as expected") {
    val ttcc = new TimeToValueCollection()
    ttcc.getClosestTimeIndex(42) shouldBe None
    ttcc.getClosestValue(42, 100000) shouldBe None

    ttcc.addTimeToValue(100, 3)
    ttcc.getClosestTimeIndex(42) shouldBe Some(0)
    ttcc.getClosestValue(42, 100 - 42) shouldBe Some(3)
    ttcc.getClosestValue(42, 100 - 43) shouldBe None

    ttcc.addTimeToValue(200, 2)
    ttcc.getClosestValue(149, 50) shouldBe Some(3)
    ttcc.getClosestValue(152, 50) shouldBe Some(2)

    ttcc.addTimeToValue(300, 10)
    ttcc.getClosestValue(149, 50) shouldBe Some(3)
    ttcc.getClosestValue(151, 50) shouldBe Some(2)
    ttcc.getClosestValue(151, 48) shouldBe None
    ttcc.getClosestValue(249, 50) shouldBe Some(2)
    ttcc.getClosestValue(251, 50) shouldBe Some(10)
    ttcc.getClosestValue(251, 48) shouldBe None
  }

  test("TimeToValueCollection should add pairs and store them sorted independently if they were sorted before or not") {
    val collector = new TimeToValueCollection()
    collector.addTimeToValue(10.0, 1)
    collector.addTimeToValue(5.0, 2)
    collector.addTimeToValue(20.0, 3)
    collector.addTimeToValue(15.0, 4)

    collector.times.toSet shouldBe Set(5.0, 10.0, 15.0, 20.0)
    collector.values.toSet shouldBe Set(2, 1, 4, 3)
  }

  test("BEAMVILLE. Should read expected link pick-ups and drop-offs") {
    val eventsFilePath = "test/test-resources/PickUpsDropOffsCollectorTestData/beamville.events.xml"
    val vehicleTypeFilePath = "test/test-resources/PickUpsDropOffsCollectorTestData/beamville.vehicleTypes.csv"

    val events = EventReader.fromXmlFile(eventsFilePath)

    val vehicleTypes = BeamVehicleUtils.readBeamVehicleTypeFile(vehicleTypeFilePath)
    val collector = new PickUpDropOffCollector(vehicleTypes)

    var linkToPickUpDropOff: mutable.HashMap[Int, LinkPickUpsDropOffs] = null

    collector.notifyIterationStarts(null)
    events.foreach(event => collector.handleEvent(event))
    linkToPickUpDropOff = collector.getLinkToPickUpsDropOffs

    linkToPickUpDropOff shouldNot be(null)

    val (pickUps, dropOffs) = linkToPickUpDropOff.foldLeft((0, 0)) { case ((pickUps, dropOffs), (_, pickUpDropOff)) =>
      val linkPickUps: Int = pickUpDropOff.timeToPickUps.values.sum
      val linkDropOffs: Int = pickUpDropOff.timeToDropOffs.values.sum

      (pickUps + linkPickUps, dropOffs + linkDropOffs)
    }

    pickUps shouldBe (19 + 20)
    dropOffs shouldBe (19 + 20)

    /*
      The PYTHON script to produce test data from events file.

      import pandas as pd
      pd.set_option('display.max_rows', 500)
      pd.set_option('display.max_columns', 500)
      pd.set_option('display.width', 1000)

      def prepare_test_data(path):
          events = pd.read_csv(path)
          print('events types:', events['type'].unique())
          events = events[events['time'] > 0]

          ptes = events[(events['type'] == 'PathTraversal') & (events['mode'] == 'car')]
          print('vehicle types', ptes['vehicleType'].unique())

          cav_ptes = ptes[(ptes['vehicleType'] == 'CAV') & (ptes['mode'] == 'car')]
          print('\ncavs pte shape:', cavs.shape)
          cav_ids = set(cav_ptes['vehicle'].unique())
          print('cav ids:', cav_ids)

          rh_ptes = ptes[ptes['vehicle'].str.startswith('rideHailVehicle')]
          print('\nRH pte shape:', rh_ptes.shape)
          rh_ids = set(rh_ptes['vehicle'].unique())
          print('RH ids:', rh_ids)

          plv = events[events['type'] == 'PersonLeavesVehicle']
          pev = events[events['type'] == 'PersonEntersVehicle']
          print('\nall person enters vehicle events:', pev.shape, 'person leaves vehicle events:', plv.shape)

          rh_plv = plv[plv['vehicle'].isin(rh_ids)]
          rh_pev = pev[pev['vehicle'].isin(rh_ids)]

          cav_plv = plv[plv['vehicle'].isin(cav_ids)]
          cav_pev = pev[pev['vehicle'].isin(cav_ids)]

          pick_ups = len(rh_pev) + len(cav_pev)
          drop_offs = len(rh_plv) + len(cav_plv)

          print('\npickUps shouldBe ({} + {})'.format(len(rh_pev), len(cav_pev)))
          print('dropOffs shouldBe ({} + {})'.format(len(rh_plv), len(cav_plv)))

          def get_first_last_link(links):
              if links:
                  if ',' in str(links):
                      link_array = links.split(',')
                      return (link_array[0], link_array[-1])
              return (None, None)

          vehicle_to_time_to_pick_up_drop_off = {}

          def fill_vehicle_to_time_to_pick_up_drop_off(df):
              for index, row in df.iterrows():
                  vehicle = row['vehicle']
                  time_arrival = row['arrivalTime']
                  time_departure = row['departureTime']
                  links = row['links']
                  (pick_up, drop_off) = get_first_last_link(links)
                  if pick_up and drop_off:
                      time_to_pick_up_drop_off = vehicle_to_time_to_pick_up_drop_off.get(vehicle, {})
                      time_to_pick_up_drop_off[time_arrival] = (None, drop_off)
                      time_to_pick_up_drop_off[time_departure] = (pick_up, None)
                      vehicle_to_time_to_pick_up_drop_off[vehicle] = time_to_pick_up_drop_off

          rh_cav_events = events[(events['vehicle'].isin(rh_ids)) | (events['vehicle'].isin(cav_ids))].copy()
          fill_vehicle_to_time_to_pick_up_drop_off(rh_cav_events)

          link_to_pickup_dropoff = {}
          def add_link_to_pickup_dropoff(link_id, pick_up = None, drop_off = None):
              (pick_ups, drop_offs) = link_to_pickup_dropoff.get(link_id, ([],[]))

              if pick_up:
                  pick_ups.append(pick_up)
              if drop_off:
                  drop_offs.append(drop_off)

              link_to_pickup_dropoff[link_id] = (pick_ups, drop_offs)


          def analyze_person_enters_left_vehicle(df):
              for index, row in df[(df['type'] == 'PersonEntersVehicle') | (df['type'] == 'PersonLeavesVehicle')].iterrows():
                  vehicle = row['vehicle']
                  time = row['time']
                  time_to_pick_up_drop_off = vehicle_to_time_to_pick_up_drop_off.get(vehicle)
                  (pick_up, drop_off) = time_to_pick_up_drop_off.get(time, (None,None))
                  if row['type'] == 'PersonEntersVehicle':
                      if pick_up is None:
                          print('missing pick up for time {} for vehicle {}'.format(time, vehicle))
                      else:
                          add_link_to_pickup_dropoff(pick_up, pick_up = time)
                  elif row['type'] == 'PersonLeavesVehicle':
                      if drop_off is None:
                          print('missing drop off for time {} for vehicle {}'.format(time, vehicle))
                      else:
                          add_link_to_pickup_dropoff(drop_off, drop_off = time)

          analyze_person_enters_left_vehicle(rh_cav_events)

          print("\n\nMap(")
          for link in link_to_pickup_dropoff:
              pickups = link_ids = ",".join([str(x) for x in link_to_pickup_dropoff[link][0]])
              dropoffs = link_ids = ",".join([str(x) for x in link_to_pickup_dropoff[link][1]])
              print("({},(Set({}), Set({}))),".format(link, pickups, dropoffs))
          print(")")


      prepare_test_data('beamville.events.csv')
     * */

    val linkToPickUpDropOffFromPython = Map(
      (132, (Set(22032.0, 22820.0, 32672.0, 41432.0), Set.empty)),
      (244, (Set(26201.0, 44201.0), Set(22249.0, 23037.0, 32889.0, 41649.0))),
      (
        228,
        (
          Set(23671.0, 24300.0, 24471.0, 24767.0, 37071.0),
          Set.empty
        )
      ),
      (176, (Set.empty, Set(23746.0))),
      (108, (Set.empty, Set(24516.0))),
      (34, (Set(72417.0), Set(24688.0, 24984.0))),
      (57, (Set.empty, Set(26490.0, 44490.0, 71350.0))),
      (177, (Set(36688.0), Set.empty)),
      (229, (Set.empty, Set(36834.0, 39163.0, 72217.0, 72709.0))),
      (20, (Set(38801.0), Set(37362.0))),
      (195, (Set(46160.0), Set(54910.0))),
      (11, (Set.empty, Set(46522.0))),
      (133, (Set(49815.0), Set())),
      (144, (Set.empty, Set(50032.0))),
      (10, (Set(54548.0), Set.empty)),
      (56, (Set(67105.0), Set.empty)),
      (208, (Set(71201.0), Set(67250.0))),
      (109, (Set(71998.0), Set.empty))
    )

    linkToPickUpDropOffFromPython.foreach { case (link, (pickUps, dropOffs)) =>
      val pickUpDropOff = linkToPickUpDropOff(link)
      pickUpDropOff.linkId shouldBe link
      withClue(s"Comparison of pickUps for link $link: (from python $pickUps)") {
        pickUpDropOff.timeToPickUps.times.toSet shouldBe pickUps
      }
      withClue(s"Comparison of dropOffs for link $link: (from python $dropOffs)") {
        pickUpDropOff.timeToDropOffs.times.toSet shouldBe dropOffs
      }
    }

    linkToPickUpDropOffFromPython.keySet -- linkToPickUpDropOff.keySet shouldBe Set.empty[Int]
    linkToPickUpDropOff.keySet -- linkToPickUpDropOffFromPython.keySet shouldBe Set.empty[Int]
  }
}
