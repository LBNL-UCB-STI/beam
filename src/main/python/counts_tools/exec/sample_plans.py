from lxml import etree
import ConfigParser
import sys

import numpy.random

__author__ = 'Andrew A Campbell'
# Selects a random sample of predetermined size from a MATSim plans file

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print 'ERROR: need to supply the path to the conifg file'
    config_path = sys.argv[1]
    conf = ConfigParser.ConfigParser()
    conf.read(config_path)
    #Paths
    in_plans_path = conf.get('Paths', 'in_plans_path')
    out_sample_path = conf.get('Paths', 'out_sample_path')
    #Parameters
    sample_size = int(conf.get('Params', 'sample_size'))

    # Parse the full sample file
    plans = etree.parse(in_plans_path)
    root = plans.getroot()
    pop = root.getchildren()  # List of all 'person' elements in the input plan

    # Remove all but the sample_size person elements
    remove_idx = numpy.random.choice(numpy.arange(len(pop)), size=len(pop)-sample_size, replace=False)
    remove_idx.sort()
    remove_idx = remove_idx[::-1]  #Needs to be sorted descending because the pop list shrinks with
    # each root.remove below. If you do not do this, you get an index outWriter of range error.
    for i in remove_idx:
        root.remove(pop[i])
    plans.write(out_sample_path)





