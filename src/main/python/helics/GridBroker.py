# -*- coding: utf-8 -*-
import time
import helics as h

initstring = "-f 2 --name=mainbroker"
fedinitstring = "--broker=mainbroker --federates=1"
deltat = 1.0
fedname = "GridFederate"

helicsversion = h.helicsGetVersion()

print("SENDER: Helics version = {}".format(helicsversion))

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
print("SENDER: Publication registered")

# Subscribe to PI SENDER's publication
sub = h.helicsFederateRegisterSubscription(cfed, "BeamFederate/powerOverNextInterval", "")
print("RECEIVER: Subscription registered")


# Enter execution mode #
h.helicsFederateEnterExecutingMode(cfed)
print("GRID: Entering execution mode")

#
# prevtime = 0
# currenttime = -1
# time_step = 300

rec_value = 0.0
send_value = 12345 # a dummy value

# start execution loop #
timebin = 300
currenttime = 0
for t in range(timebin, timebin*360+1, timebin):
    while currenttime < t:
        currenttime = h.helicsFederateRequestTime(cfed, t)
    if h.helicsInputIsUpdated(sub):
        rec_value = h.helicsInputGetString(sub)
        print("RECEIVER: Received value = {} at time {} from a SENDER".format(rec_value, currenttime))

        h.helicsPublicationPublishDouble(pub, send_value)
        print("SENDER: Sending value = {} at time {} to a RECEIVER".format(send_value, currenttime))

#     time.sleep(1.0)

h.helicsFederateFinalize(cfed)
print("GRID: Federate finalized")

h.helicsFederateDestroy(cfed)
h.helicsFederateFree(cfed)

# don't wait more than half an hour for all other federates to finalize and write
second = 0
while h.helicsBrokerIsConnected(broker) == 1 and second < 1800:
    time.sleep(1)
    second += 1

h.helicsCloseLibrary()
print("GRID: Broker disconnected")
