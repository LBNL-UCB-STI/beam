# this file creates an intermediate federate
# it maps the coordinates from TEMPO to the nearest charging
# station modeled in PyDSS
import helics as h
import logging
import time
from threading import Thread
from rudimentary_spmc_rev3 import SPM_Control
import pandas as pd


class DefaultSPMC:
    # Myungsoo is SPMC (NOT RIDE HAIL DEPOT)
    control_commands = []
    thread = Thread()

    def __init__(self, name, site_id):
        self.site_id = site_id
        self.site_prefix_logging = name + "[SITE:" + str(site_id) + "]. "
        print2(self.site_prefix_logging + "Initializing the SPMCs...")
        self.spm_c = SPM_Control(time_step_mins=1, max_power_evse=[], min_power_evse=[])

    def log(self, log_message):
        logging.info(self.site_prefix_logging + log_message)

    def run_as_thread(self, t, charging_events):
        self.thread = Thread(target=self.run, args=(t, charging_events))
        self.thread.start()

    def get_output_from_latest_run(self):
        if self.thread.is_alive():
            self.thread.join()
        return self.control_commands

    def run(self, t, charging_events):
        vehicle_id = []
        vehicle_type = []
        primary_fuel_level_in_k_wh = []
        arrival_time = []
        desired_departure_time = []
        desired_fuel_level_in_k_wh = []
        max_power_in_kw = []  # min [plug, vehicle]
        site_power_in_kw = 0
        battery_capacity_in_k_wh = []  # TODO Julius @ HL can you please add this to the BEAM output?
        self.control_commands = []

        start_time_1 = time.time()
        for charging_event in charging_events:
            vehicle_id.append(str(charging_event['vehicleId']))
            vehicle_type.append(str(charging_event['vehicleType']))
            # MJ: What is this? Is it the current energy level of each EV? Or battery size?
            primary_fuel_level_in_k_wh.append(int(charging_event['primaryFuelLevelInJoules']) / 3600000)
            # MJ: Should be in minutes of day. What is the unit of this?
            arrival_time.append(int(charging_event['arrivalTime']))
            # MJ: Should be in minutes of day. What is the unit of this?
            desired_departure_time.append(int(charging_event['departureTime']))
            # MJ: I assume that this is remaining energy to be delivered to each EV
            # and updated each time,right?
            desired_fuel_level_in_k_wh.append(int(charging_event['desiredFuelLevelInJoules']) / 3600000)
            # MJ: I assume that this is the EV charging power
            max_power_in_kw.append(float(charging_event['maxPowerInKW']))
            # Julius @ HL can you please add this to the BEAM output?
            battery_capacity_in_k_wh.append(int(charging_event['primaryFuelCapacityInJoule']) / 3600000)
            # total site power
            site_power_in_kw = float(charging_event['sitePowerInKW'])
        end_time_1 = time.time()
        runtime_1 = end_time_1 - start_time_1
        # Myungsoo is SPMC (NOT RIDE HAIL DEPOT)
        # 1) SPMC takes list(charging_events) (and/or siteId)
        # 2) SPMC returns control_commands
        # 2.a) example
        # 3) add control_commands to control_commands_list
        # control_commands_list = control_commands_list + control_commands
        start_time_2 = time.time()
        self.spm_c.max_power_evse = max_power_in_kw
        self.spm_c.min_power_evse = [0] * len(self.spm_c.max_power_evse)
        pmin_site_in_kw = 0
        pmax_site_in_kw = site_power_in_kw

        tdep = [(tt - t) / 60.0 for tt in desired_departure_time]
        # self.log("Optimizing EVSE setpoints by the regular SPMC")
        end_time_2 = time.time()
        runtime_2 = end_time_2 - start_time_2

        start_time_3 = time.time()
        [p_evse_opt, e_evse_opt, delta_t] = self.spm_c.get_evse_setpoint(tdep, desired_fuel_level_in_k_wh, pmin_site_in_kw, pmax_site_in_kw)
        end_time_3 = time.time()
        runtime_3 = end_time_3 - start_time_3

        start_time_4 = time.time()
        i = 0
        for vehicle in charging_events:
            self.control_commands = self.control_commands + [{
                'time': str(t),
                'tazId': str(vehicle["tazId"]),
                'vehicleId': vehicle['vehicleId'],
                'powerInKW': str(p_evse_opt[i])
            }]
            i = i + 1
        end_time_4 = time.time()
        runtime_4 = end_time_4 - start_time_4

        runtime_data = [[t, self.site_id, len(charging_events), runtime_1, runtime_2, runtime_3, runtime_4]]
        df = pd.DataFrame(runtime_data, columns=['time', 'site_id', 'num_events', 'runtime_1', 'runtime_2', 'runtime_3', 'runtime_4'])
        df.to_csv('runtime.csv', mode='a', index=False, header=False)
        return self.control_commands


class RideHailSPMC:
    # Julius Is SPMC (IS RIDE HAIL DEPOT)
    control_commands = []
    thread = Thread()

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

    def run_as_thread(self, t, charging_events):
        self.thread = Thread(target=self.run, args=(t, charging_events))
        self.thread.start()

    def get_output_from_latest_run(self):
        self.thread.join()
        return self.control_commands

    def run(self, t, charging_events):
        # Julius Is SPMC (IS RIDE HAIL DEPOT)
        self.control_commands = []
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
        num_commands = len(self.control_commands)
        self.log(str(num_commands) + " EVSE setpoints from the ride-hail SPMC")
        return self.control_commands


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