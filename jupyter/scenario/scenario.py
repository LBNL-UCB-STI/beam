#!/usr/bin/env python
# coding: utf-8

# In[1]:


import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)


# In[4]:


## read all households

households_all = pd.read_csv("../../production/newyork/urbansim_v2/13122k-NYC-all-ages/households.csv.gz")
print(households_all.shape)
households_all.head(3)


# In[7]:


## split all households into 3 parts, shuffling before

hh_dfs = np.array_split(households_all.sample(frac=1), 3)
for hh_df in hh_dfs:
    print(f"the shape is {hh_df.shape}")


# In[9]:


## read the rest of scenario

persons_all = pd.read_csv("../../production/newyork/urbansim_v2/13122k-NYC-all-ages/persons.csv.gz")
plans_all = pd.read_csv("../../production/newyork/urbansim_v2/13122k-NYC-all-ages/plans.csv.gz")
blocks_all = pd.read_csv("../../production/newyork/urbansim_v2/13122k-NYC-all-ages/blocks.csv.gz")
print(f"there are {len(persons_all)} rows in persons, {len(plans_all)} rows in plans, {len(blocks_all)} rows in blocks")
      
display(persons_all.head(3))
display(plans_all.head(3))
display(blocks_all.head(3))


# In[10]:


persons_dfs = []
plans_dfs = []
blocks_dfs = []

for (hh, i) in zip(hh_dfs, [1,2,3]):
    selected_hh = set(hh['household_id'])
    persons_df = persons_all[persons_all['household_id'].isin(selected_hh)].copy()
    persons_dfs.append(persons_df)
    
    selected_blocks = set(hh['block_id'])
    blocks_df = blocks_all[blocks_all['block_id'].isin(selected_blocks)].copy()
    blocks_dfs.append(blocks_df)
    
    selected_persons = set(persons_df['person_id'])
    plans_df = plans_all[plans_all['person_id'].isin(selected_persons)].copy()
    plans_dfs.append(plans_df)
    print(f'hh {i} processed')


# In[11]:


paths = ['../../production/newyork/urbansim_v2/13122k-NYC-all-ages-part1', 
         '../../production/newyork/urbansim_v2/13122k-NYC-all-ages-part2',
         '../../production/newyork/urbansim_v2/13122k-NYC-all-ages-part3']

for (persons_df, households_df, blocks_df, plans_df, path) in zip(persons_dfs, hh_dfs, blocks_dfs, plans_dfs, paths):
    print(f"processing {path} ...")
    persons_df.to_csv(f"{path}/persons.csv.gz", index=False)
    households_df.to_csv(f"{path}/households.csv.gz", index=False)
    blocks_df.to_csv(f"{path}/blocks.csv.gz", index=False)
    plans_df.to_csv(f"{path}/plans.csv.gz", index=False)
    
print('done')


# In[ ]:




