# -*- coding: utf-8 -*-
import time
import helics as h

# Create broker #
broker = h.helicsCreateBroker("zmq", "", "-f 2 --name=federate_broker")
isConnected = h.helicsBrokerIsConnected(broker)
if isConnected == 1:
    print("Broker created and connected")
fedInfo = h.helicsCreateFederateInfo()
h.helicsFederateInfoSetCoreName(fedInfo, "mapping_federate")
h.helicsFederateInfoSetCoreTypeFromString(fedInfo, "zmq")
h.helicsFederateInfoSetCoreInitString(fedInfo, "--federates=1")
# deltat is multiplied for default timedelta of 1 second
h.helicsFederateInfoSetTimeProperty(fedInfo, h.helics_property_time_delta, 1.0)
cfed = h.helicsCreateCombinationFederate("mapping_federate", fedInfo)


def getSubscription(fed_x, fed_type):
    print("subscribing to {}".format(fed_x))
    return h.helicsFederateRegisterSubscription(cfed, fed_x, fed_type)


beamFederateName = "BeamFederate1"
subs_event = getSubscription("{}/event".format(beamFederateName), "string")
subs_soc = getSubscription("{}/soc".format(beamFederateName), "double")
subs_lat = getSubscription("{}/lat".format(beamFederateName), "double")
subs_lng = getSubscription("{}/lng".format(beamFederateName), "double")

print("Waiting...")
h.helicsFederateEnterExecutingMode(cfed)

# start execution loop #
timebin = 300
currenttime = 0
for t in range(timebin, timebin*360+1, timebin):
    while currenttime < t:
        currenttime = h.helicsFederateRequestTime(cfed, t)
    if h.helicsInputIsUpdated(subs_event) == 1:
        event = h.helicsInputGetString(subs_event)
        soc = h.helicsInputGetDouble(subs_soc)
        lat = h.helicsInputGetDouble(subs_lat)
        lng = h.helicsInputGetDouble(subs_lng)
        print("seconds:{}, event:{}, Joules:{}, lat:{}, lng:{}\n".format(currenttime, event, soc, lat, lng))

h.helicsFederateFinalize(cfed)
h.helicsFederateDestroy(cfed)
h.helicsFederateFree(cfed)
# don't wait more than half an hour for all other federates to finalize and write
second = 0
while h.helicsBrokerIsConnected(broker) == 1 and second < 1800:
    time.sleep(1)
    second += 1
h.helicsCloseLibrary()
print("Broker disconnected")
