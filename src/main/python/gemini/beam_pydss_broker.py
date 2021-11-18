# -*- coding: utf-8 -*-
import time
import helics as h
import sys

# helics_recorder beam_recorder.txt --output=recording_output.txt
# Create broker #
print("Script started")
nbFed = 3
if len(sys.argv) > 1:
    nbFed = int(sys.argv[1])
brokerSetting = "-f {} --name=BeamHelicsBroker".format(nbFed)
print("broker setting: {}".format(brokerSetting))
broker = h.helicsCreateBroker("zmq", "", brokerSetting)
isConnected = h.helicsBrokerIsConnected(broker)
if isConnected == 1:
    print("Broker created and connected")
second = 0
while h.helicsBrokerIsConnected(broker) == 1 and second < 7*24*3600:
    time.sleep(1)
    second += 1
    if second % 3600 == 0:
        print("{} hours passed".format(second/3600))
h.helicsCloseLibrary()
print("Broker disconnected")
