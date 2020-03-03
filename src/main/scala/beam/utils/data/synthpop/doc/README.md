#Beam scenario generator
## Data sources
To generate new scenario for Beam we need to provide the following information:
1. household.csv and people.csv as an output of SynthPop. How to run it: https://github.com/LBNL-UCB-STI/synthpop#how-to-run-it
2. Census Transportation Planning Products Program data ([CTPP](https://ctpp.transportation.org/2012-2016-5-year-ctpp/)), link to FTP: ftp://data5.ctpp.transportation.org/
3. Traffic Analysis Zone shape file for specific state from Census Bureau: https://www2.census.gov/geo/tiger/TIGER2010/TAZ/2010/
4. Block Group file for specific state from Census Bureau: https://www2.census.gov/geo/tiger/TIGER2019/BG/
5. Congestion level data for a city in CSV format
6. Conditional work duration (can be created using [NHTS data](https://nhts.ornl.gov/))
7. OSM PB map of area

![Self-editing Diagram](data_sources.svg)
