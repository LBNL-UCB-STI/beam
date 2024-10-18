#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Created on Thu Feb 22 10:32:34 2024

@author: xiaodanxu
"""

import pandas as pd
import os
import numpy as np
from pandas import read_csv

analysis_year = [2018]
emission_rate_main = read_csv('Input/PL_MTC_2018_Annual_emission_rate_agg_60deg.csv')

NH3_emission_rate = read_csv('Input/EMFAC_2018_NH3_rate_agg.csv')

speed_list = list(range(5, 91, 5))
speed_bins = {'Speed': speed_list}

region = NH3_emission_rate.Region.unique()[0]
speed_bins_df = pd.DataFrame.from_dict(speed_bins)
speed_bins_df.loc[:, 'Region'] = region

# <codecell>


print(len(NH3_emission_rate))
NH3_emission_rate = NH3_emission_rate.loc[NH3_emission_rate['Total VMT'] > 0]
# generate NH3 emission rate by year
for yr in analysis_year: # loop through calendar year
    print('generate emission rate for year=' + str(yr))
    emission_rate_main_by_year = \
        emission_rate_main.loc[emission_rate_main['calendar_year'] == yr]
    print('size of input emission rates (no NH3)')
    print(len(emission_rate_main_by_year))
    NH3_emission_rate_by_year = \
            NH3_emission_rate.loc[NH3_emission_rate['Calendar Year'] == yr]
    print('size of input emission rates (NH3)')
    print(len(NH3_emission_rate_by_year))
    vehicle_types = NH3_emission_rate_by_year['Vehicle Category'].unique()
    NH3_emission_rate_out = None
    for vt in vehicle_types:
        NH3_emission_rate_sel = \
            NH3_emission_rate_by_year.loc[NH3_emission_rate_by_year['Vehicle Category'] == vt]
        fuel_types = NH3_emission_rate_sel['Fuel'].unique()
        for ft in fuel_types:
            NH3_emission_rate_by_fuel = \
                NH3_emission_rate_sel.loc[NH3_emission_rate_sel['Fuel'] == ft]
            # model_years = NH3_emission_rate_by_fuel['Model Year'].unique()
            # for my in model_years:
                # NH3_emission_rate_by_my = \
                #     NH3_emission_rate_by_fuel.loc[NH3_emission_rate_by_fuel['Model Year'] == my]
            NH3_emission_rate_by_fuel = \
                NH3_emission_rate_by_fuel.loc[NH3_emission_rate_by_fuel['Total VMT'] > 0] # if VMT is 0, the emission rate would be zero
            
            # insert rows for missing speed
            # new_index = pd.Index(speed_list, name="Speed")
            speed_bins_df.loc[:, 'Vehicle Category'] = vt
            speed_bins_df.loc[:, 'Fuel'] = ft
            # speed_bins_df.loc[:, 'Model Year'] = my
            merger_attr = ['Vehicle Category', 'Fuel', 'Region', 'Speed']
            NH3_emission_rate_imp = pd.merge(NH3_emission_rate_by_fuel, 
                                             speed_bins_df,
                                              on = merger_attr, how = 'right')
            NH3_emission_rate_imp.loc[:,'Calendar Year'].fillna(yr, inplace = True)
            NH3_emission_rate_imp.loc[:,'Model Year'].fillna('Aggregate', inplace = True)
            NH3_emission_rate_imp["NH3_RUNEX"].fillna(method='ffill', inplace = True) # forward fill for smaller values
            NH3_emission_rate_imp["NH3_RUNEX"].fillna(method='bfill', inplace = True) # backward fill for larger values
            NH3_emission_rate_out = pd.concat([NH3_emission_rate_out,
                                              NH3_emission_rate_imp])
        # break
    print('size of imputed emission rates (NH3)')
    print(len(NH3_emission_rate_out))
    
    NH3_emission_rate_out = NH3_emission_rate_out.drop(columns = ['Region', 'Model Year', 'Total VMT'])
    NH3_emission_rate_out = \
        NH3_emission_rate_out.rename(columns = {'Calendar Year':'calendar_year', 
                                                'Vehicle Category': 'vehicle_class',
                                                'Fuel': 'fuel',
                                                'NH3_RUNEX': 'emission_rate',
                                                 'Speed': 'speed_time'})
    # <codecell>
    
    fuel_mapping = {'Diesel': 'Dsl',
                    'Natural Gas': 'NG', 
                    'Gasoline': 'Gas',
                    'Electricity': 'Elec',
                    'Plug-in Hybrid': 'Phe'}
    
    NH3_emission_rate_out.loc[:, 'fuel'] = \
        NH3_emission_rate_out.loc[:, 'fuel'].map(fuel_mapping)
    
    # <codecell>
    sub_areas = emission_rate_main_by_year.sub_area.unique()
    merger_var = ['calendar_year', 'vehicle_class', 'fuel', 'speed_time']
    NH3_emission_rate_formatted = None
    for area in sub_areas:
        NH3_rate_template = \
            emission_rate_main_by_year.loc[emission_rate_main_by_year['sub_area'] == area]
        NH3_rate_template = \
            NH3_rate_template.loc[NH3_rate_template['pollutant'] == 'SOx']
        NH3_rate_template = \
            NH3_rate_template.loc[NH3_rate_template['process'] == 'RUNEX']
        NH3_rate_template.loc[:, 'pollutant'] = 'NH3'
        NH3_rate_template = NH3_rate_template.drop(columns = 'emission_rate')
    
        NH3_emission_rate_by_county = pd.merge(NH3_emission_rate_out,
                                               NH3_rate_template,
                                               on = merger_var, 
                                               how = 'left')
        NH3_emission_rate_by_county.fillna(method='ffill', inplace = True)
        NH3_emission_rate_formatted = pd.concat([NH3_emission_rate_formatted,
                                                 NH3_emission_rate_by_county])
        # break
    emission_rate_out = pd.concat([emission_rate_main_by_year, 
                                    NH3_emission_rate_formatted])  
    print('size of imputed emission rates:')
    print(emission_rate_out.shape)
    emission_rate_out.to_csv('Output/imputed_MTC_emission_rate_agg_NH3_added_' + str(yr) + '_v2.csv', index = False)
    break