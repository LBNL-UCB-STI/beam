#!/usr/bin/env python
# coding: utf-8

# In[ ]:


### simple multiprocessing with Pool

from multiprocessing import Pool, cpu_count


def function_doing_heavy_computations(complexity, number_of_repetitions=1):
    for _ in range(number_of_repetitions):
        a_sum = 0
        for i in range(complexity):
            a_list = [x * x * x for x in range(1, complexity)]
            a_sum += sum(a_list) % 97
    
    return a_sum % 97


def start_multiprocessing_calculations(complexity_value, number_of_tasks):
    values_to_apply_a_func = [complexity_value] * number_of_tasks

    number_of_cores_to_use = cpu_count()
    with Pool(processes=number_of_cores_to_use) as pool:
        # the pool.map does not change the order of returned values
        # but calculation of values is not ordered
        results_of_computation = pool.map(function_doing_heavy_computations, values_to_apply_a_func)

    return results_of_computation
    
# test example: how long one computation takes on one core
print("Calculation with a single core..")
get_ipython().run_line_magic('time', 'function_doing_heavy_computations(3000, number_of_repetitions=10)')
print()

# test example: how long multiple computations take on multiple cores
print("Calculation with multiple cores..")
get_ipython().run_line_magic('time', 'start_multiprocessing_calculations(3000, number_of_tasks=10)')
print()


# In[ ]:


### multiprocessing for a DataFrame

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
from multiprocessing import Pool, cpu_count


pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)
pd.set_option('max_colwidth', None)


# a function to do some heavy computation
def calculate_columns(a_row):
    two_values = (a_row['A'], a_row['B'])
    a_list = [(a_row['C'] * a_row['D'] * x) % 163 for x in range(min(two_values), max(two_values))]
    value_1 = sum(a_list) % 181
    value_2 = sum(a_list) % 199
    return (value_1, value_2)


def add_columns_to_data_frame(data_frame):
    data_frame[['E', 'F']] = data_frame.apply(calculate_columns, axis=1, result_type="expand")
    return data_frame
    
    
# please, note: Numpy array_split creates a copy of an input DataFrame
def process_data_frame_in_parallel(whole_data_frame):
    number_of_cores_to_use = cpu_count()
    # create a copy of input DataFrame split into pieces
    split_data = np.array_split(whole_data_frame, number_of_cores_to_use)
    
    with Pool(processes=number_of_cores_to_use) as pool:
        # the pool.map does not change the order of returned values
        # but calculation of values is not ordered
        processed_data_frame_parts = pool.map(add_columns_to_data_frame, split_data)

    data_frame_processed = pd.concat(processed_data_frame_parts)
    return data_frame_processed



# a data frame with random values
# the bigger the df, the bigger the difference between single thread and multiple
df = pd.DataFrame(np.random.randint(11111,99999,size=(200, 4)), columns=list('ABCD'))
print(f"DataFrame contains: {len(df)} rows")
display(df.head(2))


# test example: how long it takes to calculate the additional column with one core
print("Calculating columns with one core..")
get_ipython().run_line_magic('time', 'df[[\'E\', \'F\']] = df.apply(calculate_columns, axis=1, result_type="expand")')
print()

# test example: a multithreading approach to calculate a column
print("Calculating columns with multiple cores..")
get_ipython().run_line_magic('time', 'copy_of_df = process_data_frame_in_parallel(df)')
print()

if df.equals(copy_of_df):
    print("Resulting DataFrames are equal.")


# In[ ]:





# In[ ]:




