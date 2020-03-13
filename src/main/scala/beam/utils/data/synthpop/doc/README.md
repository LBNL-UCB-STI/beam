#Beam scenario generator
## Data sources
To generate new scenario for Beam we need to provide the following information:
1. household.csv and people.csv as an output of SynthPop. How to run it: https://github.com/LBNL-UCB-STI/synthpop#how-to-run-it
2. Census Transportation Planning Products Program data ([CTPP](https://ctpp.transportation.org/2012-2016-5-year-ctpp/)), link to FTP: ftp://data5.ctpp.transportation.org/
3. Shape files for:
    1. Traffic Analysis Zone shape file for specific state from Census Bureau: https://www2.census.gov/geo/tiger/TIGER2010/TAZ/2010/
    2. Block Group file for specific state from Census Bureau: https://www2.census.gov/geo/tiger/TIGER2019/BG/
4. Congestion level data for the area in CSV format
5. Conditional work duration (can be created using [NHTS data](https://nhts.ornl.gov/))
6. OSM PB map of area
7. US state code

![Self-editing Diagram](data_sources.svg)

## Scenario generator
