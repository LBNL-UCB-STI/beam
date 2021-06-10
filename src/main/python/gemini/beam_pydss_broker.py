# -*- coding: utf-8 -*-
import time
import helics as h

# Create broker #
broker = h.helicsCreateBroker("zmq", "", "-f 2 --name=BeamHelicsBroker")
isConnected = h.helicsBrokerIsConnected(broker)
if isConnected == 1:
    print("Broker created and connected")
second = 0
while h.helicsBrokerIsConnected(broker) == 1 and second < 8*60*60:
    time.sleep(1)
    second += 1
h.helicsCloseLibrary()
print("Broker disconnected")
