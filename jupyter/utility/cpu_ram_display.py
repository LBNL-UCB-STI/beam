#!/usr/bin/env python
# coding: utf-8

# In[1]:


import matplotlib.pyplot as plt
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)


# In[2]:


log_url = "https://beam-outputs.s3.amazonaws.com/output/sf-light/sf-light-1k-xml-warmstart__2022-07-05_09-52-48_gsw/cpu_ram_usage.csv.gz"
cpu_ram_log = pd.read_csv(log_url)
print(cpu_ram_log.shape)
cpu_ram_log.head()


# In[27]:


fig, ax1 = plt.subplots(figsize=(20,7))

ax1.set_xlabel('time')

ax1.set_ylabel('CPU usage [%]', color='blue')
cpu_ram_log.plot(x="time", y="CPU usage", label="CPU usage", color='blue', ax=ax1)

ax2 = ax1.twinx()
ax2.set_ylabel('RAM usage [Gb]', color='red')
cpu_ram_log.plot(x="time", y="RAM used", label="RAM used", color='red', ax=ax2)
cpu_ram_log.plot(x="time", y="RAM available", label="RAM available", color='green', ax=ax2)


ax1.legend().remove()
ax2.legend().remove()
fig.legend(loc="upper right", bbox_to_anchor=(0.95,0.95))


# In[ ]:




