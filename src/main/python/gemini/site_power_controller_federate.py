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


def run_spm_federate(cfed, taz_id, time_bin_in_seconds, simulated_day_in_seconds):
    # enter execution mode
    h.helicsFederateEnterExecutingMode(cfed)
    fed_name = h.helicsFederateGetName(cfed)
    taz_prefix = "[TAZ:" + taz_id + "]. "
    print2(taz_prefix + fed_name + " in execution mode")
    subs_charging_events = h.helicsFederateGetInputByIndex(cfed, 0)
    pubs_control = h.helicsFederateGetPublicationByIndex(cfed, 0)
    print2(taz_prefix + "Initializing the SPMCs...")
    # SPM Controller INITIALIZE HERE
    default_spm_c = DefaultSPMC("DefaultSPMC", taz_id, site_id)
    ride_hail_spm_c = RideHailSPMC("DefaultSPMC", taz_id, site_id)

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

                    # Running SPMC controllers
                    if not siteId.lower().startswith('depot'):
                        # Myungsoo is SPMC (NOT RIDE HAIL DEPOT)
                        control_commands_list = control_commands_list + default_spm_c.run(t, charging_events_list)
                    else:
                        # Julius Is SPMC (IS RIDE HAIL DEPOT)
                        control_commands_list = control_commands_list + ride_hail_spm_c.run(t, charging_events_list)
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


###############################################################################

if __name__ == "__main__":
    logging.basicConfig(filename='site_power_controller_federate.log', level=logging.DEBUG, filemode='w')
    print2("Using helics version " + h.helicsGetVersion())
    infrastructure_file = "../../../../production/sfbay/parking/sfbay_taz_unlimited_charging_point.csv"
    print2("Loading infrastructure file: " + infrastructure_file)
    data = pd.read_csv(infrastructure_file)
    all_taz = data["taz"].unique()
    # all_taz = range(920, 930)
    # all_taz = ["1"]
    # all_taz = ["1","2","3","4","5"]
    # all_taz = data["taz"].unique()
    # all_taz = list(map(lambda x: str(x), range(1, 6)))
    num_all_taz = len(all_taz)
    logging.info("Extracted " + str(num_all_taz) + " TAZs...")
    helics_config = {"coreInitString": f"--federates={num_all_taz} --broker_address=tcp://127.0.0.1",
                     "coreType": "zmq",
                     "timeDeltaProperty": 1.0,  # smallest discernible interval to this federate
                     "intLogLevel": 1,
                     "federatesPrefix": "BEAM_FED",
                     "federatesPublication": "CHARGING_VEHICLES",
                     "spmFederatesPrefix": "SPM_FED",
                     "spmSubscription": "CHARGING_COMMANDS",
                     "timeStepInSeconds": 60}

    print2("Creating a federate per TAZ...")
    main_fed_info = h.helicsCreateFederateInfo()
    # set core type
    h.helicsFederateInfoSetCoreTypeFromString(main_fed_info, helics_config["coreType"])
    # set initialization string
    h.helicsFederateInfoSetCoreInitString(main_fed_info, helics_config["coreInitString"])
    # set message interval
    h.helicsFederateInfoSetTimeProperty(main_fed_info, h.helics_property_time_delta, helics_config["timeDeltaProperty"])
    #
    h.helicsFederateInfoSetIntegerProperty(main_fed_info, h.helics_property_int_log_level, helics_config["intLogLevel"])

    fed = create_federate(helics_config, main_fed_info, "")

    print2("Starting number of thread(s). Each thread is running one federate.")

    time_bin = helics_config["timeStepInSeconds"]
    simulated_day = 60 * 3600  # 60 hours BEAM Day
    # start execution loop
    for [fed, taz_id] in feds:
        thread = Thread(target=run_spm_federate, args=(fed, str(taz_id), time_bin, simulated_day))
        thread.start()
