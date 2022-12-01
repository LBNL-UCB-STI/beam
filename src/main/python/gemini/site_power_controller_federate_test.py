# this file creates an intermediate federate
# it maps the coordinates from TEMPO to the nearest charging
# station modeled in PyDSS
import collections.abc
import helics as h
import logging
import pandas as pd
import statistics
import sys
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


lock_for_requests_count = Lock()
federate_to_site_to_requests_count = {}


def prepare_the_answer(federate_id, request_as_list_of_maps):
    response_list = []
    for request_map in request_as_list_of_maps:
        taz_id = request_map.get("tazId", None)
        site_id = request_map.get("siteId", None)
        vehicle_id = request_map.get("vehicleId", None)

        number_of_power_requests = 0
        number_of_empty_requests = 0

        def update_number_of_requests_in_map(requests_map):
            power_req_count = requests_map.get("power", 0)
            empty_req_count = requests_map.get("empty", 0)
            requests_map["power"] = power_req_count + number_of_power_requests
            requests_map["empty"] = empty_req_count + number_of_empty_requests

        if taz_id and site_id and vehicle_id:
            response = {
                "tazId": taz_id,
                "vehicleId": vehicle_id,
                "powerInKW": 424242424242
            }
            response_list.append(response)
            number_of_power_requests += 1

        elif taz_id and site_id:
            number_of_empty_requests += 1

        else:
            print_and_log(f"A wrong request without tazId and siteId: {request_map}")

        with lock_for_requests_count:
            fed_statistic = federate_to_site_to_requests_count.get(federate_id, {})
            number_of_requests = fed_statistic.get(site_id, {})
            update_number_of_requests_in_map(number_of_requests)
            fed_statistic[site_id] = number_of_requests
            federate_to_site_to_requests_count[federate_id] = fed_statistic

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

    number_of_requests_processed = 0

    print_and_log(f"FEDERATE{federate_id} {h.helicsFederateGetName(cfed)} in execution mode")
    # start execution loop
    for t in range(0, simulated_day_in_seconds - time_bin_in_seconds, time_bin_in_seconds):
        sync_time(cfed, t)
        received_message = h.helicsInputGetString(subs_charging_events)

        message_to_send = None
        if bool(str(received_message).strip()):
            json_message = parse_json(received_message)
            if isinstance(json_message, collections.abc.Sequence):
                message_to_send = prepare_the_answer(federate_id, json_message)
                number_of_requests_processed += 1
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
    print_and_log(f"FEDERATE{federate_id} Finished. {number_of_requests_processed} requests processed.")


###############################################################################

if __name__ == "__main__":
    infrastructure_file = "../../../../production/sfbay/parking/sfbay_taz_unlimited_charging_point.csv"
    number_of_federates = 1
    if len(sys.argv) > 1:
        number_of_federates = int(sys.argv[1])

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

    total_number_of_empty_requests = 0
    total_number_of_power_requests = 0

    site_id_to_empty_requests = {}
    site_id_to_power_requests = {}

    for federate_id in federate_to_site_to_requests_count:
        site_to_statistic = federate_to_site_to_requests_count.get(federate_id)

        fed_power_req_count = 0
        fed_empty_req_count = 0
        for site_id in site_to_statistic:
            site_statistic = site_to_statistic.get(site_id)
            site_power_req_count = site_statistic.get("power", 0)
            site_empty_req_count = site_statistic.get("empty", 0)

            total_number_of_power_requests += site_power_req_count
            total_number_of_empty_requests += site_empty_req_count

            fed_power_req_count += site_power_req_count
            fed_empty_req_count += site_empty_req_count

            grouped_power_req_count = site_id_to_power_requests.get(site_id, 0)
            grouped_empty_req_count = site_id_to_empty_requests.get(site_id, 0)
            site_id_to_power_requests[site_id] = grouped_power_req_count + site_power_req_count
            site_id_to_empty_requests[site_id] = grouped_empty_req_count + site_empty_req_count

        print_and_log(
            f"Federate {federate_id} had {fed_power_req_count} power and {fed_empty_req_count} empty requests")


    def get_statistic(a_map):
        all_vals = a_map.values()
        min_val = min(all_vals)
        max_val = max(all_vals)
        avg_val = statistics.mean(all_vals)
        return f"min value: {min_val}, max value: {max_val}, average value: {avg_val}"


    print_and_log(f"Empty requests per siteId statistic: {get_statistic(site_id_to_empty_requests)}")
    print_and_log(f"Power requests per siteId statistic: {get_statistic(site_id_to_power_requests)}")

    print_and_log(f"Total number of power requests: {total_number_of_power_requests}")
    print_and_log(f"Total number of empty requests: {total_number_of_empty_requests}")
