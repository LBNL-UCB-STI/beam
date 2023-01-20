#!/usr/bin/env python
# coding: utf-8

# In[4]:


### simple multiprocessing with pure function

from multiprocessing import Pool, cpu_count


def function_doing_heavy_computations(complexity):
    a_sum = 0
    for i in range(complexity):
        a_list = [x * x * x for x in range(1, complexity)]
        a_sum += sum(a_list) % 97
    
    return a_sum % 97


def start_multiprocessing_calculations(complexity_value, amount_of_tasks):
    values_to_apply_a_func = [complexity_value + x for x in range(amount_of_tasks)]

    number_of_cores_to_use = cpu_count()
    with Pool(processes=number_of_cores_to_use) as pool:
        # the pool.map does not change the order of returned values
        # but calculation of values is not ordered
        results_of_computation = pool.map(function_doing_heavy_computations, values_to_apply_a_func)

    print(f'Computed {len(results_of_computation)} values using {number_of_cores_to_use} cores')
    
# %time function_doing_heavy_computations(1000)
# %time start_multiprocessing_calculations(3000, 100)


# In[2]:


get_ipython().run_line_magic('time', 'function_doing_heavy_computations(1000)')


# In[3]:


get_ipython().run_line_magic('time', 'start_multiprocessing_calculations(3000, 100)')


# In[5]:


### multiprocessing for a DataFrame

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)
pd.set_option('max_colwidth', None)


df = pd.DataFrame(np.random.randint(11111,99999,size=(10000, 4)), columns=list('ABCD'))
print(f"original shape: {df.shape}")
df.head(3)


# In[6]:


def function_to_calculate_column(a_row):
    a = a_row['A']
    b = a_row['B']
    c = a_row['C']
    d = a_row['D']
    
    a_list = [(c * d * x) % 97 for x in range(min(a,b), max(a,b))]
    
    return sum(a_list) % 97


# In[10]:


get_ipython().run_line_magic('time', 'temp = df.head(500).apply(function_to_calculate_column, axis=1)')

len(temp)


# In[ ]:


number_of_cores_to_use = cpu_count()
with Pool(processes=number_of_cores_to_use) as pool:
    # the pool.map does not change the order of returned values
    # but calculation of values is not ordered
    temp2 = pool.map(function_to_calculate_column, df.head(500))


len(temp2)


# In[ ]:




