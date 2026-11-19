from config import *
import os, subprocess, glob, shutil

'''
1. Deletes all *.log file in BEAM dir
2. Deletes all urbansim-10k_*.conf or txt files from sf-light folder
3. Deletes all files except 1_*.csv from the storage 
4. Delete BEAM urbansim output directory to clear up space on EC2 before the run
5. export MAXRAM=16g
6. clean firecue.txt
7. clean writecue.txt
8. recreate fetched_files.txt 

'''

for item in os.listdir(beam):
    if item.endswith(".log"): 
        os.remove(os.path.join(beam, item)) # point 1

for filename in glob.glob(sf_light_ip_dir+'/'+conf_base+'_*'):
    os.remove(filename) # point 2

os.chdir(shared)
bashCommand = "find . \! -name '1_*.csv' -a \! -name '*.py' -a \! -type d -delete"
subprocess.Popen(bashCommand, shell=True, executable='/bin/bash') # point 3
os.chdir(search_space)
print('Ready for a fresh SSC run!')

# point 4
files = glob.glob(sf_light_dir)
if not files:
    pass
else:
    for i in range(len(files)):
        if os.path.isfile(files[i]) or os.path.islink(files[i]):
            os.remove(files[i])
        elif os.path.isdir(files[i]):
            shutil.rmtree(files[i]) 

# point 5
os.environ["MAXRAM"] = "16g"

# point 6
if os.path.isfile(f'{beam}/firecue.txt'):
    os.remove(f'{beam}/firecue.txt')

# point 7
if os.path.isfile(f'{beam}/writecue.txt'):
    os.remove(f'{beam}/writecue.txt')

# point 8
fetch_file = f'{search_space}/fetched_files.txt'
if os.path.isfile(fetch_file):
    os.remove(fetch_file)
with open(fetch_file, 'w') as document: pass

# point 9
op_files = f'{search_space}/op_folders.txt'
if os.path.isfile(op_files):
    os.remove(op_files)
