import pandas as pd
import re
from collections import defaultdict


def find_num_drive_to_transit_agents(df):
    """
    Finds people who drive to transit according to the following algorithm:

    1.  During ModeChoice event, at time t, person i chooses mode='transit'
    2.  During PathTraversal event, at time t'>t, Person i completes
        PathTraversal w/ mode='car'
    3.  Increment total and reset times to 0 for Person i

    NOTE: Will not be able to tell between agents of different housholds

    Args:
            df: A DataFrame containing PathTraversal and ModeChoice events
            See: BEAM event defs)
            transit_modes: Modes to be considered as transit

    Returns:
            int: total number of multimodal commuters
    """
    pat = re.compile('([\d]+)')
    total = 0
    dtt_dict = defaultdict(int)
    for idx, row in df.iterrows():
        # instance of person i choosing transit;
        if row['type'] == 'ModeChoice' and row['mode'] == 'transit':
            # We can only match the vehicle id w/ the first part of person id
            person_id = int(pat.match(row['person']).group())
            dtt_dict[person_id] = int(row['time'])
        # Check if PathTraversal and mode is car (will implicitly pass match
        # check)
        elif row['type'] == 'PathTraversal':
            pid_from_vid = pat.match(row['vehicle'])
            # check if vehicle_id is in our dict already
            if pid_from_vid is not None:
                pid_match = int(pid_from_vid.group())
                # if has earlier entry:
                if pid_match in dtt_dict and (dtt_dict[pid_match] != 0 and int(row['time']) > dtt_dict[pid_match]):
                    total += 1
                    dtt_dict[pid_match] = 0
    return total


if __name__ == '__main__':
    # Very basic use case w/ a few datasets...
    data = {}
    data['ridehail price low'] = find_num_drive_to_transit_agents(pd.read_csv(
        "/Users/sfeygin/remote_files/BEAM_OUTPUT/\
        sfBay_ridehail_price_low_2017-09-27_08-19-54/ITERS/it.0/\
        0.events.csv.gz"))
    print "done ridehail price low"
    data['ridehail price high'] = find_num_drive_to_transit_agents(pd.read_csv(
        "/Users/sfeygin/remote_files/BEAM_OUTPUT/\
        sfBay_ridehail_price_high_2017-09-27_05-05-15/ITERS/it.0/\
    0.events.csv.gz"))
    print "done ridehail price high"
    data['baseline'] = find_num_drive_to_transit_agents(pd.read_csv(
        "/Users/sfeygin/remote_files/BEAM_OUTPUT/\
        base_2017-09-27_05-05-07/ITERS/it.0/\
        0.events.csv.gz"))
    print "baseline"
