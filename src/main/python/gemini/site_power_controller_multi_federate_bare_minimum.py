# this file creates an intermediate federate
# it maps the coordinates from TEMPO to the nearest charging
# station modeled in PyDSS
import collections.abc
import helics as h
import logging
import pandas as pd
from threading import Thread, Lock

import json


def print_and_log(log_message):
    logging.info(log_message)
    print(log_message)


def create_federate(helics_conf, fed_info, federate_id):
    fed_name = helics_conf["spmFederatePrefix"] + federate_id

    # create federate
    cfed = h.helicsCreateCombinationFederate(fed_name, fed_info)
    logging.info("Create combination federate " + fed_name)

    # Register a publication of control signals
    # Power in kW
    pub = helics_conf["spmFederateSubscription"]
    h.helicsFederateRegisterTypePublication(cfed, pub, "string", "")
    logging.info("Registered to publication " + pub)

    # register subscriptions
    # subscribe to information from TEMPO such that you can map to PyDSS modeled charging stations
    # Power in kW and Energy in Joules
    sub = helics_conf["beamFederatePrefix"] + federate_id + "/" + helics_conf["beamFederatePublication"]
    h.helicsFederateRegisterSubscription(cfed, sub, "string")
    logging.info("Registered to subscription " + sub)

    return cfed


lock_for_taz_to_power_requests_count = Lock()
taz_to_power_requests_count = {}

lock_for_taz_empty_requests_count = Lock()
taz_empty_requests_count = {}


def prepare_the_answer(request_as_list_of_maps):
    response_list = []
    for request_map in request_as_list_of_maps:
        tazId = request_map.get("tazId", None)
        vehicleId = request_map.get("vehicleId", None)

        if tazId and vehicleId:
            response = {
                "tazId": tazId,
                "vehicleId": vehicleId,
                "powerInKW": 424242424242
            }
            response_list.append(response)

            with lock_for_taz_to_power_requests_count:
                number_of_requests = taz_to_power_requests_count.get(tazId, 0)
                taz_to_power_requests_count[tazId] = number_of_requests + 1

        elif tazId:
            with lock_for_taz_empty_requests_count:
                number_of_requests = taz_empty_requests_count.get(tazId, 0)
                taz_empty_requests_count[tazId] = number_of_requests + 1

    return response_list


def sync_time(cfed, requested_time):
    while h.helicsFederateRequestTime(cfed, requested_time) < requested_time:
        pass


def parse_json(message_to_parse):
    try:
        charging_events_json = json.loads(message_to_parse)
        return charging_events_json
    except json.decoder.JSONDecodeError as err:
        error_text = f"Message not a JSON. Message: {message_to_parse} Error: {err}"
        logging.error(error_text)
        print_and_log(error_text)
        return ""


def run_spm_federate(cfed, federate_id, time_bin_in_seconds, simulated_day_in_seconds):
    # enter execution mode
    h.helicsFederateEnterExecutingMode(cfed)
    subs_charging_events = h.helicsFederateGetInputByIndex(cfed, 0)
    pubs_control = h.helicsFederateGetPublicationByIndex(cfed, 0)

    print_and_log(f"FEDERATE{federate_id} {h.helicsFederateGetName(cfed)} in execution mode")
    # start execution loop
    for t in range(0, simulated_day_in_seconds - time_bin_in_seconds, time_bin_in_seconds):
        sync_time(cfed, t)
        received_message = h.helicsInputGetString(subs_charging_events)

        message_to_send = None
        if bool(str(received_message).strip()):
            json_message = parse_json(received_message)
            if isinstance(json_message, collections.abc.Sequence):
                message_to_send = prepare_the_answer(json_message)
            else:  # got not a json message or the message was not parsed as a collection
                pass
        else:  # got an empty message
            pass

        to_send = [{"federateId": federate_id}]
        if message_to_send:
            to_send = to_send + message_to_send
        else:  # an answer was not prepared
            pass

        json_to_send = json.dumps(to_send, separators=(',', ':'))
        h.helicsPublicationPublishString(pubs_control, json_to_send)

    # close the federate
    h.helicsFederateDisconnect(cfed)
    h.helicsFederateFree(cfed)
    print_and_log(f"FEDERATE{federate_id} Finished.")


###############################################################################

if __name__ == "__main__":
    infrastructure_file = "../../../../production/sfbay/parking/sfbay_taz_unlimited_charging_point.csv"
    number_of_federates = 1

    logging.basicConfig(filename='site_power_controller_federate.log', level=logging.DEBUG, filemode='w')
    print_and_log("Using helics version " + h.helicsGetVersion())
    print_and_log("Loading infrastructure file: " + infrastructure_file)
    data = pd.read_csv(infrastructure_file)

    federate_ids = list(map(lambda x: str(x), range(number_of_federates)))
    helics_config = {"coreInitString": f"--federates={len(federate_ids)} --broker_address=tcp://127.0.0.1",
                     "coreType": "zmq",
                     "timeDeltaProperty": 1.0,  # smallest discernible interval to this federate
                     "intLogLevel": 1,
                     "beamFederatePrefix": "BEAM_FED",
                     "beamFederatePublication": "CHARGING_VEHICLES",
                     "spmFederatePrefix": "SPM_FED",
                     "spmFederateSubscription": "CHARGING_COMMANDS",
                     "timeStepInSeconds": 60
                     }

    print_and_log(f"Creating {len(federate_ids)} federates ...")

    main_fed_info = h.helicsCreateFederateInfo()
    # set core type
    h.helicsFederateInfoSetCoreTypeFromString(main_fed_info, helics_config["coreType"])
    # set initialization string
    h.helicsFederateInfoSetCoreInitString(main_fed_info, helics_config["coreInitString"])
    # set message interval
    h.helicsFederateInfoSetTimeProperty(main_fed_info, h.helics_property_time_delta, helics_config["timeDeltaProperty"])
    #
    h.helicsFederateInfoSetIntegerProperty(main_fed_info, h.helics_property_int_log_level, helics_config["intLogLevel"])

    feds = [[create_federate(helics_config, main_fed_info, federate_id), federate_id] for federate_id in federate_ids]

    time_bin = helics_config["timeStepInSeconds"]
    simulated_day = 60 * 3600  # 60 hours BEAM Day

    threads = []
    print_and_log(f"Starting {len(feds)} number of thread(s). Each thread is running one federate.")
    for [fed, federate_id] in feds:
        thread = Thread(target=run_spm_federate, args=(fed, federate_id, time_bin, simulated_day))
        thread.start()
        threads.append(thread)

    # closing helics after all federates are finished
    for thread in threads:
        thread.join()

    print_and_log("Closing Helics...")
    h.helicsCloseLibrary()
    print_and_log("Finished.")

    total_number_of_requests = sum(taz_to_power_requests_count.values())
    print_and_log(f"{len(taz_to_power_requests_count)} taz were affected by {total_number_of_requests} power requests.")
    for taz_id in sorted(taz_to_power_requests_count.keys()):
        taz_number_of_requests = taz_to_power_requests_count.get(taz_id)
        print_and_log(f"taz {taz_id} got {taz_number_of_requests} requests.")

    total_number_of_requests = sum(taz_empty_requests_count.values())
    print_and_log(
        f"{len(taz_empty_requests_count)} taz were affected by {total_number_of_requests} requests without a vehicle Id.")
