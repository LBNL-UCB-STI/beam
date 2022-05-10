# this file creates an intermediate federate
# it maps the coordinates from TEMPO to the nearest charging
# station modeled in PyDSS
import time
import helics as h
import numpy as np
import logging
import json
import os
import components.GeminiWrapper as juliusLib # this is julius package


# if len(sys.argv) < 2:
#     logging.error("infrastructure file is missing")


data = readfile("infrastructure.file.csv")
for parkingZone in data["parkingZoneId"].unique:
    run_spmc_federate(parkingZone["parkingZoneId"])


def run_spmc_federate(parkingZoneId):
    fedinfo = h.helicsCreateFederateInfo()

    fed_name = "SPMC_FEDERATE_"+str(parkingZoneId)
    # set the name
    h.helicsFederateInfoSetCoreName(fedinfo, fed_name)

    # set core type
    h.helicsFederateInfoSetCoreTypeFromString(fedinfo, "zmq")

    # set initialization string
    h.helicsFederateInfoSetCoreInitString(fedinfo, "--federates=1")

    # set message interval
    deltat = 1.0  # smallest discernable interval to this federate
    h.helicsFederateInfoSetTimeProperty(fedinfo, h.helics_property_time_delta, deltat)

    # create federate
    cfed = h.helicsCreateCombinationFederate(fed_name, fedinfo)
    logging.info(fed_name + " created")

    print("Register a publication of control signals")

    # Register a publication of control signals
    pubs_control = h.helicsFederateRegisterTypePublication(cfed, "CHARGING_PROFILE", "string", "")
    logging.info("publications registered")

    # register subscriptions
    # subscribe to information from TEMPO such that you can map to PyDSS modeled charging stations
    subs_charging_events = h.helicsFederateRegisterSubscription(cfed, "BEAM_SPM_FEDERATE_"+str(parkingZoneId)+"/CHARGING_SESSION_EVENTS", "string")
    logging.info("subscriptions registered")

    # enter execution mode
    h.helicsFederateEnterExecutingMode(cfed)
    logging.info(fed_name + " in execution mode")

    print("entered execution mode")
    #TODO INITIALIZE HERE
    juliusObject = juliusLib.ControlWrapper(data.loc[data["parkingZoneId"] == parkingZoneId])

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
        charging_events_json = json.loads(h.helicsInputGetString(subs_charging_events))
        logging.info('Logging this as CSV')
        logging.info('stationId,estimatedLoad,currentTime')

        #for vehicle in charging_events_json["chargingPlugoutEvents"]:
            #vehicleId = vehicle['vehicleId']
            #juliusObject.departure(vehicle)

        for vehicle in charging_events_json["chargingPluginEvents"]:
            
            vehicleId = vehicle['parkingZoneId']
            vehicleId = vehicle['vehicleId']
            vehicleType = vehicle['vehicleType']
            primaryFuelLevelinJoules = vehicle['primaryFuelLevel']
            arrivalTime = vehicle['arrivalTime']
            desiredDepartureTime = vehicle['desiredDepartureTime']
            desiredFuelLevelInJoules = vehicle['desiredFuelLevel']
            logging.info(str(vehicleId)+','+str(vehicleType)+','+str(primaryFuelLevel)+','+str(t))

            juliusObject.arrival(vehicleId, vehicleType, arrivalTime, desiredDepartureTime, primaryFuelLevelinJoules, desiredFuelLevelInJoules) # lets call this with the variables, avoid mistakes with data structures

        ############### This section should be un-commented and debugged when we have a controller signal to send to BEAM
        ## format appropriately here
        #TODO CONTROL CODE RESIDE HERE
        control_command = juliusObject.step(timebin) # this takes the timestep as an input

        # Let's uncomment this and send dummy control signal to BEAM
        ## send updated signal to BEAM
        control_command_list = []
        for command in control_command:
            control_command_list.append({
                'vehicleId': str(command['vehicleId']),
                'power': str(command['power']),
                'release': str(command['release'])
            })

        h.helicsPublicationPublishString(pubs_control, json.dumps(control_command_list, separators=(',', ':')))
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
    run_spmc_federate()
