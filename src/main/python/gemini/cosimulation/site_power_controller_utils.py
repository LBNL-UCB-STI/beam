# this file creates an intermediate federate
# it maps the coordinates from TEMPO to the nearest charging
# station modeled in PyDSS
import helics as h
import logging
from threading import Thread
from nrel_spmc_controller.rudimentary_spmc_rev3 import SPM_Control
from xfc_btms_saev_controller import components


class DefaultSPMC:
    # Myungsoo is SPM Controller (NOT RIDE HAIL DEPOT)
    control_commands = []
    thread = Thread()

    def __init__(self, name, taz_id, site_id):
        self.taz_id = taz_id
        self.site_id = site_id
        self.site_prefix_logging = name + "[" + str(taz_id) + ":" + str(site_id) + "]. "
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
        self.control_commands = []
        vehicle_id = []
        vehicle_type = []
        primary_fuel_level_in_k_wh = []
        arrival_time = []
        desired_departure_time = []
        desired_fuel_level_in_k_wh = []
        max_power_in_kw = []  # min [plug, vehicle]
        site_power_in_kw = 0
        battery_capacity_in_k_wh = []  # TODO Julius @ HL can you please add this to the BEAM output?

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

        # Myungsoo is SPM Controller (NOT RIDE HAIL DEPOT)
        # 1) SPM Controller takes list(charging_events) (and/or siteId)
        # 2) SPM Controller returns control_commands
        # 2.a) example
        # 3) add control_commands to control_commands_list
        # control_commands_list = control_commands_list + control_commands
        self.spm_c.max_power_evse = max_power_in_kw
        self.spm_c.min_power_evse = [0] * len(self.spm_c.max_power_evse)
        pmin_site_in_kw = 0
        pmax_site_in_kw = site_power_in_kw
        tdep = [(tt - t) / 60.0 for tt in desired_departure_time]
        # self.log("Optimizing EVSE setpoints by the regular SPM Controller")

        [p_evse_opt, e_evse_opt, delta_t] = self.spm_c.get_evse_setpoint(
            tdep,
            desired_fuel_level_in_k_wh,
            pmin_site_in_kw,
            pmax_site_in_kw
        )

        i = 0
        for vehicle in charging_events:
            self.control_commands = self.control_commands + [{
                'time': str(t),
                'taz_id': str(self.taz_id),
                'siteId': str(self.site_id),
                'vehicleId': vehicle['vehicleId'],
                'powerInKW': str(p_evse_opt[i])
            }]
            i = i + 1
        return self.control_commands


class RideHailSPMC:
    # Julius Is SPM Controller (IS RIDE HAIL DEPOT)
    control_commands = []
    thread = Thread()

    def __init__(self, name, taz_id, site_id, time_step, simulation_duration):
        self.taz_id = taz_id
        self.site_id = site_id
        self.site_prefix_logging = name + "[" + str(taz_id) + ":" + str(site_id) + "]. "
        self.time_step = time_step
        self.simulation_duration = simulation_duration
        # JULIUS: @HL I initialized my SPM Controller here
        # @ HL can you provide the missing information
        # TODO uncomment
        initMpc = False
        t_start = int(0)
        timestep_intervall = int(self.time_step)
        result_directory = ''
        RideHailDepotId = site_id
        ChBaMaxPower = []  # list of floats in kW for each plug, for first it should be the same maximum power
        # for all -> could set 10 000 kW if message contains data --> list with length of number
        # of plugs from infrastructure file?
        ChBaParkingZoneId = []  # list of strings, could just be a list of empty strings as not further used so far
        ChBaNum = len(ChBaMaxPower)  # number of plugs in one depot --> infrastructure file?
        # only needed for MPC
        path_BeamPredictionFile = ''  # path to a former run of the same simulation to obtain predicitions.
        # the beam result file should be reduced before to only contain the relevant data
        dtype_Predictions = {
            'time': 'int64', 'type': 'category', 'vehicle': 'int64', 'parkingTaz': 'category',
            'chargingPointType': 'category', 'primaryFuelLevel': 'float64', 'mode': 'category',
            'currentTourMode': 'category', 'vehicle_type': 'category', 'arrival_time': 'float64',
            'departureTime': 'float64', 'linkTravelTime': 'string', 'primaryFuelType': 'category',
            'parkingZoneId': 'category', 'duration': 'float64'
        }  # dictionary containing the data types in the beam prediction file
        # maximum time up to which we simulate (for predicting in MPC)
        t_max = int(self.simulation_duration - self.time_step)
        self.depotController = components.GeminiWrapper.ControlWrapper(
            initMpc, t_start, timestep_intervall, result_directory, RideHailDepotId, ChBaMaxPower,
            ChBaParkingZoneId, ChBaNum, path_BeamPredictionFile, dtype_Predictions, t_max)

    def log(self, log_message):
        logging.info(self.site_prefix_logging + log_message)

    def run_as_thread(self, t, charging_events):
        self.thread = Thread(target=self.run, args=(t, charging_events))
        self.thread.start()

    def get_output_from_latest_run(self):
        self.thread.join()
        return self.control_commands

    def run(self, t, charging_events):
        self.control_commands = []
        # Julius Is SPM Controller (IS RIDE HAIL DEPOT)
        vehicle_id = []
        vehicle_type = []
        primary_fuel_level_in_k_wh = []
        arrival_time = []
        desired_departure_time = []
        desired_fuel_level_in_k_wh = []
        max_power_in_kw = []  # min [plug, vehicle]
        battery_capacity_in_k_wh = []  # TODO Julius @ HL can you please add this to the BEAM output?

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
        # 1) SPM Controller takes list(charging_events) (and/or siteId)
        # 1.a) Maybe a loop with => juliusObject.arrival(vehicle) and/or juliusObject.departure(vehicle)
        # We decide to share vehicle information every interval and when they disappear they plugged out
        # 2) SPM Controller returns control_commands => juliusObject.step(t)
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
        self.log("Optimizing EVSE setpoints by the ride-hail SPM Controller")
        # TODO uncomment
        self.depotController.synchronizeVehiclesAtStation(vehicleIdsAtStation=vehicle_id, t_act=t)
        #
        # # VEHICLE ARRIVAL
        vehicleInDepot = []
        for vehicle in self.depotController.ChargingStation.ChBaVehicles:
            vehicleInDepot.append(vehicle.vehicle_id)
        for vehicle in self.depotController.ChargingStation.Queue:
            vehicleInDepot.append(vehicle.vehicle_id)
        #
        for i in range(0, len(vehicle_id)):
            if vehicle_id[i] not in vehicleInDepot:
                self.depotController.arrival(VehicleId=vehicle_id[i],
                                             VehicleType=vehicle_type[i],
                                             VehicleArrival=arrival_time[i],
                                             VehicleDesEnd=desired_departure_time[i],
                                             VehicleEngyInKwh=primary_fuel_level_in_k_wh[i],
                                             VehicleDesEngyInKwh=desired_fuel_level_in_k_wh[i],
                                             VehicleMaxEngy=battery_capacity_in_k_wh[i],
                                             VehicleMaxPower=max_power_in_kw[i], # this doesn't change within one charging session
                                             t_act=int(t))  # Julius @ HL can you provide the actual time
        #
        # # OBTAIN CONTROL COMMANDS
        vehicles, power, release = self.depotController.step(
            timestep=int(self.time_step),  # julius @ HL can you provide the timestep,
            t_act=int(t),  # julius @ HL can you provide the actual time)
            GridPowerUpper=1e10,  # Update from DERMS, for the first we turn this off with a big number
            GridPowerLower=-1e10,  # Update from DERMS, for the first we turn this off with a big number
            BtmsEnergy=0,  # Update from PyDSS, for first this is deactivated in components.ChaDepParent
        )
        #

        for i in range(0, len(vehicles)):
            self.control_commands = self.control_commands + [{
                'time': str(t),
                'taz_id': str(self.taz_id),
                'siteId': str(self.site_id),
                'vehicleId': str(vehicles[i]),
                'powerInKW': str(power[i]),
                'release': str(release[i])
            }]
        num_commands = len(self.control_commands)
        self.log(str(num_commands) + " EVSE setpoints from the ride-hail SPM Controller")
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