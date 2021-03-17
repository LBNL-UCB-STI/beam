import time
import helics as h
import numpy as np
import logging


def run_dummy_beam_federate(station_bus_pairs):
    logging.basicConfig(filename='dummy_beam_federate.log', level=logging.DEBUG)
    fedinfo = h.helicsCreateFederateInfo()
    deltat = 0.01  # smallest discernable interval to this federate

    # set the name
    h.helicsFederateInfoSetCoreName(fedinfo, "beamFederate")

    # set core type
    h.helicsFederateInfoSetCoreTypeFromString(fedinfo, "zmq")

    # set initialization string
    initstring = "--federates=1"
    h.helicsFederateInfoSetCoreInitString(fedinfo, initstring)

    # set message interval
    h.helicsFederateInfoSetTimeProperty(fedinfo, h.helics_property_time_delta, deltat)

    # create federate
    cfed = h.helicsCreateCombinationFederate("beamFederate", fedinfo)
    print("dummy_beam_federate created")
    logging.info("dummy_beam_federate created")

    # register publications
    # publish a json string that includes some charging stations that have updated loads
    # take every 20 stations and update them
    updated_stations = station_bus_pairs[0::20]
    print(f"dummy beam federate will publish updates for stations: {updated_stations}")
    logging.info(f"dummy_beam_federate will publish updates for stations: {updated_stations}")
    # create json
    pub_json = []
    for sb_pair in updated_stations:
        station_info = sb_pair[0].split('_')
        cs_json = {}
        cs_json['chargingZoneId'] = 'abc123' 
        cs_json['tazId'] = station_info[0]
        cs_json['parkingType'] = station_info[1]
        cs_json['numChargers'] = station_info[3]
        cs_json['chargingPointType'] = station_info[2]
        cs_json['pricingModel'] = 'abc123'
        cs_json['vehicleManager'] = 'abc123'
        cs_json['estimateLoad'] = 1000
        pub_json.append(cs_json)
    updated_charging_load = json.dumps(pub_json)

    pubs = h.helicsFederateRegisterPublication(cfed, chargingLoad, 0, None)
    print("publications registered")

    # enter execution mode
    h.helicsFederateEnterExecutingMode(cfed)
    print("dummy_beam_federate in execution mode")
    logging.info("dummy_beam_federate in execution mode")

    currenttime = 0.0
    timebin = 300
    # start execution loop
    for t in range(timebin, timebin*24*12+1, timebin):
        while currenttime <= t:
            currenttime = h.helicsFederateRequestTime(cfed, t)

        # publish charging load
        h.helicsPublicationPublishString(pubs, updated_charging_load)
        pub_string = f'publishing updated loads at time {t}'
        print(pub_string)
        logging.info(pub_string)

    # close the federate
    h.helicsFederateFinalize(cfed)
    #print("mapping federate finalized")
    logging.warning("dummy_beam_federate finalized")

    h.helicsFederateFree(cfed)
    h.helicsCloseLibrary()


###############################################################################
def load_station_bus_pairs():
    with open('station_bus_pairs.csv', 'r') as sbpfile:
        station_bus_list = sbpfile.readlines()
    for sbp in station_bus_list:
        pair = sbp.split(' ,')
        station_id = pair[0]
        bus_name = pair[1]
        station_bus_pairs.append((station_id, bus_name))
return station_bus_pairs

if __name__ == "__main__":
    station_bus_pairs = load_station_bus_pairs()
    print("stations_list_loaded")
    run_dummy_beam_federate(station_bus_pairs)
