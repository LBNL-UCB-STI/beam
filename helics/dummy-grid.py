# -*- coding: utf-8 -*-
import time
import helics as h

initstring = "-f 2 --name=mainbroker"
fedinitstring = "--broker=mainbroker --federates=1"
deltat = 0.1

helicsversion = h.helicsGetVersion()

print("SENDER: Helics version = {}".format(helicsversion))

# Create broker #
print("Creating Broker")
broker = h.helicsCreateBroker("zmq", "", initstring)
print("Created Broker")

print("Checking if Broker is connected")
isconnected = h.helicsBrokerIsConnected(broker)
print("Checked if Broker is connected")

if isconnected == 1:
    print("Broker created and connected")

# Create Federate Info object that describes the federate properties #
fedinfo = h.helicsCreateFederateInfo()

# Set Federate name #
h.helicsFederateInfoSetCoreName(fedinfo, "Power Federate")

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
vfed = h.helicsCreateValueFederate("Power Federate", fedinfo)
print("SENDER: Value federate created")

# Register the publication #
pub = h.helicsFederateRegisterGlobalTypePublication(vfed, "powerOverNextIntervalResponse", "double", "")
print("SENDER: Publication registered")

# Subscribe to PI SENDER's publication
sub = h.helicsFederateRegisterSubscription(vfed, "powerOverNextIntervalRequest", "")
print("RECEIVER: Subscription registered")


# Enter execution mode #
h.helicsFederateEnterExecutingMode(vfed)
print("SENDER: Entering execution mode")

value = 0.0
prevtime = 0
currenttime = -1
time_step = 300

while currenttime <= 100000:

    currenttime = h.helicsFederateRequestTime(vfed, time_step + prevtime)

    # if h.helicsInputIsUpdated(sub):
    value = h.helicsInputGetString(sub)
    print("RECEIVER: Received value = {} at time {} from a SENDER".format(value, currenttime))

    # TODO introduce a dummy logic and a delay
    val = 12345

    h.helicsPublicationPublishDouble(pub, val)
    print("SENDER: Sending value = {} at time {} to a RECEIVER".format(val, currenttime))

    prevtime = currenttime
    time.sleep(0.1)

h.helicsFederateFinalize(vfed)
print("SENDER: Federate finalized")

while h.helicsBrokerIsConnected(broker):
    time.sleep(1)

h.helicsFederateFree(vfed)
h.helicsCloseLibrary()

print("SENDER: Broker disconnected")
