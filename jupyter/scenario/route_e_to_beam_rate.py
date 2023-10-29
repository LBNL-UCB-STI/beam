#!/usr/bin/env python
# coding: utf-8

# In[3]:


import pandas as pd
import numpy as np
import seaborn as sns
import os

container_beam = os.path.expanduser("~/beam_root")
root = container_beam if os.path.isdir(container_beam) else "../.."
root


# In[8]:


file_name = "Daycab_new_400kW-speed_mph&grade_percent&mass_kg_lookup.csv"
rates = pd.read_csv(f"{root}/data_files/route_e/{file_name}")
# flat_surface = rates[(rates['grade'] == 0)]
# display(flat_surface)
# sns.lineplot(data=flat_surface, x="speed", y="energy_rate")
rates


# In[9]:


def create_range(column, border, initial_border, group):
    border_column = "low_" + column if border == 'low' else "high_" + column
    shft = 1 if border == 'low' else -1
    rates[border_column] = rates.groupby(group)[column].shift(shft)
    rates[border_column] = rates[column] + (rates[border_column] - rates[column]) / 2
    rates[border_column].replace(to_replace=np.nan, value=initial_border, inplace=True)


create_range('speed', "low", 0, ['grade', 'mass_kg'])
create_range('speed', "high", 120, ['grade', 'mass_kg'])
create_range('mass_kg', "low", 10000, ['grade', 'speed'])
create_range('mass_kg', "high", 50000, ['grade', 'speed'])
create_range('grade', "low", -25, ['mass_kg', 'speed'])
create_range('grade', "high", 25, ['mass_kg', 'speed'])
rates[rates['grade'] == 20]


# In[10]:


rates["speed_mph_float_bins"] = rates.apply(lambda row: f"[{row['low_speed']}, {row['high_speed']})", axis=1)
rates["grade_percent_float_bins"] = rates.apply(lambda row: f"[{row['low_grade']}, {row['high_grade']})", axis=1)
rates["mass_kg_float_bins"] = rates.apply(lambda row: f"[{row['low_mass_kg']}, {row['high_mass_kg']})", axis=1)
rates["rate"] = rates['energy_rate'] * 100
rates


# In[11]:


rates[['speed_mph_float_bins', 'grade_percent_float_bins', 'mass_kg_float_bins', 'rate']]\
    .to_csv(f"{root}/test/input/beamville/freight/{file_name}", index=False)

