# -*- coding: utf-8 -*-
import time
import helics as h


initstring = "-f "+"2"+" --name=federate_broker"
fedinitstring = "--federates=1"
deltat = 0.01

helicsversion = h.helicsGetVersion()

print("PI SENDER: Helics version = {}".format(helicsversion))

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
h.helicsFederateInfoSetCoreName(fedinfo, "mapping_federate")

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
cfed = h.helicsCreateCombinationFederate("mapping_federate", fedinfo)
print("PI SENDER: Combination federate created")

subs_plugIn = h.helicsFederateRegisterSubscription(cfed, "BeamFederate1/plugIn", "vector")
subs_plugOut = h.helicsFederateRegisterSubscription(cfed, "BeamFederate1/plugOut", "vector")

print("PI SENDER: subscription registered")

# Enter execution mode #
h.helicsFederateEnterExecutingMode(cfed)
print("PI SENDER: Entering execution mode")

plugInUpdated = h.helicsInputIsUpdated(subs_plugIn)
plugOutUpdated = h.helicsInputIsUpdated(subs_plugOut)

# start execution loop #
currenttime = 0
desiredtime = 0.0
t = 0.0
end_time = 30*3600
while desiredtime <= end_time:
    currenttime = h.helicsFederateRequestTime(cfed, 100)
    if h.helicsInputIsUpdated(subs_plugIn) == 1:
        h.helicsInputGetVector(subs_plugIn)
        print("plugIn event [{}]: {} {} {} {}\n".format(currenttime,
                                                        h.helicsInputGetVector(subs_plugInVehId),
                                                        h.helicsInputGetVector(subs_plugInSOC),
                                                        h.helicsInputGetVector(subs_plugInLng),
                                                        h.helicsInputGetVector(subs_plugInLat)))
    if h.helicsInputIsUpdated(subs_plugOutVehId) == 1:
        print("plugOut event [{}]: {} {} {} {}\n".format(currenttime,
                                                         h.helicsInputGetVector(subs_plugOutVehId),
                                                         h.helicsInputGetVector(subs_plugOutSOC),
                                                         h.helicsInputGetVector(subs_plugOutLng),
                                                         h.helicsInputGetVector(subs_plugOutLat)))

# all other federates should have finished, so now you can close the broker
h.helicsFederateFinalize(cfed)
print("PI SENDER: Test Federate finalized")
h.helicsFederateDestroy(cfed)
print("PI SENDER: test federate destroyed")
h.helicsFederateFree(cfed)

# this federate has finished, but don't finalize until all have finished
iters = 0
max_iters = 60*30 # don't wait more than half an hour for all other federates to finalize and write
while h.helicsBrokerIsConnected(broker)==1 and iters<max_iters:
    time.sleep(1)
    iters += 1

h.helicsCloseLibrary()
print("PI SENDER: Broker disconnected")