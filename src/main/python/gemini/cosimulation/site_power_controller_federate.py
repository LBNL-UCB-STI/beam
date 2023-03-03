# this file creates an intermediate federate
# it maps the coordinates from TEMPO to the nearest charging
# station modeled in PyDSS
import collections.abc
import helics as h
import itertools
import logging
import sys
import pandas as pd
from threading import Thread
import json
import pathlib

from site_power_controller_utils import ConfigForSPMC
from site_power_controller_utils import RudimentarySPMC
from site_power_controller_utils import AdvancedSPMC
from site_power_controller_utils import RideHailSPMC
from site_power_controller_utils import create_federate
from site_power_controller_utils import print2



class SitePowerManager:
    # in case of multiple federates it is still fine to have these dicts as local variables
    # because each federate will work with a fixed subset of TAZs
    public_spmc_dict = {}
    ride_hail_spmc_dict = {}
    ess_soc_from_dss = {} # It needs to be updated through DSS @TODO NADIA
    power_limits_from_derms = {} # It needs to be updated through DSS @TODO KEITH
    depot_prefix = "depot"
    #spmc: advanced, rudimentary, ridehail
    public_spmc = "advanced"
    ridehail_spmc = "rudimentary"

    def __init__(self, time_step, sim_dur, output_dir, is_multi_threaded):
        self.config_for_spmc = ConfigForSPMC(time_step, sim_dur, output_dir, is_multi_threaded)

    def init_an_spmc(self, taz_id_str, parking_zone_id_str, events):
        if parking_zone_id_str.lower().startswith(self.depot_prefix):
            if parking_zone_id_str not in self.ride_hail_spmc_dict:
                if self.ridehail_spmc == "advanced":
                    self.ride_hail_spmc_dict[parking_zone_id_str] = AdvancedSPMC("RideHailSPMC", taz_id_str,
                                                                              parking_zone_id_str, events,
                                                                              self.config_for_spmc)
                elif self.ridehail_spmc == "rudimentary":
                    self.ride_hail_spmc_dict[parking_zone_id_str] = RudimentarySPMC("RideHailSPMC", taz_id_str,
                                                                                    parking_zone_id_str, events,
                                                                                    self.config_for_spmc)
                else:
                    self.ride_hail_spmc_dict[parking_zone_id_str] = RideHailSPMC("RideHailSPMC", taz_id_str,
                                                                                  parking_zone_id_str, events,
                                                                                  self.config_for_spmc)
        elif parking_zone_id_str not in self.public_spmc_dict:
            if self.public_spmc == "advanced":
                self.public_spmc_dict[parking_zone_id_str] = AdvancedSPMC("PublicSPMC", taz_id_str, parking_zone_id_str, events, self.config_for_spmc)
            else:
                self.public_spmc_dict[parking_zone_id_str] = RudimentarySPMC("PublicSPMC", taz_id_str, parking_zone_id_str, self.config_for_spmc)

    def run_an_spmc(self, current_t, zone_id_str, charging_events):
        ess_soc = self.ess_soc_from_dss.get(zone_id_str)
        power_limits = self.power_limits_from_derms.get(zone_id_str)
        if zone_id_str.lower().startswith(self.depot_prefix):
            self.ride_hail_spmc_dict[zone_id_str].run_as_a_thread(current_t, charging_events, ess_soc, power_limits)
        else:
            self.public_spmc_dict[zone_id_str].run_as_a_thread(current_t, charging_events, ess_soc, power_limits)

    # CONTROL COMMANDS
    def get_power_commands_from_an_spmc(self, zone_id_str):
        if not zone_id_str.lower().startswith(self.depot_prefix):
            return self.public_spmc_dict[zone_id_str].get_output_from_latest_run()
        else:
            return self.ride_hail_spmc_dict[zone_id_str].get_output_from_latest_run()


def run_spm_federate(cfed, spm: SitePowerManager):
    # enter execution mode
    h.helicsFederateEnterExecutingMode(cfed)
    fed_name = h.helicsFederateGetName(cfed)

    def print_inf(any_to_print):
        to_print = f"{fed_name}: {str(any_to_print)}"
        logging.info(to_print)
        print(to_print)

    def print_err(any_to_print):
        to_print = f"{fed_name}: {str(any_to_print)}"
        logging.error(to_print)
        print(to_print)

    print_inf("In execution mode")
    subs_charging_events = h.helicsFederateGetInputByIndex(cfed, 0)
    pubs_control = h.helicsFederateGetPublicationByIndex(cfed, 0)

    def sync_time(requested_time):
        granted_time = -1
        while granted_time < requested_time:
            granted_time = h.helicsFederateRequestTime(cfed, requested_time)

    def parse_json(message_to_parse):
        try:
            return json.loads(message_to_parse)
        except json.decoder.JSONDecodeError as err:
            print_err("Message from BEAM is an incorrect JSON, " + str(err))
            return ""

    config = spm.config_for_spmc
    # start execution loop
    for t in range(0, config.sim_dur - config.time_step, config.time_step):
        sync_time(t)
        power_commands_list = []
        received_message = h.helicsInputGetString(subs_charging_events)
        if bool(str(received_message).strip()):
            charging_events_json = parse_json(received_message)
            if not isinstance(charging_events_json, collections.abc.Sequence):
                print_err(f"[time:{str(t)}] It was not able to parse JSON message from BEAM: " + received_message)
                pass
            elif len(charging_events_json) > 0:
                processed_side_ids = []
                grouped_events = [
                    (k[0], k[1], list(v)) for k, v in itertools.groupby(
                        charging_events_json, key= lambda d: (d['tazId'], d['parkingZoneId'])
                    )
                ]
                # ****** SPMC SECTION ******
                for taz_id, parking_zone_id, charging_events in grouped_events:
                    # INITIALIZING SPMC ONCE, USING THE INITIALIZING EVENTS. THESE EVENTS DO NOT CONTAIN: "vehicleId"
                    # Initializing does not happen at every time step
                    spm.init_an_spmc(taz_id, parking_zone_id, charging_events)

                    # FILTERING OUT CHARGING EVENTS FROM INITIALIZING EVENTS
                    filtered_charging_events = list(filter(lambda charging_event: 'vehicleId' in charging_event, charging_events))

                    # RUNNING SPM CONTROLLERS
                    if len(filtered_charging_events) > 0:
                        processed_side_ids = processed_side_ids + [parking_zone_id]
                        spm.run_an_spmc(t, parking_zone_id, filtered_charging_events)

                # ****** COLLECTING SPMC SECTION ******
                for parking_zone_id in processed_side_ids:
                    power_commands_list = power_commands_list + spm.get_power_commands_from_an_spmc(parking_zone_id)

                # ****** DSS-DERMS SECTION ******
                # TODO Nadia & Keith
                # RUN HELICS CONNECTION TO DSS AND DERMS HERE
                # a- UPDATE updated_ess_soc_from_dss HERE FORE EVERY parking_zone_id
                # b- UPDATE updated_power_limits_from_derms HERE FOR EVERY parking_zone_id
                # For a and b, to keep default value for a certain parking_zone_id, you can remove it from the dict
            else:
                # print_err("[time:" + str(t) + "] The JSON message is empty")
                pass
        else:
            # print_err("[time:" + str(t) + "] SPM Controller received empty message from BEAM!")
            pass
        h.helicsPublicationPublishString(pubs_control, json.dumps(power_commands_list, separators=(',', ':')))
        if len(power_commands_list) > 0:
            pd.DataFrame(power_commands_list).to_csv('out.csv', mode='a', index=False, header=False)
        sync_time(t + 1)
        if t % 1800 == 0:
            print2("Hour " + str(t/3600) + " completed.")

    # close the helics federate
    h.helicsFederateDisconnect(cfed)
    print_inf("Federate finalized and now saving and finishing")
    h.helicsFederateFree(cfed)
    # depotController: save results
    # TODO uncomment
    # depotController.save()
    print_inf("Finished")


###############################################################################

if __name__ == "__main__":
    number_of_federates = 1
    if len(sys.argv) > 1:
        number_of_federates = int(sys.argv[1])

    current_directory = str(pathlib.Path(__file__).parent.resolve())
    log_file = current_directory + "/site_power_controller_federate.log"
    print("Log file will located at: " + log_file)
    logging.basicConfig(filename=log_file, level=logging.DEBUG, filemode='w')
    print2("Using helics version " + h.helicsGetVersion())
    helics_config = {"coreInitString": f"--federates={number_of_federates} --broker_address=tcp://127.0.0.1",
                     "coreType": "zmq",
                     "timeDeltaProperty": 1.0,  # smallest discernible interval to this federate
                     "intLogLevel": 1,
                     "federatesPrefix": "BEAM_FED",
                     "federatesPublication": "CHARGING_VEHICLES",
                     "spmFederatesPrefix": "SPM_FED",
                     "spmSubscription": "CHARGING_COMMANDS",
                     "timeStepInSeconds": 60}

    federate_ids = list(map(lambda x: str(x), range(number_of_federates)))

    print2(f"Creating {number_of_federates} federate(s) ...")
    main_fed_info = h.helicsCreateFederateInfo()
    # set core type
    h.helicsFederateInfoSetCoreTypeFromString(main_fed_info, helics_config["coreType"])
    # set initialization string
    h.helicsFederateInfoSetCoreInitString(main_fed_info, helics_config["coreInitString"])
    # set message interval
    h.helicsFederateInfoSetTimeProperty(main_fed_info, h.helics_property_time_delta, helics_config["timeDeltaProperty"])
    #
    h.helicsFederateInfoSetIntegerProperty(main_fed_info, h.helics_property_int_log_level, helics_config["intLogLevel"])

    feds = [create_federate(helics_config, main_fed_info, fed_id) for fed_id in federate_ids]
    print2("Starting " + str(len(feds)) + " thread(s). Each thread is running one federate.")

    time_bin = helics_config["timeStepInSeconds"]
    simulated_day = 60 * 3600  # 60 hours BEAM Day

    # start execution loop
    threads = []
    is_multi_threaded_run = False
    spm_wrapper = SitePowerManager(time_bin, simulated_day, current_directory, is_multi_threaded_run)
    for fed in feds:
        thread = Thread(target=run_spm_federate, args=(fed, spm_wrapper))
        thread.start()
        threads.append(thread)

    # closing helics after all federates are finished
    for thread in threads:
        thread.join()

    print2("Closing Helics...")
    h.helicsCloseLibrary()
    print2("Finished.")
