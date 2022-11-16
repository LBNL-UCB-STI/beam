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
import collections.abc

from threading import Thread

from rudimentary_spmc import SPM_Control


class DefaultSPMC:
    # Myungsoo is SPMC (NOT RIDE HAIL DEPOT)
    def __init__(self, name, site_id):
        self.site_id = site_id
        self.site_prefix_logging = name + "[|SITE:" + str(site_id) + "]. "
        print2(self.site_prefix_logging + "Initializing the SPMCs...")
        self.spm_c = SPM_Control(time_step_mins=1, max_power_evse=[], min_power_evse=[])

    def log(self, log_message):
        logging.info(self.site_prefix_logging + log_message)

    def run(self, t, charging_events):
        vehicle_id = []
        vehicle_type = []
        primary_fuel_level_in_k_wh = []
        arrival_time = []
        desired_departure_time = []
        desired_fuel_level_in_k_wh = []
        max_power_in_kw = []  # min [plug, vehicle]
        battery_capacity_in_k_wh = []  # TODO Julius @ HL can you please add this to the BEAM output?

        self.log("Received " + str(len(charging_events)) + " charging event(s)")

        for vehicle in charging_events:
            vehicle_id.append(str(vehicle['vehicleId']))
            vehicle_type.append(str(vehicle['vehicleType']))
            # MJ: What is this? Is it the current energy level of each EV? Or battery size?
            primary_fuel_level_in_k_wh.append(int(vehicle['primaryFuelLevelInJoules']) / 3600000)
            # MJ: Should be in minutes of day. What is the unit of this?
            arrival_time.append(int(vehicle['arrivalTime']))
            # MJ: Should be in minutes of day. What is the unit of this?
            desired_departure_time.append(int(vehicle['departureTime']))
            # MJ: I assume that this is remaining energy to be delivered to each EV
            # and updated each time,right?
            desired_fuel_level_in_k_wh.append(int(vehicle['desiredFuelLevelInJoules']) / 3600000)
            # MJ: I assume that this is the EV charging power
            max_power_in_kw.append(float(vehicle['maxPowerInKW']))
            # Julius @ HL can you please add this to the BEAM output?
            battery_capacity_in_k_wh.append(int(vehicle['primaryFuelCapacityInJoule']) / 3600000)

        # Myungsoo is SPMC (NOT RIDE HAIL DEPOT)
        control_commands = []
        # 1) SPMC takes list(charging_events) (and/or siteId)
        # 2) SPMC returns control_commands
        # 2.a) example
        # 3) add control_commands to control_commands_list
        # control_commands_list = control_commands_list + control_commands
        self.spm_c.max_power_evse = max_power_in_kw
        self.spm_c.min_power_evse = [0] * len(self.spm_c.max_power_evse)
        pmin_site_in_kw = 0
        pmax_site_in_kw = sum(max_power_in_kw)
        tdep = [tt - t / 60.0 for tt in desired_departure_time]
        self.log("Optimizing EVSE setpoints by the regular SPMC")
        [p_evse_opt, e_evse_opt, delta_t] = self.spm_c.get_evse_setpoint(tdep, desired_fuel_level_in_k_wh, pmin_site_in_kw, pmax_site_in_kw)
        print2("**** TEST ****")
        print2("simulation time: {}. desired_departure_time: {}. tdep: {}. p_evse_opt: {}".format(t, desired_departure_time, tdep, p_evse_opt))
        print2(charging_events)
        i = 0
        for vehicle in charging_events:
            control_commands = control_commands + [{
                'tazId': str(vehicle["tazId"]),
                'vehicleId': vehicle['vehicleId'],
                'powerInKW': str(p_evse_opt[i])
            }]
            i = i + 1
        num_commands = len(control_commands)
        self.log(str(num_commands) + " EVSE setpoints. Sending:" + str(control_commands))
        return control_commands


class RideHailSPMC:
    # Julius Is SPMC (IS RIDE HAIL DEPOT)
    def __init__(self, name, site_id):
        self.site_id = site_id
        self.site_prefix_logging = name + "[SITE:" + str(site_id) + "]. "
        # JULIUS: @HL I initialized my SPMC here
        # @ HL can you provide the missing information
        # TODO uncomment
        # initMpc = False
        # t_start = int(0)
        # timestep_intervall = int(timebin_in_seconds)
        # result_directory = ''
        # simName = 'myFirstSimulation'
        # RideHailDepotId = taz_id
        # ChBaMaxPower = []  # list of floats in kW for each plug, for first it should be the same maximum power
        # for all -> could set 10 000 kW if message contains data --> list with length of number
        # of plugs from infrastructure file?
        # ChBaParkingZoneId = []  # list of strings, could just be a list of empty strings as not further used so far
        # ChBaNum = len(ChBaMaxPower)  # number of plugs in one depot --> infrastructure file?
        # only needed for MPC
        # path_BeamPredictionFile = ''  # path to a former run of the same simulation to obtain predicitions.
        # the beam result file should be reduced before to only contain the relevant data
        # dtype_Predictions = {
        #     'time': 'int64', 'type': 'category', 'vehicle': 'int64', 'parkingTaz': 'category',
        #     'chargingPointType': 'category',
        #     'primaryFuelLevel': 'float64', 'mode': 'category', 'currentTourMode': 'category', 'vehicle_type': 'category',
        #     'arrival_time': 'float64', 'departureTime': 'float64', 'linkTravelTime': 'string',
        #     'primaryFuelType': 'category',
        #     'parkingZoneId': 'category', 'duration': 'float64'
        # }  # dictionary containing the data types in the beam prediction file
        # maximum time up to which we simulate (for predicting in MPC)
        # t_max = int(simulated_day_in_seconds - timebin_in_seconds)
        # depotController = components.GeminiWrapper.ControlWrapper(initMpc, t_start, timestep_intervall, result_directory,
        #                                                           simName, RideHailDepotId, ChBaMaxPower, ChBaParkingZoneId,
        #                                                           ChBaNum, path_BeamPredictionFile, dtype_Predictions,
        #                                                           t_max)

    def log(self, log_message):
        logging.info(self.site_prefix_logging + log_message)

    def run(self, t, charging_events):
        # Julius Is SPMC (IS RIDE HAIL DEPOT)
        control_commands = []
        # 1) SPMC takes list(charging_events) (and/or siteId)
        # 1.a) Maybe a loop with => juliusObject.arrival(vehicle) and/or juliusObject.departure(vehicle)
        # We decide to share vehicle information every interval and when they disappear they plugged out
        # 2) SPMC returns control_commands => juliusObject.step(t)
        # 2.a) example
        # 3) add control_commands to control_commands_list

        # Julius @ HL we need to add every vehicle which arrives to the depotController
        # Julius @ HL do you send a list of all vehicles which are currently at the depot or of the ones
        # which are just arriving? - I implemented a routine here to check if they are already
        # in the station. If not, they are added. Do we need to synchronize the SOC of vehicles
        # in my controller with the one in the BEAM output?
        # Julius @ HL do we need to check if all vehicles which are already in my charging depot
        # are still there in every loop of this run? i.e. could there be vehicles which just leave
        # without me sending a departure signal? --> # @Julius yes, we should do that  --> implemented below

        # synchronize vehicles which are at station: Remove vehicles which are not in the vehicle_id
        # list from BEAM anymore
        # Julius @ HL can you please add the actual time here?
        self.log("Optimizing EVSE setpoints by the ride-hail SPMC")
        # TODO uncomment
        # depotController.synchronizeVehiclesAtStation(vehicleIdsAtStation=vehicle_id, t_act=t)
        #
        # # VEHICLE ARRIVAL
        # vehicleInDepot = []
        # for vehicle in depotController.ChargingStation.ChBaVehicles:
        #     vehicleInDepot.append(vehicle.vehicle_id)
        # for vehicle in depotController.ChargingStation.Queue:
        #     vehicleInDepot.append(vehicle.vehicle_id)
        #
        # for i in range(0, len(vehicle_id)):
        #     if vehicle_id[i] not in vehicleInDepot:
        #         depotController.arrival(VehicleId=vehicle_id[i],
        #                                 VehicleType=vehicle_type[i],
        #                                 VehicleArrival=arrival_time[i],
        #                                 VehicleDesEnd=desired_departure_time[i],
        #                                 VehicleEngyInKwh=primary_fuel_level_in_k_wh[i],
        #                                 VehicleDesEngyInKwh=desired_fuel_level_in_k_wh[i],
        #                                 VehicleMaxEngy=battery_capacity_in_k_wh[i],
        #                                 VehicleMaxPower=max_power_in_kw[i],
        #                                 # this doesn't change within one charging session
        #                                 t_act=int(t))  # Julius @ HL can you provide the actual time
        #
        # # OBTAIN CONTROL COMMANDS
        # vehicles, power, release = depotController.step(
        #     timestep=int(timebin_in_seconds),  # julius @ HL can you provide the timestep,
        #     t_act=int(t),  # julius @ HL can you provide the actual time)
        #     GridPowerUpper=1e10,  # Update from DERMS, for the first we turn this off with a big number
        #     GridPowerLower=-1e10,  # Update from DERMS, for the first we turn this off with a big number
        #     BtmsEnergy=0,  # Update from PyDSS, for first this is deactivated in components.ChaDepParent
        # )
        #
        # for i in range(0, len(vehicles)):
        #     control_commands = [{
        #         'tazId': str(taz_id),
        #         'vehicleId': str(vehicles[i]),
        #         'powerInKW': str(power[i]),
        #         'release': str(release[i])
        #     }]
        #     control_commands_list_temp = control_commands_list_temp + control_commands
        num_commands = len(control_commands)
        self.log(str(num_commands) + " EVSE setpoints from the ride-hail SPMC")
        return control_commands


def print2(to_print):
    logging.info(to_print)
    print(to_print)


# if len(sys.argv) < 2:
#     logging.error("infrastructure file is missing")

def create_federate(helics_conf, fed_info, taz_id):
    fed_name = helics_conf["spmFederatesPrefix"] + taz_id

    # create federate
    cfed = h.helicsCreateCombinationFederate(fed_name, fed_info)
    logging.info("Create combination federate " + fed_name)

    # Register a publication of control signals
    # Power in kW
    pub = helics_conf["spmSubscription"]
    h.helicsFederateRegisterTypePublication(cfed, pub, "string", "")
    logging.info("Registered to publication " + pub)

    # register subscriptions
    # subscribe to information from TEMPO such that you can map to PyDSS modeled charging stations
    # Power in kW and Energy in Joules
    sub = helics_conf["federatesPrefix"] + str(taz_id) + "/" + helics_conf["federatesPublication"]
    h.helicsFederateRegisterSubscription(cfed, sub, "string")
    logging.info("Registered to subscription " + sub)

    # subscriptions to PyDSS
    # The Power is in kW, and to get the energy multiply by 60 seconds (1 minute interval) and convert it to Joules
    # Then sum it up for the 5 minutes total interval (battery)
    # While for the load take the average
    # kW * 60/3600 => X KWH
    # kWH * 3.6e+6 => Y Joules
    # Z = Sum(Y_i); i = 1min -> 5min => Storage
    # Z* = Mean(kW_i) => Load
    return cfed


def run_spm_federate(cfed, taz_id, time_bin_in_seconds, simulated_day_in_seconds):
    # enter execution mode
    h.helicsFederateEnterExecutingMode(cfed)
    fed_name = h.helicsFederateGetName(cfed)
    taz_prefix = "[TAZ:" + taz_id + "]. "
    print2(taz_prefix + fed_name + " in execution mode")
    subs_charging_events = h.helicsFederateGetInputByIndex(cfed, 0)
    pubs_control = h.helicsFederateGetPublicationByIndex(cfed, 0)
    print2(taz_prefix + "Initializing the SPMCs...")
    # SPMC INITIALIZE HERE
    # JULIUS: @HL I initialized my SPMC here
    # @ HL can you provide the missing information
    # TODO uncomment
    # initMpc = False
    # t_start = int(0)
    # timestep_intervall = int(timebin_in_seconds)
    # result_directory = ''
    # simName = 'myFirstSimulation'
    # RideHailDepotId = taz_id
    # ChBaMaxPower = []  # list of floats in kW for each plug, for first it should be the same maximum power
    # for all -> could set 10 000 kW if message contains data --> list with length of number
    # of plugs from infrastructure file?
    # ChBaParkingZoneId = []  # list of strings, could just be a list of empty strings as not further used so far
    # ChBaNum = len(ChBaMaxPower)  # number of plugs in one depot --> infrastructure file?
    # only needed for MPC
    # path_BeamPredictionFile = ''  # path to a former run of the same simulation to obtain predicitions.
    # the beam result file should be reduced before to only contain the relevant data
    # dtype_Predictions = {
    #     'time': 'int64', 'type': 'category', 'vehicle': 'int64', 'parkingTaz': 'category',
    #     'chargingPointType': 'category',
    #     'primaryFuelLevel': 'float64', 'mode': 'category', 'currentTourMode': 'category', 'vehicle_type': 'category',
    #     'arrival_time': 'float64', 'departureTime': 'float64', 'linkTravelTime': 'string',
    #     'primaryFuelType': 'category',
    #     'parkingZoneId': 'category', 'duration': 'float64'
    # }  # dictionary containing the data types in the beam prediction file
    # maximum time up to which we simulate (for predicting in MPC)
    # t_max = int(simulated_day_in_seconds - timebin_in_seconds)
    # depotController = components.GeminiWrapper.ControlWrapper(initMpc, t_start, timestep_intervall, result_directory,
    #                                                           simName, RideHailDepotId, ChBaMaxPower, ChBaParkingZoneId,
    #                                                           ChBaNum, path_BeamPredictionFile, dtype_Predictions,
    #                                                           t_max)
    # MYUNGSOO
    spmc = SPM_Control(time_step_mins=1, max_power_evse=[], min_power_evse=[])

    def key_func(k):
        return k['siteId']

    def sync_time(requested_time):
        granted_time = -1
        while granted_time < requested_time:
            granted_time = h.helicsFederateRequestTime(cfed, requested_time)

    def parse_json(message_to_parse):
        try:
            charging_events_json = json.loads(message_to_parse)
            return charging_events_json
        except json.decoder.JSONDecodeError as err:
            error_text = taz_prefix + "Message from BEAM is an incorrect JSON, " + str(err)
            logging.error(error_text)
            print(error_text)
            return ""

    # start execution loop
    for t in range(0, simulated_day_in_seconds - time_bin_in_seconds, time_bin_in_seconds):
        sync_time(t)
        t_hour_min = str(int(t / 3600)) + ":" + str(round((t % 3600)/60))
        control_commands_list = []
        received_message = h.helicsInputGetString(subs_charging_events)
        # print2(taz_prefix +
        #        "Message received at simulation time: " + str(t) + " seconds (" + t_hour_min + "). " +
        #        "Message length: " + str(len(received_message)) + ". Message: " + str(received_message))
        if bool(str(received_message).strip()):
            charging_events_json = parse_json(received_message)
            if not isinstance(charging_events_json, collections.abc.Sequence):
                # logging.error(taz_prefix + "Was not able to parse JSON message from BEAM. Something is broken!")
                pass
            elif len(charging_events_json) > 0 and 'vehicleId' in charging_events_json[0]:
                print2(taz_prefix +
                       "Message received at simulation time: " + str(t) + " seconds (" + t_hour_min + "). " +
                       "Message length: " + str(len(received_message)) + ". Message: " + str(received_message))
                # Reading BEAM values
                for siteId, charging_events in itertools.groupby(charging_events_json, key_func):
                    site_prefix = "[TAZ:" + taz_id + "|SITE:" + str(siteId) + "]. "
                    charging_events_list = list(charging_events)
                    logging.info(site_prefix + "Received " + str(len(charging_events_list)) + " charging event(s)")
                    vehicle_id = []
                    vehicle_type = []
                    primary_fuel_level_in_k_wh = []
                    arrival_time = []
                    desired_departure_time = []
                    desired_fuel_level_in_k_wh = []
                    max_power_in_kw = []  # min [plug, vehicle]
                    battery_capacity_in_k_wh = []  # TODO Julius @ HL can you please add this to the BEAM output?
                    for vehicle in charging_events_list:
                        vehicle_id.append(str(vehicle['vehicleId']))
                        vehicle_type.append(str(vehicle['vehicleType']))
                        # MJ: What is this? Is it the current energy level of each EV? Or battery size?
                        primary_fuel_level_in_k_wh.append(int(vehicle['primaryFuelLevelInJoules']) / 3600000)
                        # MJ: Should be in minutes of day. What is the unit of this?
                        arrival_time.append(int(vehicle['arrivalTime']))
                        # MJ: Should be in minutes of day. What is the unit of this?
                        desired_departure_time.append(int(vehicle['departureTime']))
                        # MJ: I assume that this is remaining energy to be delivered to each EV
                        # and updated each time,right?
                        desired_fuel_level_in_k_wh.append(int(vehicle['desiredFuelLevelInJoules']) / 3600000)
                        # MJ: I assume that this is the EV charging power
                        max_power_in_kw.append(float(vehicle['maxPowerInKW']))
                        # Julius @ HL can you please add this to the BEAM output?
                        battery_capacity_in_k_wh.append(int(vehicle['primaryFuelCapacityInJoule']) / 3600000)

                    # Running SPMC controllers
                    if not siteId.lower().startswith('depot'):
                        # Myungsoo is SPMC (NOT RIDE HAIL DEPOT)
                        control_commands_list_temp = []
                        # 1) SPMC takes list(charging_events) (and/or siteId)
                        # 2) SPMC returns control_commands
                        # 2.a) example
                        # 3) add control_commands to control_commands_list
                        # control_commands_list = control_commands_list + control_commands
                        spmc.max_power_evse = max_power_in_kw
                        spmc.min_power_evse = [0] * len(spmc.max_power_evse)
                        pmin_site_in_kw = 0
                        pmax_site_in_kw = sum(max_power_in_kw)
                        tdep = [(tt - t) / 60.0 for tt in desired_departure_time]
                        logging.info(site_prefix + "Optimizing EVSE setpoints by the regular SPMC")
                        [p_evse_opt, e_evse_opt, delta_t] = spmc.get_evse_setpoint(tdep, desired_fuel_level_in_k_wh,
                                                                                   pmin_site_in_kw,
                                                                                   pmax_site_in_kw)
                        i = 0
                        for vehicle in charging_events:
                            control_commands = [{
                                'tazId': str(taz_id),
                                'vehicleId': vehicle['vehicleId'],
                                'powerInKW': str(p_evse_opt[i])
                            }]
                            control_commands_list_temp = control_commands_list_temp + control_commands
                            i = i + 1

                        num_commands = len(control_commands_list_temp)
                        logging.info(site_prefix + str(num_commands) + " EVSE setpoints from the regular SPMC. Sending " + str(control_commands))
                        control_commands_list = control_commands_list + control_commands_list_temp
                    else:
                        # Julius Is SPMC (IS RIDE HAIL DEPOT)
                        control_commands_list_temp = []
                        # 1) SPMC takes list(charging_events) (and/or siteId)
                        # 1.a) Maybe a loop with => juliusObject.arrival(vehicle) and/or juliusObject.departure(vehicle)
                        # We decide to share vehicle information every interval and when they disappear they plugged out
                        # 2) SPMC returns control_commands => juliusObject.step(t)
                        # 2.a) example
                        # 3) add control_commands to control_commands_list

                        # Julius @ HL we need to add every vehicle which arrives to the depotController
                        # Julius @ HL do you send a list of all vehicles which are currently at the depot or of the ones
                        # which are just arriving? - I implemented a routine here to check if they are already
                        # in the station. If not, they are added. Do we need to synchronize the SOC of vehicles
                        # in my controller with the one in the BEAM output?
                        # Julius @ HL do we need to check if all vehicles which are already in my charging depot
                        # are still there in every loop of this run? i.e. could there be vehicles which just leave
                        # without me sending a departure signal? --> # @Julius yes, we should do that  --> implemented below

                        # synchronize vehicles which are at station: Remove vehicles which are not in the vehicle_id
                        # list from BEAM anymore
                        # Julius @ HL can you please add the actual time here?
                        logging.info(site_prefix + "Optimizing EVSE setpoints by the ride-hail SPMC")
                        # TODO uncomment
                        # depotController.synchronizeVehiclesAtStation(vehicleIdsAtStation=vehicle_id, t_act=t)
                        #
                        # # VEHICLE ARRIVAL
                        # vehicleInDepot = []
                        # for vehicle in depotController.ChargingStation.ChBaVehicles:
                        #     vehicleInDepot.append(vehicle.vehicle_id)
                        # for vehicle in depotController.ChargingStation.Queue:
                        #     vehicleInDepot.append(vehicle.vehicle_id)
                        #
                        # for i in range(0, len(vehicle_id)):
                        #     if vehicle_id[i] not in vehicleInDepot:
                        #         depotController.arrival(VehicleId=vehicle_id[i],
                        #                                 VehicleType=vehicle_type[i],
                        #                                 VehicleArrival=arrival_time[i],
                        #                                 VehicleDesEnd=desired_departure_time[i],
                        #                                 VehicleEngyInKwh=primary_fuel_level_in_k_wh[i],
                        #                                 VehicleDesEngyInKwh=desired_fuel_level_in_k_wh[i],
                        #                                 VehicleMaxEngy=battery_capacity_in_k_wh[i],
                        #                                 VehicleMaxPower=max_power_in_kw[i],
                        #                                 # this doesn't change within one charging session
                        #                                 t_act=int(t))  # Julius @ HL can you provide the actual time
                        #
                        # # OBTAIN CONTROL COMMANDS
                        # vehicles, power, release = depotController.step(
                        #     timestep=int(timebin_in_seconds),  # julius @ HL can you provide the timestep,
                        #     t_act=int(t),  # julius @ HL can you provide the actual time)
                        #     GridPowerUpper=1e10,  # Update from DERMS, for the first we turn this off with a big number
                        #     GridPowerLower=-1e10,  # Update from DERMS, for the first we turn this off with a big number
                        #     BtmsEnergy=0,  # Update from PyDSS, for first this is deactivated in components.ChaDepParent
                        # )
                        #
                        # for i in range(0, len(vehicles)):
                        #     control_commands = [{
                        #         'tazId': str(taz_id),
                        #         'vehicleId': str(vehicles[i]),
                        #         'powerInKW': str(power[i]),
                        #         'release': str(release[i])
                        #     }]
                        #     control_commands_list_temp = control_commands_list_temp + control_commands
                        num_commands = len(control_commands_list_temp)
                        logging.info(site_prefix + str(num_commands) + " EVSE setpoints from the ride-hail SPMC")
                        control_commands_list = control_commands_list + control_commands_list_temp
                # END LOOP
            elif len(charging_events_json) > 0 and 'vehicleId' not in charging_events_json[0]:
                pass
                # logging.debug(taz_prefix +
                #               "No charging events were observed from BEAM from TAZ: " +
                #               str(charging_events_json[0]["tazId"]))
            else:
                # logging.error(taz_prefix +
                #               "The loaded JSON message is not valid. Something is broken! " +
                #               "Here is the received message" + str(charging_events_json))
                pass
        else:
            # logging.error(taz_prefix + "SPMC received empty message from BEAM. Something is broken!")
            pass

        message_to_send = control_commands_list
        if not message_to_send:
            message_to_send = [{'tazId': str(taz_id)}]
        h.helicsPublicationPublishString(pubs_control, json.dumps(message_to_send, separators=(',', ':')))
        sync_time(t + 1)

    # close the federate
    h.helicsFederateDisconnect(cfed)
    print2(taz_prefix + "Federate finalized and now saving and finishing")
    h.helicsFederateFree(cfed)
    h.helicsCloseLibrary()
    # depotController: save results
    # TODO uncomment
    # depotController.save()
    print2("Finished")
