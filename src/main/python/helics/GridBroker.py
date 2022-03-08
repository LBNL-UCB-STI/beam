# -*- coding: utf-8 -*-
import time
import helics as h
import json
import random

iterations = 3
initstring = "-f 2 --name=mainbroker"
fedinitstring = "--broker=mainbroker --federates=1"
deltat = 1.0
fedname = "GridFederate"
dataOuputStreamPoint = "PhysicalBounds"

beamFederateName = "CNMFederate/PowerDemand"
timebin = 300

helicsversion = h.helicsGetVersion()
print("GRID: Helics version = {}".format(helicsversion))

for t in range(0, iterations):
    print("Starting iteration {}".format(t))

    # Create broker #
    print("Creating Broker")
    broker = h.helicsCreateBroker("zmq", "", initstring)

    print("Checking if Broker is connected")
    isconnected = h.helicsBrokerIsConnected(broker)
    if isconnected == 1:
        print("Broker created and connected")

    # Create Federate Info object that describes the federate properties #
    fedinfo = h.helicsCreateFederateInfo()

    # Set Federate name #
    h.helicsFederateInfoSetCoreName(fedinfo, fedname)

    # Set core type from string #
    h.helicsFederateInfoSetCoreTypeFromString(fedinfo, "zmq")

    # Federate init string #
    h.helicsFederateInfoSetCoreInitString(fedinfo, fedinitstring)

    # Set the message interval (timedelta) for federate. Note th#
    # HELICS minimum message time interval is 1 ns and by default
    # it uses a time delta of 1 second. What is provided to the
    # setTimedelta routine is a multiplier for the default timedelta.

    # Set one second message interval #
    h.helicsFederateInfoSetTimeProperty(fedinfo, h.helics_property_time_delta, deltat)

    # Create value federate #
    cfed = h.helicsCreateCombinationFederate(fedname, fedinfo)
    print("GRID: Value federate created")

    # Register the publication #
    pub = h.helicsFederateRegisterTypePublication(cfed, dataOuputStreamPoint, "string", "")
    print("GRID: Publication '{}' registered".format(dataOuputStreamPoint))

    # Subscribe to PI SENDER's publication
    sub = h.helicsFederateRegisterSubscription(cfed, beamFederateName, "")
    print("GRID: Subscription '{}' registered".format(beamFederateName))

    # Enter execution mode #
    h.helicsFederateEnterExecutingMode(cfed)
    print("GRID: Entering execution mode")

    # start execution loop #
    currenttime = -1
    rec_value = 0.0
    time_sleep = 0 # if time_sleep is > 0 then simulation of delay happens for the Helics Grid below in the loop

    for t in range(0, timebin*360+1, timebin):
        while currenttime < t:
            currenttime = h.helicsFederateRequestTime(cfed, t)

        if time_sleep > 0:
            # time_sleep = random.uniform(0, 0.1)
            print("GRID: Simulating work (sleep for {} sec) before power flow response".format(time_sleep))
            time.sleep(time_sleep)

        #if h.helicsInputIsUpdated(sub):
        rec_value = h.helicsInputGetString(sub)
        print("GRID: Received {} at time {}".format(beamFederateName, currenttime))
        received_data = json.loads(rec_value)
        print("")
        print("****************************************************")
        print("chargingZoneId, tazId, parkingType, chargingPointType, pricingModel, 'numChargers', vehicleManager, estimatedLoad")
        for load in received_data:
            print("{}, {}, {}, {}, {}, {}, {}, {}".format(
                  load['chargingZoneId'],
                  load['tazId'],
                  load['parkingType'],
                  load['chargingPointType'],
                  load['pricingModel'],
                  load['numChargers'],
                  load['vehicleManager'],
                  load['estimatedLoad']))
            del load['estimatedLoad']
            load['maxLoad'] = 0  # a dummy physical bound
        print("****************************************************")
        print("")
        data_to_send = json.dumps(received_data, separators=(',', ':'))
        h.helicsPublicationPublishString(pub, data_to_send)
        h.helicsPublicationPublishDouble(pub, 22.0)
        print("GRID: Sending {} at time {} to CNMFederate".format(dataOuputStreamPoint, currenttime))


    h.helicsFederateFinalize(cfed)
    print("GRID: Federate finalized")

    # don't wait more than half an hour for all other federates to finalize and write
    second = 0
    while h.helicsBrokerIsConnected(broker) == 1 and second < 1800:
        time.sleep(1)
        second += 1

    h.helicsFederateDestroy(cfed)
    h.helicsFederateFree(cfed)
    print("GRID: Federate Destroyed")

    h.helicsCloseLibrary()
    print("GRID: Broker disconnected")
