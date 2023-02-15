# this file creates an intermediate federate
# it maps the coordinates from TEMPO to the nearest charging
# station modeled in PyDSS
import collections.abc
import json
import logging

import helics as h


def run_beam_to_pydss_federate(helics_conf):
    fed_info = h.helicsCreateFederateInfo()

    # set the name
    h.helicsFederateInfoSetCoreName(fed_info, "beam_to_pydss_federate")

    # set core type
    h.helicsFederateInfoSetCoreTypeFromString(fed_info, helics_config["coreType"])

    # set initialization string
    h.helicsFederateInfoSetCoreInitString(fed_info, helics_config["coreInitString"])

    # set message interval
    h.helicsFederateInfoSetTimeProperty(fed_info, h.helics_property_time_delta, helics_config["timeDeltaProperty"])

    federate_id = "0"
    # create federate
    fed_name = helics_conf["spmFederatePrefix"] + federate_id
    cfed = h.helicsCreateCombinationFederate(fed_name, fed_info)
    logging.info("beam_to_pydss_federate created")

    print("Register a publication of control signals")

    # Register a publication of control signals
    pub = helics_conf["spmFederateSubscription"]
    pubs_control = h.helicsFederateRegisterTypePublication(cfed, pub, "string", "")
    logging.info("publications registered")

    # register subscriptions
    # subscribe to information from TEMPO such that you can map to PyDSS modeled charging stations
    sub = helics_conf["beamFederatePrefix"] + federate_id + "/" + helics_conf["beamFederatePublication"]
    subs_charger_loads = h.helicsFederateRegisterSubscription(cfed, sub, "string")
    logging.info("subscriptions registered")

    # enter execution mode
    h.helicsFederateEnterExecutingMode(cfed)
    logging.info("beam_to_pydss_federate in execution mode")

    print("entered execution mode")

    def sync_time(requested_time):
        granted_time = -1
        while granted_time < requested_time:
            granted_time = h.helicsFederateRequestTime(cfed, requested_time)

    def parse_json(message_to_parse):
        try:
            return json.loads(message_to_parse)
        except json.decoder.JSONDecodeError as err:
            logging.error("Message from BEAM is an incorrect JSON, " + str(err))
            return ""

    time_bin = helics_config["timeStepInSeconds"]
    simulated_day = 60 * 3600  # 60 hours BEAM Day
    # start execution loop
    for t in range(0, simulated_day - time_bin, time_bin):
        sync_time(t)
        print("charger loads received at current_time: " + str(t) + " seconds")
        logging.info("charger loads received at current_time: " + str(t) + " seconds")
        received_message = h.helicsInputGetString(subs_charger_loads)
        charger_load_json = parse_json(received_message)
        all_stations_with_control = []

        if not isinstance(charger_load_json, collections.abc.Sequence):
            logging.error(f"[time:{str(t)}] It was not able to parse JSON message from BEAM: " + received_message)
            pass
        else:
            logging.info('Logging this as CSV')
            logging.info('stationId,estimatedLoad,currentTime')
            for station in charger_load_json:
                reserved_for = station.get('reservedFor', "")
                parking_zone_id = station.get('parkingZoneId', "")
                station_load = station.get('estimatedLoad', "")
                logging.info(str(parking_zone_id) + ',' + str(station_load) + ',' + str(reserved_for) + ',' + str(t))

            # This section should be un-commented and debugged when we have a controller signal to send to BEAM
            # format appropriately here
            #
            # Let's uncomment this and send dummy control signal to BEAM
            # send updated signal to BEAM
            for station in charger_load_json:
                station_with_control = {
                    'parkingZoneId': str(station.get('parkingZoneId', "")),
                    'reservedFor': str(station.get('reservedFor', "")),
                    'power_limit_upper': station.get('estimatedLoad', ""),
                    'power_limit_lower': station.get('estimatedLoad', ""),
                    'lmp_with_control_signal': 0
                }
                all_stations_with_control.append(station_with_control)

        message_to_send = json.dumps(all_stations_with_control, separators=(',', ':'))
        h.helicsPublicationPublishString(pubs_control, message_to_send)
        sync_time(t + 1)

    # close federate
    # h.helicsFederateFinalize(cfed)
    h.helicsFederateDisconnect(cfed)
    logging.warning("beam_to_pydss_federate finalized")

    h.helicsFederateFree(cfed)
    h.helicsCloseLibrary()


###############################################################################

if __name__ == "__main__":
    logging.basicConfig(filename='beam_to_pydss_federate.log', level=logging.DEBUG, filemode='w')

    helics_config = {"coreInitString": f"--federates=1 --broker_address=tcp://127.0.0.1",
                     "coreType": "zmq",
                     "timeDeltaProperty": 1.0,  # smallest discernible interval to this federate
                     "intLogLevel": 1,
                     "beamFederatePrefix": "BEAM_FED",
                     "beamFederatePublication": "CHARGING_VEHICLES",
                     "spmFederatePrefix": "SPM_FED",
                     "spmFederateSubscription": "CHARGING_COMMANDS",
                     "timeStepInSeconds": 60
                     }
    logging.info("stations_list_loaded")
    run_beam_to_pydss_federate(helics_config)
