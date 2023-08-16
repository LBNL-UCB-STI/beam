import pandas as pd
import os
import sys


full_filename = os.path.expanduser('~/Workspace/Data/GEMINI/2022-07-05/_models/2010-718k-oakland-4k/plans.csv.gz')
full_filename2 = os.path.expanduser('~/Workspace/Data/GEMINI/2022-07-05/_models/2010-718k-oakland-4k/households.csv.gz')
full_filename3 = os.path.expanduser('~/Workspace/Data/GEMINI/2022-07-05/_models/2010-718k-oakland-4k/households.7Advanced.csv.gz')
full_filename4 = os.path.expanduser('~/Workspace/Data/GEMINI/2022-07-05/_models/2010-718k-oakland-4k/households.Base.csv.gz')



dirname = os.path.dirname(full_filename)
basename = os.path.basename(full_filename)
compression = None
if basename.endswith(".gz"):
    compression = 'gzip'

data = pd.read_csv(full_filename, sep=",", index_col=None, header=0, compression=compression)
data2 = pd.read_csv(full_filename2, sep=",", index_col=None, header=0, compression=compression)
data3 = pd.read_csv(full_filename3, sep=",", index_col=None, header=0, compression=compression)
data4 = pd.read_csv(full_filename4, sep=",", index_col=None, header=0, compression=compression)
data3['cars'].sum()
data2['cars'].sum()
data4['hh_cars'].sum()