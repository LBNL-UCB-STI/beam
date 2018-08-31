import ConfigParser
import os
import sys

from utils import xml_validation

__author__ = 'Andrew A Campbell'

'''
This script is used to compare the output of a MATSim run against a specific subset of screenlines. Takes three sys
args:
1 - path to config file
2 - current ouput directory
'''

if __name__ == '__main__':
    # Load config file
    config_path = sys.argv[1]
    conf = ConfigParser.ConfigParser()
    conf.read(config_path)

    #Paths
    exp_plans_file = conf.get('Paths', 'exp_plans_file')
    l2c_file = conf.get('Paths', 'l2c_file')  # maps links to counties
    out_dir = conf.get('Paths', 'out_dir')

    #Params
    home = conf.get('Params', 'home')
    work = conf.get('Params', 'work')

    # Validate activities
    os.chdir(out_dir)
    adf = xml_validation.analyze_acts(exp_plans_file, l2c_file)
    adf.to_csv('act_analysis.csv', index=False)

    # Validate vmt
    tdf, ddf, idf = xml_validation.calc_vmts(exp_plans_file, l2c_file, home=home, work=work, print_inc=1000)
    tdf.to_csv('total_vmt.csv', index=False)
    ddf.to_csv('direct_H2W_vmt.csv', index=False)
    idf.to_csv('indirect_H2W_vmt.csv', index=False)





