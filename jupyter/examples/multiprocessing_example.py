#!/usr/bin/env python
# coding: utf-8

# In[26]:


import time
from timeit import default_timer as timer
from multiprocessing import Pool, cpu_count


def function_doing_heavy_computations(complexity):
    a_sum = 0
    for i in range(complexity):
        a_list = [x * x * x for x in range(1, complexity)]
        a_sum += sum(a_list) / complexity
    
    return a_sum


def start_multiprocessing_calculations(complexity_value, amount_of_tasks):
    start = timer()
    print(f'starting computations on {cpu_count()} cores')

    values_to_apply_a_func = [complexity_value + x for x in range(1, amount_of_tasks)]

    with Pool() as pool:
        results_of_computation = pool.map(function_doing_heavy_computations, values_to_apply_a_func)

    end = timer()
    print(f'elapsed time: {end - start}')
    print(f'results len: {len(results_of_computation)}')


# In[22]:


### an example of a call of a function

get_ipython().run_line_magic('time', 'function_doing_heavy_computations(1000)')


# In[27]:


start_multiprocessing_calculations(3000, 100)


# In[ ]:




