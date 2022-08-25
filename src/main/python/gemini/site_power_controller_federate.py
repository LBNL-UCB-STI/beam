# this file creates an intermediate federate
# it maps the coordinates from TEMPO to the nearest charging
# station modeled in PyDSS
import time
import helics as h
import pandas as pd
import logging
import json
import itertools
import os


# import juliusLib


# if len(sys.argv) < 2:
#     logging.error("infrastructure file is missing")

def create_federate(fedinfo, tazId):
    fed_name = "SPMC_FEDERATE_" + str(tazId)

    # create federate
    cfed = h.helicsCreateCombinationFederate(fed_name, fedinfo)
    logging.info(fed_name + " created")

    logging.info("Register a publication of control signals")

    # Register a publication of control signals
    h.helicsFederateRegisterTypePublication(cfed, "CHARGING_PROFILE", "string", "")
    logging.info("publications registered")

    # register subscriptions
    # subscribe to information from TEMPO such that you can map to PyDSS modeled charging stations
    h.helicsFederateRegisterSubscription(cfed, "BEAM_SPM_FEDERATE_" + str(tazId) + "/CHARGING_SESSION_EVENTS", "string")
    logging.info("subscriptions registered")
    return cfed


def run_spmc_federate(cfed):
    # enter execution mode
    h.helicsFederateEnterExecutingMode(cfed)
    fed_name = h.helicsFederateGetName(cfed)
    logging.debug(fed_name + " in execution mode")
    subs_charging_events = h.helicsFederateGetInputByIndex(cfed, 0)
    pubs_control = h.helicsFederateGetPublicationByIndex(cfed, 0)

    # TODO SPMC INITIALIZE HERE
    # TODO JULIUS
    # TODO MYUNGSOO
    # juliusObject = juliusLib.initialize(data.loc[data["parkingZoneId"] == parkingZoneId])

    def key_func(k):
        return k['siteId']

    def syncTime(requestedtime):
        grantedtime = -1
        while grantedtime < requestedtime:
            grantedtime = h.helicsFederateRequestTime(cfed, requestedtime)

    timebin = 300
    # start execution loop
    for t in range(0, 60 * 3600 - timebin, timebin):
        syncTime(t)
        logging.info("charger loads received at currenttime: " + str(t) + " seconds")
        logging.info("charger loads received at currenttime: " + str(t) + " seconds")
        charging_events_json = json.loads(h.helicsInputGetString(subs_charging_events))
        logging.info('Logging this as CSV')
        logging.info('stationId,estimatedLoad,currentTime')

        # for vehicle in charging_events_json:
        #     vehicleId = vehicle['vehicleId']
        #     tazId = vehicle['tazId']
        #     siteId = vehicle['siteId']
        #     vehicleType = vehicle['vehicleType']
        #     primaryFuelLevelInJoules = vehicle['primaryFuelLevelInJoules']
        #     arrivalTime = vehicle['arrivalTime']
        #     desiredDepartureTime = vehicle['departureTime']
        #     desiredFuelLevelInJoules = vehicle['desiredFuelLevelInJoules']
        #     powerInKW = vehicle['powerInKW']
        #     logging.info(str(vehicleId)+','+str(vehicleType)+','+str(primaryFuelLevelInJoules)+','+str(desiredDepartureTime)+','+str(t))

        ############### This section should be un-commented and debugged when we have a controller signal to send to BEAM
        control_commands_list = []
        for siteId, charging_events in itertools.groupby(charging_events_json, key_func):
            print(siteId)
            print(list(charging_events))

            if not siteId.str.lower().startswith('depot'):
                # TODO Myungsoo is SPMC (NOT RIDE HAIL DEPOT)
                # 1) SPMC takes list(charging_events) (and/or siteId)
                # 2) SPMC returns control_commands
                # 2.a) example
                control_commands = [{
                    'vehicleId': str(""),
                    'powerInKw': str(9.5)
                }]
                # 3) add control_commands to control_commands_list
                control_commands_list = control_commands_list + control_commands
            else:
                # TODO Julius Is SPMC (IS RIDE HAIL DEPOT)
                # 1) SPMC takes list(charging_events) (and/or siteId)
                # 1.a) Maybe a loop with => juliusObject.arrival(vehicle) and/or juliusObject.departure(vehicle)
                # We decide to share vehicle information every interval and when they disappear they plugged out
                # 2) SPMC returns control_commands => juliusObject.step(t)
                # 2.a) example
                control_commands = [{
                    'vehicleId': str(""),
                    'powerInKw': str(9.5),
                    'release': str(False)
                }]
                # 3) add control_commands to control_commands_list
                control_commands_list = control_commands_list + control_commands
        # END LOOP

        h.helicsPublicationPublishString(pubs_control, json.dumps(control_commands_list, separators=(',', ':')))
        syncTime(t + 1)

    # close the federate
    h.helicsFederateFinalize(cfed)
    logging.info("beam_to_pydss_federate finalized")

    h.helicsFederateFree(cfed)
    h.helicsCloseLibrary()


###############################################################################


data = pd.read_csv("../../../../production/sfbay/parking/sfbay_taz_unlimited_charging_point.csv")
tazes = data["taz"].unique()
logging.info(tazes)

fedinfo = h.helicsCreateFederateInfo()

# set core type
h.helicsFederateInfoSetCoreTypeFromString(fedinfo, "zmq")

# set initialization string
h.helicsFederateInfoSetCoreInitString(fedinfo, f"--federates={len(tazes)}")

# set message interval
deltat = 1.0  # smallest discernable interval to this federate
h.helicsFederateInfoSetTimeProperty(fedinfo, h.helics_property_time_delta, deltat)

feds = [create_federate(fedinfo, taz) for taz in tazes]

print(len(feds))

from threading import Thread

for fed in feds:
    t = Thread(target=run_spmc_federate, args=(fed,))
    t.start()
