# this file creates an intermediate federate
# it maps the coordinates from TEMPO to the nearest charging
# station modeled in PyDSS
import helics as h
import logging
from threading import Thread
from nrel_spmc_controller.rudimentary_spmc_rev3 import SPM_Control as SPM_Control_Rudimentary
from nrel_spmc_controller.spmc_v5 import SPM_Control as SPM_Control_Advanced
from xfc_btms_saev_controller import components
from abc import abstractmethod


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


class ConfigForSPMC:
    def __init__(self, time_step, sim_dur, output_dir, is_multi_threaded):
        self.time_step = time_step
        self.sim_dur = sim_dur
        self.output_dir = output_dir
        self.is_multi_threaded = is_multi_threaded


class DataForSPMC:
    def __init__(self, initial_ess_soc, updated_ess_soc, updated_power_limits_from_derms):
        self.updated_ess_soc_internal = initial_ess_soc
        self.updated_ess_soc_from_dss = updated_ess_soc
        self.updated_power_limits_from_derms = updated_power_limits_from_derms
        self.vehicle_id = []
        self.vehicle_type = []
        self.primary_fuel_level_in_k_wh = []
        self.arrival_time = []
        self.desired_departure_time = []
        self.desired_fuel_level_in_k_wh = []
        self.max_power_in_kw = []  # min [plug, vehicle]
        self.site_power_in_kw = 0
        self.battery_capacity_in_k_wh = []  # TODO Julius @ HL can you please add this to the BEAM output?
        pass


class AbstractSPMC:
    public_controlled_spmc_label = "PublicControlledSPMC"
    ride_hail_controlled_spmc_label = "RideHailControlledSPMC"
    ride_hail_using_public_controlled_spmc_label = "RideHailUsingPublicControlledSPMC"
    public_rudimentary_spmc_label = "PublicRudimentarySPMC"
    ride_hail_rudimentary_spmc_label = "RideHailRudimentarySPMC"
    @abstractmethod
    def run_model(self, t, vehicles, data_for_spmc):
        return []

    def __init__(self, name, taz_id, site_id, events, config_for_spmc):
        self.config_for_spmc = config_for_spmc
        self.taz_id = taz_id
        self.site_id = site_id
        self.site_prefix_logging = name + "[" + str(taz_id) + ":" + str(site_id) + "]. "
        self.num_plugs = int(events[0]['parkingZoneNumPlugs'])
        self.site_power = float(events[0]['parkingZonePowerInKW'])
        self.ess_size = float(events[0]['energyStorageCapacityInKWh'])  # in kWh
        self.initial_ess_soc = float(events[0]['energyStorageSOC'])  #
        self.control_commands = []
        self.thread = Thread()

    @staticmethod
    def create(taz_id_str, parking_zone_id_str, events, config_for_spmc):
        load_management = str(events[0]['loadManagement'])  #
        if load_management == AbstractSPMC.public_controlled_spmc_label:
            return ControlledSPMC("PublicSPMC", taz_id_str, parking_zone_id_str, events, config_for_spmc)
        elif load_management == AbstractSPMC.public_rudimentary_spmc_label:
            return RudimentarySPMC("PublicSPMC", taz_id_str, parking_zone_id_str, events, config_for_spmc)
        elif load_management == AbstractSPMC.ride_hail_controlled_spmc_label:
            return RideHailSPMC("RideHailSPMC", taz_id_str, parking_zone_id_str, events, config_for_spmc)
        elif load_management == AbstractSPMC.ride_hail_using_public_controlled_spmc_label:
            return ControlledSPMC("RideHailSPMC", taz_id_str, parking_zone_id_str, events, config_for_spmc)
        elif load_management == AbstractSPMC.ride_hail_rudimentary_spmc_label:
            return RudimentarySPMC("RideHailSPMC", taz_id_str, parking_zone_id_str, events, config_for_spmc)
        else:
            return AbstractSPMC("DoNothingSPMC", taz_id_str, parking_zone_id_str, events, config_for_spmc)

    def log(self, log_message):
        logging.info(self.site_prefix_logging + log_message)

    def run_as_a_thread(self, t, charging_events, updated_ess_soc_from_dss, updated_power_limits_from_derms):
        if self.config_for_spmc.is_multi_threaded:
            self.thread = Thread(target=self.run, args=(t, charging_events, updated_ess_soc_from_dss, updated_power_limits_from_derms))
            self.thread.start()
        else:
            self.run(t, charging_events, updated_ess_soc_from_dss, updated_power_limits_from_derms)

    def get_output_from_latest_run(self):
        if self.thread.is_alive():
            self.thread.join()
        return self.control_commands

    def run(self, t, charging_events, updated_ess_soc_from_dss, updated_power_limits_from_derms):
        self.control_commands = []
        data_for_spmc = DataForSPMC(self.initial_ess_soc, updated_ess_soc_from_dss, updated_power_limits_from_derms)

        for charging_event in charging_events:
            data_for_spmc.vehicle_id.append(str(charging_event['vehicleId']))
            data_for_spmc.vehicle_type.append(str(charging_event['vehicleType']))
            # MJ: What is this? Is it the current energy level of each EV? Or battery size?
            data_for_spmc.primary_fuel_level_in_k_wh.append(int(charging_event['primaryFuelLevelInJoules']) / 3600000)
            # MJ: Should be in minutes of day. What is the unit of this?
            data_for_spmc.arrival_time.append(int(charging_event['arrivalTime']))
            # MJ: Should be in minutes of day. What is the unit of this?
            data_for_spmc.desired_departure_time.append(int(charging_event['departureTime']))
            # MJ: I assume that this is remaining energy to be delivered to each EV
            # and updated each time,right?
            data_for_spmc.desired_fuel_level_in_k_wh.append(int(charging_event['desiredFuelLevelInJoules']) / 3600000)
            # MJ: I assume that this is the EV charging power
            data_for_spmc.max_power_in_kw.append(float(charging_event['maxPowerInKW']))
            # Julius @ HL can you please add this to the BEAM output?
            data_for_spmc.battery_capacity_in_k_wh.append(int(charging_event['primaryFuelCapacityInJoule']) / 3600000)
            # total site power
            data_for_spmc.site_power_in_kw = float(charging_event['parkingZonePowerInKW'])

        self.control_commands = self.run_model(t, charging_events, data_for_spmc)

        return self.control_commands


# **********************************************************************************
# **********************************************************************************
# **********************************************************************************
class ControlledSPMC(AbstractSPMC):
    # Myungsoo's advanced SPM Controller
    def __init__(self, name, taz_id, site_id, events, config_for_spmc):
        AbstractSPMC.__init__(self, name, taz_id, site_id, events, config_for_spmc)
        plug_power = self.site_power/self.num_plugs
        self.p_max = [plug_power] * self.num_plugs  # max EV charging power (EVSE power rate)
        p_min = [0] * self.num_plugs  # min EV charging power
        self.spm_c = SPM_Control_Advanced(max_power_evse=self.p_max, min_power_evse=p_min, time_step_mins=15, num_ess=1, ess_size=self.ess_size)
        print2(self.site_prefix_logging + " Initialized!")

    def run_model(self, t, vehicles, data_for_spmc):
        t_dep = [(tt - t) / 60.0 for tt in data_for_spmc.desired_departure_time] # departure time in minute from the current time
        e_req = data_for_spmc.desired_fuel_level_in_k_wh # energy remaining for each EV
        p_max_site = self.site_power
        if data_for_spmc.updated_power_limits_from_derms is not None:
            if data_for_spmc.updated_power_limits_from_derms > p_max_site:
                logging.error("This should not happen: updated_power_limits_from_derms > p_max_site")
            else:
                p_max_site = data_for_spmc.updated_power_limits_from_derms
        p_min_site = 0
        current_ess_soc = data_for_spmc.updated_ess_soc_internal
        if data_for_spmc.updated_ess_soc_from_dss is not None:
            current_ess_soc = data_for_spmc.updated_ess_soc_from_dss
        max_power_evse = data_for_spmc.max_power_in_kw
        min_power_evse = [0] * len(max_power_evse)
        [p_evse_setpoint, p_ess_setpoint, p_evse_opt, e_evse_opt, p_ess_opt, e_ess_opt, delta_t, flag] = \
            self.spm_c.get_evse_setpoint(t_dep, e_req, p_min_site, p_max_site, current_ess_soc, min_power_evse=min_power_evse, max_power_evse=max_power_evse)
        # update ESS SOC internally in case the DSS does not do that
        data_for_spmc.updated_ess_soc_internal = current_ess_soc + p_ess_setpoint * (self.config_for_spmc.time_step / 3600.0) / self.ess_size

        power_commands = p_evse_setpoint
        if flag > 0:
            power_commands = p_evse_opt

        i = 0
        spmc_commands = []
        for vehicle in vehicles:
            spmc_commands = spmc_commands + [{
                'time': str(t),
                'tazId': str(self.taz_id),
                'siteId': str(self.site_id),
                'vehicleId': vehicle['vehicleId'],
                'powerInKW': str(power_commands[i])
            }]
            i = i + 1
        return spmc_commands


class RudimentarySPMC(AbstractSPMC):
    # Myungsoo's rudimentary SPM Controller

    def __init__(self, name, taz_id, site_id, events, config_for_spmc):
        AbstractSPMC.__init__(self, name, taz_id, site_id, events, config_for_spmc)
        self.spm_c = SPM_Control_Rudimentary(time_step_mins=1, max_power_evse=[], min_power_evse=[])
        print2(self.site_prefix_logging + " Initialized!")

    def run_model(self, t, vehicles, data_for_spmc):
        # Myungsoo is SPM Controller (NOT RIDE HAIL DEPOT)
        # 1) SPM Controller takes list(charging_events) (and/or siteId)
        # 2) SPM Controller returns control_commands
        # 2.a) example
        # 3) add control_commands to control_commands_list
        # control_commands_list = control_commands_list + control_commands
        self.spm_c.max_power_evse = data_for_spmc.max_power_in_kw
        self.spm_c.min_power_evse = [0] * len(self.spm_c.max_power_evse)
        pmin_site_in_kw = 0
        pmax_site_in_kw = data_for_spmc.site_power_in_kw
        tdep = [(tt - t) / 60.0 for tt in data_for_spmc.desired_departure_time]
        # self.log("Optimizing EVSE setpoints by the regular SPM Controller")

        [p_evse_setpoint, e_evse_opt, delta_t] = self.spm_c.get_evse_setpoint(
            tdep,
            data_for_spmc.desired_fuel_level_in_k_wh,
            pmin_site_in_kw,
            pmax_site_in_kw
        )
        power_commands = p_evse_setpoint

        i = 0
        spmc_commands = []
        for vehicle in vehicles:
            spmc_commands = spmc_commands + [{
                'time': str(t),
                'tazId': str(self.taz_id),
                'siteId': str(self.site_id),
                'vehicleId': vehicle['vehicleId'],
                'powerInKW': str(power_commands[i])
            }]
            i = i + 1
        return spmc_commands


class RideHailSPMC(AbstractSPMC):
    # Julius Is SPM Controller (IS RIDE HAIL DEPOT)

    def __init__(self, name, taz_id, site_id, events, config_for_spmc):
        AbstractSPMC.__init__(self, name, taz_id, site_id, events, config_for_spmc)
        plug_power = self.site_power/self.num_plugs
        # JULIUS: @HL I initialized my SPM Controller here
        # @ HL can you provide the missing information
        # TODO uncomment
        init_mpc = False
        t_start = int(0)
        timestep_interval = int(self.config_for_spmc.time_step)
        result_directory = self.config_for_spmc.output_directory
        ride_hail_depot_id = self.site_id
        # list of floats in kW for each plug, for first it should be the same maximum power
        # for all -> could set 10 000 kW if message contains data --> list with length of number
        # of plugs from infrastructure file?
        ch_ba_max_power = [plug_power] * self.num_plugs
        # list of strings, could just be a list of empty strings as not further used so far
        ch_ba_parking_zone_id = [self.site_id] * self.num_plugs
        ch_ba_num = len(ch_ba_max_power)  # number of plugs in one depot --> infrastructure file?
        # only needed for MPC
        path_beam_prediction_file = ''  # path to a former run of the same simulation to obtain predictions.
        # the beam result file should be reduced before to only contain the relevant data
        dtype_predictions = {
            'time': 'int64', 'type': 'category', 'vehicle': 'int64', 'parkingTaz': 'category',
            'chargingPointType': 'category', 'primaryFuelLevel': 'float64', 'mode': 'category',
            'currentTourMode': 'category', 'vehicle_type': 'category', 'arrival_time': 'float64',
            'departureTime': 'float64', 'linkTravelTime': 'string', 'primaryFuelType': 'category',
            'parkingZoneId': 'category', 'duration': 'float64'
        }  # dictionary containing the data types in the beam prediction file
        # maximum time up to which we simulate (for predicting in MPC)
        t_max = int(self.config_for_spmc.simulation_duration - self.config_for_spmc.time_step)
        self.depotController = components.GeminiWrapper.ControlWrapper(
            init_mpc, t_start, timestep_interval, result_directory, ride_hail_depot_id, ch_ba_max_power,
            ch_ba_parking_zone_id, ch_ba_num, path_beam_prediction_file, dtype_predictions, t_max)
        print2(self.site_prefix_logging + " Initialized!")

    def run_model(self, t, vehicles, data_for_spmc):
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
        self.depotController.synchronizeVehiclesAtStation(vehicleIdsAtStation=data_for_spmc.vehicle_id, t_act=t)
        #
        # # VEHICLE ARRIVAL
        vehicles_in_depot = []
        for vehicle in self.depotController.ChargingStation.ChBaVehicles:
            vehicles_in_depot.append(vehicle.vehicle_id)
        for vehicle in self.depotController.ChargingStation.Queue:
            vehicles_in_depot.append(vehicle.vehicle_id)
        #
        for i in range(0, len(data_for_spmc.vehicle_id)):
            if data_for_spmc.vehicle_id[i] not in vehicles_in_depot:
                self.depotController.arrival(VehicleId=data_for_spmc.vehicle_id[i],
                                             VehicleType=data_for_spmc.vehicle_type[i],
                                             VehicleArrival=data_for_spmc.arrival_time[i],
                                             VehicleDesEnd=data_for_spmc.desired_departure_time[i],
                                             VehicleEngyInKwh=data_for_spmc.primary_fuel_level_in_k_wh[i],
                                             VehicleDesEngyInKwh=data_for_spmc.desired_fuel_level_in_k_wh[i],
                                             VehicleMaxEngy=data_for_spmc.battery_capacity_in_k_wh[i],
                                             VehicleMaxPower=data_for_spmc.max_power_in_kw[i],
                                             # this doesn't change within one charging session
                                             t_act=int(t))  # Julius @ HL can you provide the actual time
        #
        # # OBTAIN CONTROL COMMANDS
        vehicles, power, release = self.depotController.step(
            timestep=int(self.config_for_spmc.time_step),  # julius @ HL can you provide the timestep,
            t_act=int(t),  # julius @ HL can you provide the actual time)
            GridPowerUpper=1e10,  # Update from DERMS, for the first we turn this off with a big number
            GridPowerLower=-1e10,  # Update from DERMS, for the first we turn this off with a big number
            BtmsEnergy=0,  # Update from PyDSS, for first this is deactivated in components.ChaDepParent
        )
        #

        spmc_commands = []
        for i in range(0, len(vehicles)):
            spmc_commands = spmc_commands + [{
                'time': str(t),
                'tazId': str(self.taz_id),
                'siteId': str(self.site_id),
                'vehicleId': str(vehicles[i]),
                'powerInKW': str(power[i]),
                'release': str(release[i])
            }]
        num_commands = len(spmc_commands)
        self.log(str(num_commands) + " EVSE setpoints from the ride-hail SPM Controller")
        return spmc_commands

