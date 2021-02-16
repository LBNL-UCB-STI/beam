# -*- coding: utf-8 -*-
import time
import helics as h

iterations = 3
initstring = "-f 2 --name=mainbroker"
fedinitstring = "--broker=mainbroker --federates=1"
deltat = 1.0
fedname = "GridFederate"

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
    pub = h.helicsFederateRegisterTypePublication(cfed, "powerFlow", "double", "")
    print("GRID: Publication 'powerFlow' registered")

    # Subscribe to PI SENDER's publication
    sub = h.helicsFederateRegisterSubscription(cfed, "BeamFederate/powerOverNextInterval", "")
    print("GRID: Subscription 'powerOverNextInterval' registered")

    # Enter execution mode #
    h.helicsFederateEnterExecutingMode(cfed)
    print("GRID: Entering execution mode")

    # start execution loop #
    timebin = 300
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

        if h.helicsInputIsUpdated(sub):
            rec_value = h.helicsInputGetString(sub)
            print("GRID: Received 'powerOverNextInterval' with value = {} at time {} from BeamFederate".format(rec_value, currenttime))


            send_value = 12345 # a dummy value

            h.helicsPublicationPublishDouble(pub, send_value)
            print("GRID: Sending 'powerFlow' with value = {} at time {} to BeamFederate".format(send_value, currenttime))


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
