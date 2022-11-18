# this file creates an intermediate federate
# it maps the coordinates from TEMPO to the nearest charging
# station modeled in PyDSS
import collections.abc
import helics as h
import logging
import pandas as pd
from threading import Thread

import json

def print2(to_print):
    logging.info(to_print)
    print(to_print)


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
        received_message = h.helicsInputGetString(subs_charging_events)
        json_message = '?'

        if bool(str(received_message).strip()):
            json_message = parse_json(received_message)

        message_to_send = [{'tazId': str(taz_id)}] + json_message
        json_message_to_send = json.dumps(message_to_send, separators=(',', ':'))
        h.helicsPublicationPublishString(pubs_control, json_message_to_send)

    # close the federate
    h.helicsFederateDisconnect(cfed)
    print2(taz_prefix + "Federate finalized and now saving and finishing")
    h.helicsFederateFree(cfed)
    h.helicsCloseLibrary()
    print2("Finished")


###############################################################################

if __name__ == "__main__":
    logging.basicConfig(filename='site_power_controller_federate.log', level=logging.DEBUG, filemode='w')
    print2("Using helics version " + h.helicsGetVersion())
    infrastructure_file = "../../../../production/sfbay/parking/sfbay_taz_unlimited_charging_point.csv"
    print2("Loading infrastructure file: " + infrastructure_file)
    data = pd.read_csv(infrastructure_file)

    all_taz = list(map(lambda x: str(x), range(1, 4 + 1)))
    num_all_taz = len(all_taz)
    logging.info("Extracted " + str(num_all_taz) + " TAZs...")
    helics_config = {"coreInitString": f"--federates={num_all_taz} --broker_address=tcp://127.0.0.1",
                     "coreType": "zmq",
                     "timeDeltaProperty": 1.0,  # smallest discernible interval to this federate
                     "intLogLevel": 1,
                     "federatesPrefix": "BEAM_FED_TAZ",
                     "federatesPublication": "CHARGING_VEHICLES",
                     "spmFederatesPrefix": "SPM_FED_TAZ",
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

    feds = [[create_federate(helics_config, main_fed_info, str(taz_id)), taz_id] for taz_id in all_taz]
    print2("Starting " + str(len(feds)) + " number of thread(s). Each thread is running one federate.")

    time_bin = helics_config["timeStepInSeconds"]
    simulated_day = 60 * 3600  # 60 hours BEAM Day
    # start execution loop
    for [fed, taz_id] in feds:
        thread = Thread(target=run_spm_federate, args=(fed, str(taz_id), time_bin, simulated_day))
        thread.start()
