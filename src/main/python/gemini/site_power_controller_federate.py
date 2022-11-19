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

from site_power_controller_utils import print2
from site_power_controller_utils import create_federate
from site_power_controller_utils import RideHailSPMC
from site_power_controller_utils import DefaultSPMC


def run_spm_federate(cfed, time_bin_in_seconds, simulated_day_in_seconds):
    # enter execution mode
    h.helicsFederateEnterExecutingMode(cfed)
    fed_name = h.helicsFederateGetName(cfed)
    print2(fed_name + " in execution mode")
    subs_charging_events = h.helicsFederateGetInputByIndex(cfed, 0)
    pubs_control = h.helicsFederateGetPublicationByIndex(cfed, 0)
    default_spm_c_dict = {}
    ride_hail_spm_c_dict = {}

    def key_func(k):
        return k['siteId']

    def sync_time(requested_time):
        granted_time = -1
        while granted_time < requested_time:
            granted_time = h.helicsFederateRequestTime(cfed, requested_time)

    def parse_json(message_to_parse):
        try:
            return json.loads(message_to_parse)
        except json.decoder.JSONDecodeError as err:
            error_text = "Message from BEAM is an incorrect JSON, " + str(err)
            logging.error(error_text)
            print(error_text)
            return ""

    # start execution loop
    all_power_commands_list = []
    for t in range(0, simulated_day_in_seconds - time_bin_in_seconds, time_bin_in_seconds):
        sync_time(t)
        power_commands_list = []
        received_message = h.helicsInputGetString(subs_charging_events)
        if t % 1800 == 0:
            print2("Hour " + str(t/3600) + " completed.")
        if bool(str(received_message).strip()):
            charging_events_json = parse_json(received_message)
            if not isinstance(charging_events_json, collections.abc.Sequence):
                logging.error("[time:" + str(t) + "] It was not able to parse JSON message from BEAM: " + received_message)
                pass
            elif len(charging_events_json) > 0:
                for site_id, charging_events in itertools.groupby(charging_events_json, key_func):
                    if site_id not in default_spm_c_dict:
                        default_spm_c_dict[site_id] = DefaultSPMC("DefaultSPMC", site_id)
                    if site_id not in ride_hail_spm_c_dict:
                        ride_hail_spm_c_dict[site_id] = RideHailSPMC("RideHailSPMC", site_id)
                    # Running SPM Controllers
                    filtered_charging_events = list(filter(lambda charging_event: 'vehicleId' in charging_event, charging_events))
                    if len(filtered_charging_events) > 0:
                        if not site_id.lower().startswith('depot'):
                            power_commands_list = power_commands_list + default_spm_c_dict[site_id].run(t, filtered_charging_events)
                        else:
                            power_commands_list = power_commands_list + ride_hail_spm_c_dict[site_id].run(t, filtered_charging_events)
            else:
                logging.error("[time:" + str(t) + "] The JSON message is not valid: " + received_message)
                pass
        else:
            logging.error("[time:" + str(t) + "] SPM Controller received empty message from BEAM!")
            pass

        message_to_send = power_commands_list
        if not message_to_send:
            message_to_send = [{}]
        h.helicsPublicationPublishString(pubs_control, json.dumps(message_to_send, separators=(',', ':')))
        sync_time(t + 1)
        all_power_commands_list = all_power_commands_list + power_commands_list

    control_commands_df = pd.DataFrame(all_power_commands_list)
    control_commands_df.to_csv('out.csv', index=False)

    # close the federate
    h.helicsFederateDisconnect(cfed)
    print2("Federate finalized and now saving and finishing")
    h.helicsFederateFree(cfed)
    h.helicsCloseLibrary()
    # depotController: save results
    # TODO uncomment
    # depotController.save()
    print2("Finished")


###############################################################################

if __name__ == "__main__":
    logging.basicConfig(filename='site_power_controller_federate.log', level=logging.DEBUG, filemode='w')
    print2("Using helics version " + h.helicsGetVersion())
    helics_config = {"coreInitString": f"--federates=1 --broker_address=tcp://127.0.0.1",
                     "coreType": "zmq",
                     "timeDeltaProperty": 1.0,  # smallest discernible interval to this federate
                     "intLogLevel": 1,
                     "federatesPrefix": "BEAM_FED",
                     "federatesPublication": "CHARGING_VEHICLES",
                     "spmFederatesPrefix": "SPM_FED",
                     "spmSubscription": "CHARGING_COMMANDS",
                     "timeStepInSeconds": 60}

    print2("Creating a federate(s) ...")
    main_fed_info = h.helicsCreateFederateInfo()
    # set core type
    h.helicsFederateInfoSetCoreTypeFromString(main_fed_info, helics_config["coreType"])
    # set initialization string
    h.helicsFederateInfoSetCoreInitString(main_fed_info, helics_config["coreInitString"])
    # set message interval
    h.helicsFederateInfoSetTimeProperty(main_fed_info, h.helics_property_time_delta, helics_config["timeDeltaProperty"])
    #
    h.helicsFederateInfoSetIntegerProperty(main_fed_info, h.helics_property_int_log_level, helics_config["intLogLevel"])
    #
    fed = create_federate(helics_config, main_fed_info, "")

    print2("Starting number of thread(s). Each thread is running one federate.")

    time_bin = helics_config["timeStepInSeconds"]
    simulated_day = 60 * 3600  # 60 hours BEAM Day
    # start execution loop
    run_spm_federate(fed, time_bin, simulated_day)
    # for [fed, taz_id] in feds:
    #     thread = Thread(target=run_spm_federate, args=(fed, str(taz_id), time_bin, simulated_day))
    #     thread.start()
