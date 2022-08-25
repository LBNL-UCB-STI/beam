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

    fed_name = "SPMC_FEDERATE_"+str(tazId)

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


def key_func(k):
    return k['siteId']


def run_spmc_federate(cfed):
    # enter execution mode
    h.helicsFederateEnterExecutingMode(cfed)
    fed_name = h.helicsFederateGetName(cfed)
    logging.debug(fed_name + " in execution mode")
    subs_charging_events = h.helicsFederateGetInputByIndex(cfed, 0)
    pubs_control = h.helicsFederateGetPublicationByIndex(cfed, 0)

    #TODO INITIALIZE HERE
    # juliusObject = juliusLib.initialize(data.loc[data["parkingZoneId"] == parkingZoneId])

    def syncTime(requestedtime):
        grantedtime = -1
        while grantedtime < requestedtime:
            grantedtime = h.helicsFederateRequestTime(cfed, requestedtime)

    timebin = 300
    # start execution loop
    for t in range(0, 60*3600-timebin, timebin):
        syncTime(t)
        logging.info("charger loads received at currenttime: " + str(t) + " seconds")
        logging.info("charger loads received at currenttime: " + str(t) + " seconds")
        charging_events_json = json.loads(h.helicsInputGetString(subs_charging_events))
        logging.info('Logging this as CSV')
        logging.info('stationId,estimatedLoad,currentTime')

        #for vehicle in charging_events_json["chargingPlugoutEvents"]:
            #juliusObject.departure(vehicle)

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
        #     # juliusObject.arrival(vehicle)


        ############### This section should be un-commented and debugged when we have a controller signal to send to BEAM
        # TODO JULIUS SECTION CONTROL CODE RESIDE HERE
        # control_command = juliusObject.step(t)
        control_commands_list = []
        for key, value in itertools.groupby(charging_events_json, key_func):
            print(key)
            print(list(value))
            # 1) SPMC takes list(value) (and/or key)
            # 2) SPMC returns control_commands
            # 2.a) example
            control_commands = [{
                'vehicleId': "",
                'powerInKw': 9.5
            }]
            # 3) add control_commands to control_commands_list
            control_commands_list = control_commands_list + control_commands
        # END LOOP

        h.helicsPublicationPublishString(pubs_control, json.dumps(control_commands_list, separators=(',', ':')))
        syncTime(t+1)

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

