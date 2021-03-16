# this file creates an intermediate federate
# it maps the coordinates from TEMPO to the nearest charging
# station modeled in PyDSS
import time
import helics as h
import numpy as np
import logging


def run_beam_to_pydss_federate(station_bus_pairs):
    logging.basicConfig(filename='beam_to_pydss_federate.log', level=logging.DEBUG)
    fedinfo = h.helicsCreateFederateInfo()
    deltat = 0.01  # smallest discernable interval to this federate

    # set the name
    h.helicsFederateInfoSetCoreName(fedinfo, "beam_to_pydss_federate")

    # set core type
    h.helicsFederateInfoSetCoreTypeFromString(fedinfo, "zmq")

    # set initialization string
    initstring = "--federates=1"
    h.helicsFederateInfoSetCoreInitString(fedinfo, initstring)

    # set message interval
    h.helicsFederateInfoSetTimeProperty(fedinfo, h.helics_property_time_delta, deltat)

    # create federate
    cfed = h.helicsCreateCombinationFederate("beam_to_pydss_federate", fedinfo)
    print("beam_to_pydss_federate created")
    logging.info("beam_to_pydss_federate created")

    # register publications
    # publish an ordered list of charging station codes in same order as charging loads
    pubs_station_loads = []    

    for pairing in station_bus_pairs:
        station_id = station_bus_pairs[0]
        pubs_station_loads[station_id] = h.helicsFederateRegisterPublication(cfed, station_id, 0, None)

    print("publications registered")

    # register subscriptions
    # subscribe to information from TEMPO such that you can map to PyDSS modeled charging stations
    subs_charger_loads = h.helicsFederateRegisterSubscription(cfed, "beamFederate/chargingLoad", "string")
    print("subscriptions registered")

    # enter execution mode
    h.helicsFederateEnterExecutingMode(cfed)
    print("beam_to_pydss_federate in execution mode")
    logging.info("beam_to_pydss_federate in execution mode")

    currenttime = 0.0
    timebin = 300
    # start execution loop
    for t in range(timebin, timebin*24*12+1, timebin):
        while currenttime <= t:
            currenttime = h.helicsFederateRequestTime(cfed, t)

        # if there are any plug in or out events
        if h.helicsInputIsUpdated(subs_charger_loads) == 1:
            charger_load_json = json.loads(h.helicsInputGetString(subs_charger_loads))
            
            logging.info(f'charger_load_json read as: {charger_load_json}')
            updated_station_ids = []
            #updated_station_q = []
            #updated_station_p = []
            updated_station_loads = []
            for station in charger_load_json:
                taz = station['tazId']
                parking_type = station['parkingType']
                charger_type = station['charginerPointType']
                n_plugs = station['numChargers']
                station_id = f'cs_{taz}_{parking_type}_{charger_type}_{n_plugs}'
                station_load = list(station['estimatedLoad'])
                updated_station_ids.append(station_id)
                updated_station_load.append(station_load)

        for i in range(len(updated_station_ids)):
            # publish the station assignments
            updated_station = updated_station_ids[i]
            updated_load = updated_station_loads[i]
            h.helicsPublicationPublishVector(pubs_station_loads[updated_station], updated_load)#[station_P, station_Q])

        ############### This section should be un-commented and debugged when we have a controller signal to send to BEAM
        # power limits and potentially market signals will come from the controler and if they need reformatting before sending to BEAM, that can be done here
        #power_limit_upper = h.helicsInputGetString(subs_power_limit_upper)
        #power_limit_lower = h.helicsInputGetString(subs_power_limit_lower)
        #lmp_with_control_signal = h.helicsInputGetString(subs_lmp_control)
        ## format appropriately here
        #
        ## send updated signal to BEAM
        #h.helicsPublicationPublishString(pubs_power_limit_upper, power_limit_upper)
        #h.helicsPublicationPublishString(pubs_power_limit_lower, power_limit_lower)
        #h.helicsPublicationPublishString(pubs_lmp_control, lmp_with_control_signal)

    # close the federate
    h.helicsFederateFinalize(cfed)
    #print("mapping federate finalized")
    logging.warning("beam_to_pydss_federate finalized")

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
    run_beam_to_pydss_federate(station_bus_pairs)
