# this file creates an intermediate federate
# it maps the coordinates from TEMPO to the nearest charging
# station modeled in PyDSS
import time
import helics as h
import numpy as np
import logging
import json
import os


def run_beam_to_pydss_federate():
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
        print("charger loads received at currenttime: " + str(t) + " seconds")
        logging.info("charger loads received at currenttime: " + str(t) + " seconds")
        charger_load_json = json.loads(h.helicsInputGetString(subs_charger_loads))
        logging.info('Logging this as CSV')
        logging.info('stationId,estimatedLoad,currentTime')
        for station in charger_load_json:
            reservedFor = station['reservedFor']
            parkingZoneId = station['parkingZoneId']
            station_load = station['estimatedLoad']
            logging.info(str(parkingZoneId)+','+str(station_load)+','+str(reservedFor)+','+str(t))

        ############### This section should be un-commented and debugged when we have a controller signal to send to BEAM
        ## format appropriately here
        #
        # Let's uncomment this and send dummy control signal to BEAM
        ## send updated signal to BEAM
        all_stations_with_control = []
        for station in charger_load_json:
            station_with_control = {
                'parkingZoneId': str(station['parkingZoneId']),
                'reservedFor': str(station['reservedFor']),
                'power_limit_upper': station['estimatedLoad'],
                'power_limit_lower': station['estimatedLoad'],
                'lmp_with_control_signal': 0
            }
            all_stations_with_control.append(station_with_control)

        h.helicsPublicationPublishString(pubs_control, json.dumps(all_stations_with_control, separators=(',', ':')))
        syncTime(t+1)

    # close the federate
    h.helicsFederateFinalize(cfed)
    logging.warning("beam_to_pydss_federate finalized")

    h.helicsFederateFree(cfed)
    h.helicsCloseLibrary()


###############################################################################

if __name__ == "__main__":
    logging.basicConfig(filename='beam_to_pydss_federate.log', level=logging.DEBUG, filemode='w')
    logging.info("stations_list_loaded")
    run_beam_to_pydss_federate()
