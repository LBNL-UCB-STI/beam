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
h.helicsFederateInfoSetCoreName(fedinfo, "PowerOverNextInterval Federate")

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
vfed = h.helicsCreateValueFederate("PowerOverNextInterval Federate", fedinfo)
print("SENDER: Value federate created")

# Register the publication #
pub = h.helicsFederateRegisterGlobalTypePublication(vfed, "powerOverNextInterval", "double", "")
print("SENDER: Publication registered")

# Enter execution mode #
h.helicsFederateEnterExecutingMode(vfed)
print("SENDER: Entering execution mode")

# This federate will be publishing deltat*value for numsteps steps #
this_time = 0.0
value = 10000
time_m = 600

for t in range(0, 10000):
    val = value

    currenttime = h.helicsFederateRequestTime(vfed, t * time_m)

    h.helicsPublicationPublishDouble(pub, val)
    print(
        "SENDER: Sending value = {} at time {} to a RECEIVER".format(
            val, currenttime
        )
    )

    time.sleep(deltat)

h.helicsFederateFinalize(vfed)
print("SENDER: Federate finalized")

while h.helicsBrokerIsConnected(broker):
    time.sleep(1)

h.helicsFederateFree(vfed)
h.helicsCloseLibrary()

print("SENDER: Broker disconnected")
