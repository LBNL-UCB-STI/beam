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


plans = []
paths = [] #["plans1.csv.gz", "plans2.csv.gz"]
for path in paths:
    df = pd.read_csv(path)
    print(f"read df with shape {df.shape}")
    plans.append(df)

# df.head(3)


# In[3]:


for plan in plans:
    display(plan['legMode'].value_counts(normalize=True))
    print("\n\n")


# In[ ]:




