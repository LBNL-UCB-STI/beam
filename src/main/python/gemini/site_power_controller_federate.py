# this file creates an intermediate federate
# it maps the coordinates from TEMPO to the nearest charging
# station modeled in PyDSS
import time
import helics as h
import pandas as pd
import logging
import json
import itertools
import os

from rudimentary_spmc import SPM_Control
import components


# if len(sys.argv) < 2:
#     logging.error("infrastructure file is missing")

def create_federate(fedinfo, tazId):
    fed_name = "SPMC_FEDERATE_" + str(tazId)

    # create federate
    cfed = h.helicsCreateCombinationFederate(fed_name, fedinfo)
    logging.info(fed_name + " created")

    logging.info("Register a publication of control signals")

    # Register a publication of control signals
    # Power in kW
    h.helicsFederateRegisterTypePublication(cfed, "CHARGING_PROFILE", "string", "")
    logging.info("publications registered")

    # register subscriptions
    # subscribe to information from TEMPO such that you can map to PyDSS modeled charging stations
    # Power in kW and Energy in Joules
    h.helicsFederateRegisterSubscription(cfed, "BEAM_SPM_FEDERATE_" + str(tazId) + "/CHARGING_SESSION_EVENTS", "string")
    logging.info("subscriptions registered")

    # subscriptions to PyDSS
    # The Power is in kW, and to get the energy multiply by 60 seconds (1 minute interval) and convert it to Joules
    # Then sum it up for the 5 minutes total interval (battery)
    # While for the load take the average
    # kW * 60/3600 => X KWH
    # kWH * 3.6e+6 => Y Joules
    # Z = Sum(Y_i); i = 1min -> 5min => Storage
    # Z* = Mean(kW_i) => Load
    return cfed


def run_spmc_federate(cfed, taz_id, timebin_in_seconds, simulated_day_in_seconds):
    # enter execution mode
    h.helicsFederateEnterExecutingMode(cfed)
    fed_name = h.helicsFederateGetName(cfed)
    logging.debug(fed_name + " in execution mode")
    subs_charging_events = h.helicsFederateGetInputByIndex(cfed, 0)
    pubs_control = h.helicsFederateGetPublicationByIndex(cfed, 0)

    # SPMC INITIALIZE HERE
    # JULIUS: @HL I initialized my SPMC here
    # @ HL can you provide the missing infromation
    initMpc = False
    t_start = int(0)
    timestep_intervall = int(timebin_in_seconds)
    result_directory = ''
    simName = 'myFirstSimulation'
    RideHailDepotId = str(taz_id)
    ChBaMaxPower = []  # list of floats in kW for each plug, for first it should be the same maximum power for all -> could set 10 000 kW if message contains data --> list with length of number of plugs from infrastructure file?
    ChBaParkingZoneId = []  # list of strings, could just be a list of empty strings as not further used so far
    ChBaNum = len(ChBaMaxPower)  # number of plugs in one depot --> infrastructure file?
    # only needed for MPC
    path_BeamPredictionFile = ''  # path to a former run of the same simulation to obtain predicitions. the beam result file should be reduced before to only contain the relevant data
    dtype_Predictions = {
        'time': 'int64', 'type': 'category', 'vehicle': 'int64', 'parkingTaz': 'category',
        'chargingPointType': 'category',
        'primaryFuelLevel': 'float64', 'mode': 'category', 'currentTourMode': 'category', 'vehicleType': 'category',
        'arrivalTime': 'float64', 'departureTime': 'float64', 'linkTravelTime': 'string', 'primaryFuelType': 'category',
        'parkingZoneId': 'category', 'duration': 'float64'
    }  # dictionary containing the data types in the beam prediction file
    # maximum time up to which we simulate (for predicting in MPC)
    t_max = int(simulated_day_in_seconds - timebin_in_seconds)

    depotController = components.GeminiWrapper.ControlWrapper(initMpc, t_start, timestep_intervall, result_directory,
                                                              simName, RideHailDepotId, ChBaMaxPower, ChBaParkingZoneId,
                                                              ChBaNum, path_BeamPredictionFile, dtype_Predictions,
                                                              t_max)
    # MYUNGSOO
    spmc = SPM_Control(time_step_mins=1, max_power_evse=[], min_power_evse=[])

    def key_func(k):
        return k['siteId']

    def syncTime(requestedtime):
        grantedtime = -1
        while grantedtime < requestedtime:
            grantedtime = h.helicsFederateRequestTime(cfed, requestedtime)

    # start execution loop
    for t in range(0, simulated_day_in_seconds - timebin_in_seconds, timebin_in_seconds):
        syncTime(t)
        logging.info("charger loads received at currenttime: " + str(t) + " seconds")
        charging_events_json = json.loads(h.helicsInputGetString(subs_charging_events))
        logging.info('Logging this as CSV')
        logging.info('stationId,estimatedLoad,currentTime')

        # Reading BEAM values
        control_commands_list = []
        for siteId, charging_events in itertools.groupby(charging_events_json, key_func):
            print(siteId)
            print(list(charging_events))

            vehicleId = []
            tazId = []
            vehicleType = []
            primaryFuelLevelInKWh = []
            arrivalTime = []
            desiredDepartureTime = []
            desiredFuelLevelInKWh = []
            maxPowerInKW = []  # min [plug, vehicle]
            batteryCapacityInKWh = []  # TODO Julius @ HL can you please add this to the BEAM output?
            for vehicle in charging_events:
                vehicleId.append(int(vehicle['vehicleId']))
                tazId.append(int(vehicle['tazId']))
                vehicleType.append(vehicle['vehicleType'])
                # MJ: What is this? Is it the current energy level of each EV? Or battery size?
                primaryFuelLevelInKWh.append(float(vehicle['primaryFuelLevelInJoules']) / 3600000)
                # MJ: Should be in minutes of day. What is the unit of this?
                arrivalTime.append(float(vehicle['arrivalTime']))
                # MJ: Should be in minutes of day. What is the unit of this?
                desiredDepartureTime.append(float(vehicle['departureTime']))
                # MJ: I assume that this is remaining energy to be delivered to each EV and updated each time, right?
                desiredFuelLevelInKWh.append(float(vehicle['desiredFuelLevelInJoules']) / 3600000)
                # MJ: I assume that this is the EV charging power
                maxPowerInKW.append(float(vehicle['maxPowerInKW']))
                # Julius @ HL can you please add this to the BEAM output?
                batteryCapacityInKWh.append(float(vehicle['primaryFuelCapacityInJoule']) / 3600000)

            # Running SPMC controllers
            if not siteId.str.lower().startswith('depot'):
                # Myungsoo is SPMC (NOT RIDE HAIL DEPOT)
                # 1) SPMC takes list(charging_events) (and/or siteId)
                # 2) SPMC returns control_commands
                # 2.a) example
                # 3) add control_commands to control_commands_list
                # control_commands_list = control_commands_list + control_commands
                spmc.max_power_evse = maxPowerInKW
                spmc.min_power_evse = [0] * len(spmc.max_power_evse)
                Pmin_siteInKW = 0
                Pmax_siteInKW = sum(maxPowerInKW)
                Tdep = [(tt - t) / 60.0 for tt in desiredDepartureTime]
                [p_evse_opt, e_evse_opt, delta_t] = spmc.get_evse_setpoint(Tdep, desiredFuelLevelInKWh, Pmin_siteInKW,
                                                                           Pmax_siteInKW)
                i = 0
                for vehicle in charging_events:
                    control_commands = [{
                        'vehicleId': vehicle['vehicleId'],
                        'powerInKw': str(p_evse_opt[i])
                    }]
                    control_commands_list = control_commands_list + control_commands
                    i = i + 1
            else:
                # Julius Is SPMC (IS RIDE HAIL DEPOT)
                # 1) SPMC takes list(charging_events) (and/or siteId)
                # 1.a) Maybe a loop with => juliusObject.arrival(vehicle) and/or juliusObject.departure(vehicle)
                # We decide to share vehicle information every interval and when they disappear they plugged out
                # 2) SPMC returns control_commands => juliusObject.step(t)
                # 2.a) example
                # 3) add control_commands to control_commands_list

                # Julius @ HL we need to add every vehicle which arrives to the depotController
                # Julius @ HL do you send a list of all vehicles which are currently at the depot or of the ones which are just arriving? - I implemented a routine here to check if they are already in the station. If not, they are added. Do we need to synchronize the SOC of vehicles in my controller with the one in the BEAM output?
                # Julius @ HL do we need to check if all vehicles which are already in my charging depot are still there in every loop of this run? i.e. could there be vehicles which just leave without me sending a departure signal? --> # @Julius yes, we should do that  --> implemented below

                # synchronize vehicles which are at station: Remove vehicles which are not in the vehicleId list from BEAM anymore
                # Julius @ HL can you please add the actual time here?
                depotController.synchronizeVehiclesAtStation(vehicleIdsAtStation=vehicleId, t_act=t)

                # VEHICLE ARRIVAL
                vehicleInDepot = []
                for vehicle in depotController.ChargingStation.ChBaVehicles:
                    vehicleInDepot.append(vehicle.vehicleId)
                for vehicle in depotController.ChargingStation.Queue:
                    vehicleInDepot.append(vehicle.vehicleId)

                for i in range(0, len(vehicleId)):
                    if vehicleId[i] not in vehicleInDepot:
                        depotController.arrival(VehicleId=vehicleId[i],
                                                VehicleType=vehicleType[i],
                                                VehicleArrival=arrivalTime[i],
                                                VehicleDesEnd=desiredDepartureTime[i],
                                                VehicleEngyInKwh=primaryFuelLevelInKWh[i],
                                                VehicleDesEngyInKwh=desiredFuelLevelInKWh[i],
                                                VehicleMaxEngy=batteryCapacityInKWh[i],
                                                VehicleMaxPower=maxPowerInKW[i],
                                                # this doesn't change within one charging session
                                                t_act=int())  # Julius @ HL can you provide the actual time

                # OBTAIN CONTROL COMMANDS
                vehicles, power, release = depotController.step(
                    timestep=int(),  # julius @ HL can you provide the timestep,
                    t_act=int(),  # julius @ HL can you provide the actual time)
                    GridPowerUpper=1e10,  # Update from DERMS, for the first we turn this off with a big number
                    GridPowerLower=-1e10,  # Update from DERMS, for the first we turn this off with a big number
                    BtmsEnergy=0,  # Update from PyDSS, for first this is deactivated in components.ChaDepParent
                )

                for i in range(0, len(vehicles)):
                    control_commands = [{
                        'vehicleId': str(vehicles[i]),
                        'power': str(power[i]),
                        'release': str(release[i])
                    }]
                    control_commands_list = control_commands_list + control_commands
        # END LOOP

        h.helicsPublicationPublishString(pubs_control, json.dumps(control_commands_list, separators=(',', ':')))
        syncTime(t + 1)

    # close the federate
    h.helicsFederateFinalize(cfed)
    logging.info("beam_to_pydss_federate finalized")

    h.helicsFederateFree(cfed)
    h.helicsCloseLibrary()

    # depotController: save results
    depotController.save()


###############################################################################


data = pd.read_csv("../../../../production/sfbay/parking/sfbay_taz_unlimited_charging_point.csv")
tazes = data["taz"].unique()
logging.info(tazes)

fedinfo = h.helicsCreateFederateInfo()

# set core type
h.helicsFederateInfoSetCoreTypeFromString(fedinfo, "zmq")

# set initialization string
h.helicsFederateInfoSetCoreInitString(fedinfo, f"--federates={len(tazes)}")

# set message interval
deltat = 1.0  # smallest discernable interval to this federate
h.helicsFederateInfoSetTimeProperty(fedinfo, h.helics_property_time_delta, deltat)

feds = [[create_federate(fedinfo, taz), taz] for taz in tazes]

print(len(feds))

from threading import Thread

timeBin = 300
simulatedDay = 60 * 3600  # 60 hours BEAM Day
# start execution loop
for [fed, tazId] in feds:
    t = Thread(target=run_spmc_federate, args=(fed, tazId, timeBin, simulatedDay))
    t.start()
