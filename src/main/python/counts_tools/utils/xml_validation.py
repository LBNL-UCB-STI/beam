from collections import defaultdict
from xml.etree import cElementTree as CET
import time

import pandas as pd

'''
Methods for parsing output XML files for validation.
'''

def analyze_acts(exp_plans_file, l2c_file, print_inc=10000):
    '''
    Calculate the total and commute VMTs. Breaks commute into direct and indirect H2W groups.
    :param print_inc:
    :param work:
    :param exp_plans_file:
    :param l2c_file: (str) Path to csv file with a map of links to counties.
    :return: (DataFrame) Columns are: 1) activity type, 2) start time, 3) county
    '''
    ##
    # Initialize constants and containers
    ##
    t0 = time.time()
    l2c_df = pd.read_csv(l2c_file)
    l2c_df.dropna(inplace=True)  # Drop any rows missing data
    l2c_df.set_index(l2c_df.LINK_ID, inplace=True)
    # l2c_df.se
    counties = l2c_df.COUNTY.unique()
    # Initialize containers
    act_types = []
    strt_times = []
    end_times = []
    cntys = []

    ##
    # Iterate through file and update containers
    ##
    itree = CET.iterparse(exp_plans_file)
    event, elem = itree.next()
    ticker = 0
    t0 = time.time()
    while elem.tag != 'population':  # Iterate until file is finished
        # Iterate until a complete person is initialized
        while elem.tag != 'person':
            event, elem = itree.next()
        # We have a complete person element. Time to process the person's plan.
        plan = elem.getchildren()[0]
        # Iterate through plan elements, update for each activity
        for pe in plan.getchildren():
            if pe.tag == 'activity':
                # Get the county
                try:
                    # idx = l2c_df.COUNTY[l2c_df.LINK_ID == pe.attrib['link']].index[0]
                    # act_cnty = l2c_df.get_value(idx, 'COUNTY')
                    act_cnty = l2c_df.loc[pe.attrib['link'], 'COUNTY']
                    cntys.append(act_cnty)
                except KeyError:
                    # Activity might have been mapped to one of the links that were filtered outWriter due to not being mapped to
                    # any counties. We can just ignore these rare occurrences.
                    # elem.clear()
                    # event, elem = itree.next()
                    continue
                # Get the start and end time
                if 'start_time' in pe.attrib.keys():
                    strt_times.append(pe.attrib['start_time'])
                else:
                    strt_times.append(float('nan'))
                if 'end_time' in pe.attrib.keys():
                    end_times.append(pe.attrib['end_time'])
                else:
                    end_times.append(float('nan'))
                act_types.append(pe.attrib['type'])
        # Delete the Person so that we don't run outWriter of memory
        elem.clear()
        event, elem = itree.next()
        ticker += 1
        if ticker % print_inc == 0:
            print 'Processed person number: ' + str(ticker)
            print 'Time so far: ' + str(time.time() - t0)
    # Make and return the data frame
    df = pd.DataFrame({'type':act_types, 'county':cntys, 'start_times':strt_times, 'end_times':end_times})
    print "Running time: " + str(time.time() - t0)
    return df





def calc_vmts(exp_plans_file, l2c_file, home='Home', work='Work', print_inc=10000):
    '''
    Calculate the total and commute VMTs. Breaks commute into direct and indirect H2W groups.
    :param print_inc:
    :param work:
    :param exp_plans_file:
    :param l2c_file: (str) Path to csv file with a map of links to counties.
    :return: (3 x DataFrame) Rows are each COUNTY and VMT observed. DataFrames are: Total VMT, Direct H2W
    VMT, and Indirect H2W VMT
    '''

    ##
    # Initialize constants and containers
    ##
    l2c_df = pd.read_csv(l2c_file)
    l2c_df.dropna(inplace=True)  # Drop any rows missing data
    l2c_df.set_index(l2c_df.LINK_ID, inplace=True)
    counties = l2c_df.COUNTY.unique()
    # Initialize containers
    total_vmt = defaultdict(list)
    for cnty in counties:
        total_vmt[cnty]
    direct_vmt = defaultdict(list)
    for cnty in counties:
        direct_vmt[cnty]
    i_direct_vmt = defaultdict(list)
    for cnty in counties:
        i_direct_vmt[cnty]

    ##
    # Iterate through file and update containers
    ##
    itree = CET.iterparse(exp_plans_file)
    event, elem = itree.next()
    ticker = 0
    t0 = time.time()
    while elem.tag != 'population':  # Iterate until file is finished
        try:  # We have one terrible catch-all try/except
            # Iterate until a complete person element is assembled
            while elem.tag != 'person':
                event, elem = itree.next()
            # We have a complete person element. Time to process the person's plan.
            plan = elem.getchildren()[0]
            # Check if plan has more than one element
            if len(plan) < 2:
                elem.clear()
                event, elem = itree.next()
                continue
            # Skip person if first activity is not at home
            # TODO - we shouldn't have to throw away Persons that don't start at home
            if plan.getchildren()[0].attrib['type'] != home:
                elem.clear()
                event, elem = itree.next()
                continue
            home_act = plan.getchildren()[0]
            # Get the home county.
            try:
                # idx = l2c_df.COUNTY[l2c_df.LINK_ID == home_act.attrib['link']].index[0]
                hcnty = l2c_df.loc[home_act.attrib['link'],'COUNTY']
            except IndexError:
                # Activity might have been mapped to one of the links that were filtered outWriter due to not being mapped to
                # any counties. We can just ignore these rare occurrences.
                elem.clear()
                event, elem = itree.next()
                continue
            d_dist = 0  # Distance for direct h2w commutes
            i_dist = 0  # Distance for indirect h2w commutes
            temp_i_dist = 0  # for building up i_dist before we know if a H2W trip occurs at all
            t_vmt = 0  # total vmt for person
            tracking_h2w = True
            # Parse first leg and second activity to check if it is a direct H2W commute
            pelem = plan.getchildren()[1]  # first leg
            last_dist = round(float(pelem.getchildren()[0].attrib['distance']))
            t_vmt += last_dist
            pelem = plan.getchildren()[2]  # second activity
            if pelem.attrib['type'] == work:  # we have a direct h2w
                d_dist += last_dist
                tracking_h2w = False  # Stop tracking H2W commute
            else:
                temp_i_dist += last_dist
            # Parse the rest of the plan.
            for i, pelem in enumerate(plan.getchildren()[3:]):
                if pelem.tag == 'leg':
                    last_dist = round(float(pelem.getchildren()[0].attrib['distance']))
                    t_vmt += last_dist
                    continue
                if pelem.tag == 'activity':
                    if pelem.attrib['type'] == work and tracking_h2w:
                        temp_i_dist += last_dist
                        i_dist = temp_i_dist  # Finalize indirect distance
                        tracking_h2w = False  # Stop tracking H2W commute
            # Update the appropriate containers
            if d_dist:
                direct_vmt[hcnty].append(d_dist)
            elif i_dist:
                i_direct_vmt[hcnty].append(i_dist)
            total_vmt[hcnty].append(t_vmt)
            # Delete the Person so that we don't run outWriter of memory
            elem.clear()
            event, elem = itree.next()
            ticker += 1
            if ticker % print_inc == 0:
                print 'Processed person number: ' + str(ticker)
                print 'Time so far: ' + str(time.time() - t0)
        except:
            elem.clear()
            event, elem = itree.next()
            continue
    # Make and return the dataframes
    total_df = defaultdict_2_dataframe(total_vmt, 'COUNTY', 'DIST')
    direct_df = defaultdict_2_dataframe(direct_vmt, 'COUNTY', 'DIST')
    i_direct_df = defaultdict_2_dataframe(i_direct_vmt, 'COUNTY', 'DIST')
    return (total_df, direct_df, i_direct_df)


##
# Helpers
##

def defaultdict_2_dataframe(dd, key_col, val_col):
    '''
    Converts a default dict to a dataframe with one row containing keys and the other values.
    :param val_col:
    :param key_col:
    :param dd:
    :return:
    '''
    keys = []
    vals = []
    for k in dd.keys():
        keys += [k]*len(dd[k])
        vals += dd[k]
    return pd.DataFrame({key_col: keys, val_col: vals})










