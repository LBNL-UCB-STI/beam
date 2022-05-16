#!/usr/bin/env python
# coding: utf-8

# In[1]:


import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)


# In[2]:


## modes distribution - plans reading

plans = []
paths = [] #["plans1.csv.gz", "plans2.csv.gz"]
for path in paths:
    df = pd.read_csv(path)
    print(f"read df with shape {df.shape}")
    plans.append(df)

# df.head(3)


# In[3]:


## modes distribution 

for plan in plans:
    display(plan['legMode'].value_counts(normalize=True))
    print("\n\n")


# In[ ]:


## sampling of plans - plans reading

path_to_plans = ""
plans_full = pd.read_csv(path_to_plans)
display(plans_full.shape)
plans_full.head(3)


# In[ ]:


## sampling of plans - sampling

percentage = 10

persons_all = plans_full['personId'].unique()
sample_size = int(len(persons_all) / 100.0 * percentage)
persons_selected = set(np.random.choice(persons_all, sample_size))

plans_sampled = plans_full[plans_full['personId'].isin(persons_selected)].copy()
print(f"Sampled {percentage}% of persons. The shape of sampled plans: {plans_sampled.shape}")


# In[ ]:


## sampling of plans - writing to file

path_to_sampled_plans = ""
plans_sampled.to_csv(path_to_sampled_plans, index=False)

