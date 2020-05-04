# -*- coding: utf-8 -*-
import time
import helics as h

# Create broker #
broker = h.helicsCreateBroker("zmq", "", "-f 2 --name=BeamBroker")
isConnected = h.helicsBrokerIsConnected(broker)
if isConnected == 1:
    print("Broker created and connected")
fedInfo = h.helicsCreateFederateInfo()
fedName = "BeamFederateReader"
h.helicsFederateInfoSetCoreName(fedInfo, fedName)
h.helicsFederateInfoSetCoreTypeFromString(fedInfo, "zmq")
h.helicsFederateInfoSetCoreInitString(fedInfo, "--federates=1")
# deltat is multiplied for default timedelta of 1 second
h.helicsFederateInfoSetTimeProperty(fedInfo, h.helics_property_time_delta, 1.0)
cfed = h.helicsCreateCombinationFederate(fedName, fedInfo)

print("Subscribing...")
subs_chargingPlugIn = h.helicsFederateRegisterSubscription(cfed, "BeamFederate/chargingPlugIn", "string")
subs_chargingPlugOut = h.helicsFederateRegisterSubscription(cfed, "BeamFederate/chargingPlugOut", "string")

print("Waiting Execution Mode...")
h.helicsFederateEnterInitializingMode(cfed)
h.helicsFederateEnterExecutingMode(cfed)

# start execution loop #
timebin = 300
currenttime = 0
for t in range(timebin, timebin*360+1, timebin):
    while currenttime < t:
        currenttime = h.helicsFederateRequestTime(cfed, t)
    if h.helicsInputIsUpdated(subs_chargingPlugIn) == 1:
        chargingPlugIn = h.helicsInputGetString(subs_chargingPlugIn)
        arr = chargingPlugIn.split(',')
        print("vehId:{}, Joules:{}, lat:{}, lng:{}\n".format(arr[0], arr[1], arr[2], arr[3]))
    if h.helicsInputIsUpdated(subs_chargingPlugOut) == 1:
        chargingPlugOut = h.helicsInputGetString(subs_chargingPlugOut)
        arr = chargingPlugOut.split(',')
        print("vehId:{}, Joules:{}, lat:{}, lng:{}\n".format(arr[0], arr[1], arr[2], arr[3]))


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
