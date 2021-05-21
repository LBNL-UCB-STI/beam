# this file creates an intermediate federate
# it maps the coordinates from TEMPO to the nearest charging
# station modeled in PyDSS
import time
import helics as h
import numpy as np
import logging
import json
import os


def run_beam_to_pydss_federate(station_bus_pairs):
    fedinfo = h.helicsCreateFederateInfo()

    # set the name
    h.helicsFederateInfoSetCoreName(fedinfo, "beam_to_pydss_federate")

    # set core type
    h.helicsFederateInfoSetCoreTypeFromString(fedinfo, "zmq")

    # set initialization string
    h.helicsFederateInfoSetCoreInitString(fedinfo, "--federates=1")

    # set message interval
    deltat = 1.0  # smallest discernable interval to this federate
    h.helicsFederateInfoSetTimeProperty(fedinfo, h.helics_property_time_delta, deltat)

    # create federate
    cfed = h.helicsCreateCombinationFederate("beam_to_pydss_federate", fedinfo)
    logging.info("beam_to_pydss_federate created")

    # register publications
    # publish an ordered list of charging station codes in same order as charging loads
    # pubs_station_loads = {}
    #
    # for s in range(len(station_bus_pairs)):
    #     station_id = station_bus_pairs[s][0]
    #     pubs_station_loads[station_id] = h.helicsFederateRegisterTypePublication(cfed, station_id, "string_vector", "")

    print("Register a publication of control signals")

    # Register a publication of control signals
    pubs_control = h.helicsFederateRegisterTypePublication(cfed, "pubs_power_limit_and_lpm_control", "string", "")
    logging.info("publications registered")

    # register subscriptions
    # subscribe to information from TEMPO such that you can map to PyDSS modeled charging stations
    subs_charger_loads = h.helicsFederateRegisterSubscription(cfed, "beamFederate/chargingLoad", "string")
    logging.info("subscriptions registered")

    # enter execution mode
    h.helicsFederateEnterExecutingMode(cfed)
    logging.info("beam_to_pydss_federate in execution mode")

    print("entered execution mode")

    def syncTime(requestedtime):
        grantedtime = -1
        while grantedtime < requestedtime:
            grantedtime = h.helicsFederateRequestTime(cfed, requestedtime)

    timebin = 300
    # start execution loop
    for t in range(0, 60*3600-timebin, timebin):
        syncTime(t)
        isupdated = 0
        # while isupdated != 1:
        #     isupdated = h.helicsInputIsUpdated(subs_charger_loads)
        print("charger loads received at currenttime: " + str(t) + " seconds")
        logging.info("charger loads received at currenttime: " + str(t) + " seconds")
        charger_load_json = json.loads(h.helicsInputGetString(subs_charger_loads))
        updated_station_ids = []
        #updated_station_q = []
        #updated_station_p = []
        updated_station_loads = []
        logging.info('Logging this as CSV')
        logging.info('stationId,estimatedLoad,currentTime')
        for station in charger_load_json:
            taz = station['tazId']
            parking_type = station['parkingType']
            charger_type = station['chargingPointType']
            n_plugs = station['numChargers']
            manager_id = station['managerId']
            station_id = 'cs_'+str(manager_id)+'_'+str(taz)+'_'+str(parking_type)+'_'+str(charger_type)
            station_load = station['estimatedLoad']
            updated_station_ids.append(station_id)
            updated_station_loads.append(station_load)
            logging.info(str(station_id)+','+str(station_load)+','+str(t))

        # uncomment this when pydss is included
        # for i in range(len(updated_station_ids)):
        #     # publish the station assignments
        #     updated_station = updated_station_ids[i]
        #     updated_load = updated_station_loads[i]
        #     h.helicsPublicationPublishVector(pubs_station_loads[updated_station], updated_load)#[station_P, station_Q])

        ############### This section should be un-commented and debugged when we have a controller signal to send to BEAM
        # power limits and potentially market signals will come from the controler and if they need reformatting before sending to BEAM, that can be done here
        #power_limit_upper = h.helicsInputGetString(subs_power_limit_upper)
        #power_limit_lower = h.helicsInputGetString(subs_power_limit_lower)
        #lmp_with_control_signal = h.helicsInputGetString(subs_lmp_control)
        ## format appropriately here
        #
        # Let's uncomment this and send dummy control signal to BEAM
        ## send updated signal to BEAM
        all_stations_with_control = []
        for station in charger_load_json:
            station_with_control = {
                'managerId': str(station['managerId']),
                'tazId': str(station['tazId']),
                'parkingType': str(station['parkingType']),
                'chargingPointType': str(station['chargingPointType']),
                'power_limit_upper': station['estimatedLoad'],
                'power_limit_lower': station['estimatedLoad'],
                'lmp_with_control_signal': 0
            }
            all_stations_with_control.append(station_with_control)

        h.helicsPublicationPublishString(pubs_control, json.dumps(all_stations_with_control, separators=(',', ':')))
        #h.helicsPublicationPublishString(pubs_power_limit_upper, power_limit_upper)
        #h.helicsPublicationPublishString(pubs_power_limit_lower, power_limit_lower)
        #h.helicsPublicationPublishString(pubs_lmp_control, lmp_with_control_signal)
        syncTime(t+1)

    # close the federate
    h.helicsFederateFinalize(cfed)
    logging.warning("beam_to_pydss_federate finalized")

    h.helicsFederateFree(cfed)
    h.helicsCloseLibrary()


###############################################################################
def load_station_bus_pairs():
    # with open('station_bus_pairs.csv', 'r') as sbpfile:
    #     station_bus_list = sbpfile.readlines()
    station_bus_pairs = []
    # for sbp in station_bus_list:
    #     pair = sbp.split(',')
    #     station_id = pair[0].strip()
    #     bus_name = pair[1].strip()
    #     station_bus_pairs.append((station_id, bus_name))
    return station_bus_pairs


if __name__ == "__main__":
    logging.basicConfig(filename='beam_to_pydss_federate.log', level=logging.DEBUG, filemode='w')
    station_bus_pairs = load_station_bus_pairs()
    logging.info("stations_list_loaded")
    run_beam_to_pydss_federate(station_bus_pairs)
