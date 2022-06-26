#!/usr/bin/env python
# coding: utf-8

# In[1]:


import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)


# In[21]:


## plans with persons with walk transit legs only - reading

plans = pd.read_csv("../../../beam-production/production/newyork/urbansim_v2/13122k-NYC-all-ages-14k-sample/plans.csv.gz")
plans.head()


# In[23]:


## plans with persons with walk transit legs only - getting persons

all_persons = set(plans['person_id'].unique())
walk_transit_persons = set(plans[plans['trip_mode'] == 'walk_transit']['person_id'].unique())
the_rest_persons = all_persons - walk_transit_persons
f"all: {len(all_persons)}", f"walk transit persons: {len(walk_transit_persons)}", f"the rest: {len(the_rest_persons)}"


# In[25]:


## plans with persons with walk transit legs only - resulting DF

plans_p1 = plans[plans['person_id'].isin(walk_transit_persons)]
plans_p2 = plans[(plans['person_id'].isin(the_rest_persons)) & (plans['ActivityType'] == 'Home')]
plans_p2 = plans_p2.groupby('person_id').first().reset_index().copy()

plans_p2['departure_time'] = -np.inf
print(plans_p1.shape, plans_p2.shape)

plans_joined = pd.concat([plans_p1, plans_p2])
plans_joined


# In[26]:


## plans with persons with walk transit legs only - checking mode split

hists = [plans['trip_mode'].dropna(), plans_joined['trip_mode'].dropna()]
labels = ['original plans', 'walk transit plans']
_, ax = plt.subplots(figsize=(15,5))
ax.hist(hists, label=labels)
ax.legend()


# In[8]:


columns = ["trip_id","person_id","PlanElementIndex","ActivityElement","trip_mode","ActivityType","x","y","departure_time"]
plans_u = pd.read_csv("../../output/beamville_urbansim_plans.csv.gz")[columns]
plans_u.head(3)


# In[9]:


plans = pd.read_csv("../../test/input/beamville/urbansim_v2/plans.csv.gz")[columns]
plans.head(3)


# In[10]:


plans['trip_mode'].hist()


# In[11]:


plans_u['trip_mode'].hist()


# In[48]:


import pandas as pd
import os

base_folder = "../../output/beamville/"
subfolders = [f.path for f in os.scandir(base_folder) if f.is_dir()]

for beamville_run_full_path in subfolders:
    print(beamville_run_full_path)
    plans_it0 = pd.read_csv(f"{beamville_run_full_path}/ITERS/it.0/0.plans.csv.gz")
    value_counts_series = plans_it0['legMode'].value_counts() # plans_it0[plans_it0['planSelected']==True]['legMode'].value_counts()
    for (i,v) in value_counts_series.items():
        print(i.ljust(25), v)
    print()


# In[47]:


plans_it0.head(3)


# In[39]:





# In[1]:


# either this:
# ! pip install <package>

# or that:
# %pip install <package>


# In[ ]:




