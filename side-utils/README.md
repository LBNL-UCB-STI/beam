### Side utils module
This module was extracted to contain side utilisation application which will provide some helpful resources for simulation.

##### Modules:
   - Speed compare application

#### Speed compare application
Such util is used to collect Uber provided speed data, aggregate and compose it via different filters.

It takes raw movement data from [http://movement.uber.com/][http://movement.uber.com/] and compares it with BEAM 
simulation data, which loads from R5 network. Based on a filter algorithm result speed will be passed to a file.
Currently may be used 2 types of R5 network: original osm and simplified.

For original osm map we have one to one mapping with Uber data, for simplified network we are building node graph and 
looking for the shortest path between them.
  
To run analysis use: 
```
./gradlew :run -PappArgs="['-u', 'movement-speeds-hourly-san-francisco-2018-10.csv.zip,movement-speeds-hourly-san-francisco-2018-12.csv.zip', '-o', 'osm.mapdb', '-d', 'movement-segments-to-osm-ways-san-francisco-2018.csv.zip', '-r', 'network.dat', '--out', 'links_simplified_sl.csv', '-j', 'movement-junctions-to-osm-nodes-san-francisco-2018.csv.zip', '-s', 'beam-speed.csv.zip', '-m', 'sl']"
```

##### Program arguments:
   - `-u, --uber <user_path>   Uber zip paths`
   - `-o, --osm <osm_map>      OSM map file to compare`
   - `-d, --dict <dict_path>   Uber to OSM way dictionary`
   - `-r, --r5 <r5_path>       R5 Network`
   - `--out <out_file>         Output file`
   - `-m, --mode <mode_type>   Filtering action name`
   - `-j, --junction <junction_dict_path>
                           Junction dictionary path`
   - `-s, --beam_speed <beam_speed_path>
                           Beam speed dictionary path`
   - `--fArgs k1=v1,k2=v2...   Filtering argument`
   - `simple` Command to analyze simplified network
        - `-s, --beam_speed <beam_speed_path>
                                   Beam speed dictionary path`
   
Supported filtering modes:
  - `all` takes all speeds observation for the whole week and computes median, average
  - `we` weighted speed comparision, night observations have twice more weight
  - `mp` takes observation with maximum speed values in provided hours range. Filter arguments `from=21,to=5,p=10`
    - `from` From hour     
    - `to` To hour
    - `p` Observation amount threshold     

[http://movement.uber.com/]: http://movement.uber.com/