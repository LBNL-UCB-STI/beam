import pandas as pd
import matplotlib
import sys
import matplotlib.pyplot as plt
import numpy as np
import matplotlib.patches as mpatches
import matplotlib.lines as mlines

plt.style.use('seaborn-colorblind')
# plt.style.use('ggplot')
plt.rcParams['axes.edgecolor'] = 'black'
plt.rcParams['axes.facecolor'] = 'white'
plt.rcParams['savefig.facecolor'] = 'white'
plt.rcParams['savefig.edgecolor'] = 'black'

colors = {'blue': '#377eb8', 'green': '#227222', 'orange': '#C66200', 'purple': '#470467', 'red': '#B30C0C',
          'yellow': '#C6A600', 'light.green': '#C0E0C0', 'magenta': '#D0339D', 'dark.blue': '#23128F',
          'brown': '#542D06', 'grey': '#8A8A8A', 'dark.grey': '#2D2D2D', 'light.yellow': '#FFE664',
          'light.purple': '#9C50C0', 'light.orange': '#FFB164', 'black': '#000000'}
mode_colors = {'Ride Hail': colors['red'], 'Car': colors['grey'], 'Walk': colors['green'], 'Transit': colors['blue'],
               'Ride Hail - Transit': colors['light.purple'], 'Ride Hail - Pooled': colors['purple'],
               'CAV': colors['light.yellow'], 'Bike': colors['light.orange']}

expansion_factor = 8000000/630000

_metrics_file = "/Users/haitam/workspace/pyscripts/data/smart/pilates4thSep2019/2010.metrics-final.csv"
_output_folder = "/Users/haitam/workspace/pyscripts/data/smart/pilates4thSep2019/makeplots/2010"

if len(sys.argv) > 1:
    _metrics_file = sys.argv[1]
    _output_folder = "{}/makeplots/{}".format(sys.argv[2].rsplit("/", 1)[0], sys.argv[2].rsplit("/", 1)[1])

df = pd.read_csv(_metrics_file).fillna(0)
_range = range(11)
_xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5, 10, 11, 13, 14]
_names = [tech.rsplit(" ", 1)[0].split(" ")[-1] for tech in list(df['Technology'])]
_sc_names = ['Base', 'Mid-term', 'Long-term', 'Sharing is Caring', 'Technology Takeover', "All About Me"]
_sc_names_xpos = [1, 3, 5.5, 8, 10.5, 13.5]
_population = list(df['population'])

# %%
plt.figure(figsize=(6, 3.5))

height_Transit = df['drive_transit_counts'].values * expansion_factor / 1000000 + df['ride_hail_transit_counts'].values * expansion_factor / 1000000 + df[
    'walk_transit_counts'].values * expansion_factor / 1000000
height_Car = df['car_counts'].values * expansion_factor / 1000000
height_Cav = df['cav_counts'].values * expansion_factor / 1000000
height_RideHail = df['ride_hail_counts'].values * expansion_factor / 1000000 + df['ride_hail_pooled_counts'].values * expansion_factor / 1000000
height_RideHailPooled = df['ride_hail_pooled_counts'].values * expansion_factor / 1000000
height_nonMotorized = df['walk_counts'].values * expansion_factor / 1000000 / 60 + df['bike_counts'].values * expansion_factor / 1000000
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_Cav
height_Transit /= height_all
height_Car /= height_all
height_Cav /= height_all
height_RideHail /= height_all
height_RideHailPooled /= height_all
# height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/50000/60
height_nonMotorized /= height_all

plt_car = plt.bar(x=_xpos, height=height_Car)
plt_cav = plt.bar(x=_xpos, height=height_Cav, bottom=height_Car)
plt_transit = plt.bar(x=_xpos, height=height_Transit, bottom=height_Car + height_Cav)
plt_rh = plt.bar(x=_xpos, height=height_RideHail, bottom=height_Transit + height_Car + height_Cav)
# plt_rhp = plt.bar(x=_xpos,height=height_RideHailPooled,bottom=height_RideHail + height_Car + height_Transit+ height_Cav)
plt_nm = plt.bar(x=_xpos, height=height_nonMotorized, bottom=height_Car + height_Transit + height_RideHail + height_Cav)
plt_rhp = plt.bar(x=_xpos, height=height_RideHailPooled, bottom=height_Transit + height_Car + height_Cav, hatch='///',
                  fill=False)

plt.xticks(_sc_names_xpos, _sc_names, rotation=10)
plt.legend((plt_car, plt_cav, plt_transit, plt_rh, plt_nm), ('Car', 'CAV', 'Transit', 'Ridehail', 'NonMotorized'),
           labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid(False)
for ind in range(11):
    plt.text(_xpos[ind], 1.02,  _names[ind], ha='center')
ax.set_ylim((0, 1.0))
plt.ylabel('Portion of Trips')
plt.savefig('{}.modesplit.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
# %%
plt.figure(figsize=(6, 3.5))
height_Transit = df['VMT_cable_car'].values * expansion_factor / 1000000
height_Transit += df['VMT_bus'].values * expansion_factor / 1000000
height_Transit += df['VMT_ferry'].values * expansion_factor / 1000000
height_Transit += df['VMT_rail'].values * expansion_factor / 1000000
height_Transit += df['VMT_subway'].values * expansion_factor / 1000000
height_Transit += df['VMT_tram'].values * expansion_factor / 1000000
height_Car = df['VMT_car'].values * expansion_factor / 1000000
height_Cav = df['VMT_car_CAV'].values * expansion_factor / 1000000
height_CavEmpty = df['VMT_car_CAV_empty'].values * expansion_factor / 1000000
height_CavShared = df['VMT_car_CAV_shared'].values * expansion_factor / 1000000
height_RideHail = df['VMT_car_RH'].values * expansion_factor / 1000000
height_RideHail += df['VMT_car_RH_CAV'].values * expansion_factor / 1000000
height_RideHailPooled = df['VMT_car_RH_pooled'].values * expansion_factor / 1000000
height_RideHailPooled += df['VMT_car_RH_CAV_pooled'].values * expansion_factor / 1000000
height_RideHailEmpty = df['VMT_car_RH_empty'].values * expansion_factor / 1000000
height_RideHailEmpty += df['VMT_car_RH_CAV_empty'].values * expansion_factor / 1000000
# height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/50000
# height_nonMotorized = df['VMT_walk'].values/50000
height_nonMotorized = df['VMT_bike'].values * expansion_factor / 1000000
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_Cav


plt_car = plt.bar(x=_xpos, height=height_Car)
plt_cav = plt.bar(x=_xpos, height=height_Cav, bottom=height_Car)
plt_transit = plt.bar(x=_xpos, height=height_Transit, bottom=height_Car + height_Cav)
plt_rh = plt.bar(x=_xpos, height=height_RideHail, bottom=height_Transit + height_Car + height_Cav)
plt_nm = plt.bar(x=_xpos, height=height_nonMotorized, bottom=height_Car + height_Transit + height_RideHail + height_Cav)
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)
plt_cav_empty = plt.bar(x=_xpos, height=height_CavEmpty, bottom=height_Car, hatch='///', fill=False,
                        linewidth=0)
plt_rh_empty = plt.bar(x=_xpos, height=height_RideHailEmpty,
                       bottom=height_Transit + height_Car + height_Cav, hatch='///', fill=False, linewidth=0)
plt_rh_pooled = plt.bar(x=_xpos, height=-height_RideHailPooled,
                        bottom=height_Transit + height_Car + height_Cav + height_RideHail, hatch="xx", fill=False,
                        linewidth=0)

ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
shared = mpatches.Patch(facecolor='white', label='The white data', hatch='xx')
ax.grid(False)
plt.legend((plt_car, plt_cav, plt_transit, plt_rh, plt_nm, empty, shared),
           ('Car', 'CAV', 'Transit', 'Ridehail', 'NonMotorized', 'Empty', 'Shared'), labelspacing=-2.5,
           bbox_to_anchor=(1.05, 0.5), frameon=False)
for ind in _range:
    plt.text(_xpos[ind], height_all[ind] + 1.5,  _names[ind], ha='center')
#ax.set_ylim((0, 160))
plt.ylabel('Vehicle Miles Traveled (millions)')
plt.savefig('{}.vmt_mode.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
# %%
plt.figure(figsize=(6, 3.5))


height_Transit = df['PMT_bus'].values * expansion_factor / 1000000
height_Transit += df['PMT_ferry'].values * expansion_factor / 1000000
height_Transit += df['PMT_rail'].values * expansion_factor / 1000000
height_Transit += df['PMT_subway'].values * expansion_factor / 1000000
height_Transit += df['PMT_tram'].values * expansion_factor / 1000000
# height_Transit += df['PMT_cable_car'].values/50000
height_Car = df['PMT_car'].values * expansion_factor / 1000000
height_Cav = df['PMT_car_CAV'].values * expansion_factor / 1000000
height_RideHail = df['PMT_car_RH'].values * expansion_factor / 1000000
height_RideHail += df['PMT_car_RH_CAV'].values * expansion_factor / 1000000
# height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/50000
height_nonMotorized = df['PMT_walk'].values * expansion_factor / 1000000
height_nonMotorized += df['PMT_bike'].values * expansion_factor / 1000000
height_all = height_Car + height_Cav + height_RideHail

plt_car = plt.bar(x=_xpos, height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=_xpos, height=height_Cav, bottom=height_Car, color=mode_colors['CAV'])
#plt_transit = plt.bar(x=_xpos, height=height_Transit, bottom=height_Car + height_Cav, color=mode_colors['Transit'])
plt_rh = plt.bar(x=_xpos, height=height_RideHail, bottom= height_Car + height_Cav,
                 color=mode_colors['Ride Hail'])
#plt_nm = plt.bar(x=_xpos, height=height_nonMotorized, bottom=height_Car + height_Transit + height_RideHail + height_Cav,
#                 color=mode_colors['Bike'])
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)
plt.legend((plt_car, plt_cav, plt_rh), ('Car', 'CAV', 'Ridehail'),
           labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid(False)
for ind in _range:
    plt.text(_xpos[ind], height_all[ind] + 2.5,  _names[ind], ha='center')
ax.set_ylim((0, 400))
plt.ylabel('LDV Person Miles Traveled (millions)')
plt.savefig('{}.pmt_mode.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))


height_Transit = df['PMT_bus'].values * expansion_factor / 1000000
height_Transit += df['PMT_ferry'].values * expansion_factor / 1000000
height_Transit += df['PMT_rail'].values * expansion_factor / 1000000
height_Transit += df['PMT_subway'].values * expansion_factor / 1000000
height_Transit += df['PMT_tram'].values * expansion_factor / 1000000
# height_Transit += df['PMT_cable_car'].values/50000
height_Car = df['PMT_car'].values * expansion_factor / 1000000
height_Cav = df['PMT_car_CAV'].values * expansion_factor / 1000000
height_RideHail = df['PMT_car_RH'].values * expansion_factor / 1000000
height_RideHail += df['PMT_car_RH_CAV'].values * expansion_factor / 1000000
# height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/50000
height_nonMotorized = df['PMT_walk'].values * expansion_factor / 1000000
height_nonMotorized += df['PMT_bike'].values * expansion_factor / 1000000
height_all = height_Car + height_Cav + height_RideHail + height_Transit + height_nonMotorized

plt_car = plt.bar(x=_xpos, height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=_xpos, height=height_Cav, bottom=height_Car, color=mode_colors['CAV'])
plt_rh = plt.bar(x=_xpos, height=height_RideHail, bottom= height_Car + height_Cav,
                 color=mode_colors['Ride Hail'])
plt_transit = plt.bar(x=_xpos, height=height_Transit, bottom=height_Car + height_Cav + height_RideHail, color=mode_colors['Transit'])

plt_nm = plt.bar(x=_xpos, height=height_nonMotorized, bottom=height_Car + height_Transit + height_RideHail + height_Cav,
                 color=mode_colors['Bike'])
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)
plt.legend((plt_car, plt_cav, plt_rh, plt_transit, plt_nm), ('Car', 'CAV', 'Ridehail','Transit','NonMotorized'),
           labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid(False)
for ind in _range:
    plt.text(_xpos[ind], height_all[ind] + 2.5,  _names[ind], ha='center')
ax.set_ylim((0, 420))
plt.ylabel('Person Miles Traveled (millions)')
plt.savefig('{}.pmt_mode_2.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
# %%
plt.figure(figsize=(6, 3.5))

height_Gas = df['totalEnergy_Gasoline'].values * expansion_factor / 1000000000000
height_Diesel = df['totalEnergy_Diesel'].values * expansion_factor / 1000000000000
height_Electricity = df['totalEnergy_Electricity'].values * expansion_factor / 1000000000000
height_all = height_Gas + height_Electricity

plt_g = plt.bar(x=_xpos, height=height_Gas)
plt_d = plt.bar(x=_xpos, height=height_Diesel, bottom=height_Gas)
plt_e = plt.bar(x=_xpos, height=height_Electricity, bottom=height_Diesel + height_Gas)

plt.xticks(_sc_names_xpos, _sc_names, rotation=10)
plt.legend((plt_g, plt_d, plt_e), ('Gasoline', 'Diesel', 'Electricity'), labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5),
           frameon=False)
ax = plt.gca()
ax.grid(False)
for ind in _range:
    plt.text(_xpos[ind], height_all[ind] + 5, _names[ind], ha='center')
# ax.set_ylim((0,400))
plt.ylabel('Light duty vehicle energy use (TJ)')
plt.savefig('{}.energy_source.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()


# %%
plt.figure(figsize=(6, 3.5))

height_Gas = df['totalEnergy_Gasoline'].values/1000000
height_Diesel = df['totalEnergy_Diesel'].values/1000000
height_Electricity = df['totalEnergy_Electricity'].values/1000000
height_all = height_Gas + height_Electricity + height_Diesel
VMT_all = df['motorizedVehicleMilesTraveled_total']
energy_intensity = VMT_all/height_all

plt_g = plt.bar(x=_xpos, height=energy_intensity)

plt.xticks(_sc_names_xpos, _sc_names, rotation=10)

ax = plt.gca()
ax.grid(False)
for ind in _range:
    plt.text(_xpos[ind], energy_intensity[ind] + 0.005,  _names[ind], ha='center')
# ax.set_ylim((0,400))
plt.ylabel('Energy productivity (mi/MJ)')
plt.savefig('{}.energy_intensity.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
# %%
plt.figure(figsize=(6, 3.5))


height_Low = df['VMT_L1'].values * expansion_factor / 1000000
height_High = df['VMT_L3'].values * expansion_factor / 1000000
height_CAV = df['VMT_L5'].values * expansion_factor / 1000000
height_All = height_Low + height_High + height_CAV
height_RH_Empty = df['VMT_car_RH_empty'].values * expansion_factor / 1000000
height_PV_Empty = df['VMT_car_CAV_empty'].values * expansion_factor / 1000000

plt_Low = plt.bar(x=_xpos, height=height_Low)
plt_High = plt.bar(x=_xpos, height=height_High, bottom=height_Low)
plt_CAV = plt.bar(x=_xpos, height=height_CAV, bottom=height_High + height_Low)
plt_empty_car = plt.bar(x=_xpos, height=height_RH_Empty, hatch='///', fill=False)
plt_empty_cav = plt.bar(x=_xpos, height=height_PV_Empty, bottom=height_High + height_Low, hatch='///', fill=False)
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)

ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
plt.legend((plt_Low, plt_High, plt_CAV, empty), ('Low Automation', 'Partial Automation', 'CAV','No Passengers'), labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5),
           frameon=False)
plt.grid(b=None)
# ax.set_ylim((0,10))CAV', 'Partial Automation', 'Low Automation'
ax.grid(False)
for ind in _range:
    plt.text(_xpos[ind], height_All[ind] + 2,  _names[ind], ha='center')
#ax.set_ylim((0, 160))
plt.ylabel('Light duty vehicle miles traveled (millions)')
plt.savefig('{}.vmt_tech.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))

height_Low = df['VMT_L1'].values / _population
height_High = df['VMT_L3'].values / _population
height_CAV = df['VMT_L5'].values / _population
height_all = height_Low + height_High + height_CAV
plt_Low = plt.bar(x=_xpos, height=height_Low, color=colors['blue'])
plt_High = plt.bar(x=_xpos, height=height_High, bottom=height_Low, color=colors['green'])
plt_CAV = plt.bar(x=_xpos, height=height_CAV, bottom=height_Low + height_High, color=colors['red'])
plt.xticks(_sc_names_xpos, ['base', 'a', 'b', 'c'])
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)

ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
for ind in _range:
    plt.text(_xpos[ind], height_all[ind] + 0.3,  _names[ind], ha='center')
ax.set_ylim((0, 40))
plt.ylabel('Light Duty Vehicle Miles per Capita')
plt.legend((plt_CAV, plt_High, plt_Low), ('CAV', 'Partial Automation', 'Low Automation'), bbox_to_anchor=(1.05, 0.5),
           frameon=False)
plt.savefig('{}.vmt_percapita_tech.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 5.5))

height_RH = (df['VMT_car_RH'].values+df['VMT_car_RH_CAV'].values) * expansion_factor / 1000000
height_RH_Empty = df['VMT_car_RH_empty'].values * expansion_factor / 1000000
height_RH_Pooled = df['VMT_car_RH_pooled'].values * expansion_factor / 1000000
height_PV = (df['VMT_car'].values+df['VMT_car_CAV'].values) * expansion_factor / 1000000
height_PV_Empty = df['VMT_car_CAV_empty'].values * expansion_factor / 1000000
height_all = height_RH + height_PV
plt_rh = plt.bar(x=_xpos, height=height_RH, color=mode_colors['Ride Hail'])
rh_empty = plt.bar(x=_xpos, height=height_RH_Empty, hatch='///', fill=False)
rh_pooled = plt.bar(x=_xpos, height=-height_RH_Pooled, bottom=height_RH, hatch='xxx', fill=False)
plt_pv = plt.bar(x=_xpos, height=height_PV, bottom=height_RH, color=mode_colors['Car'])
pv_empty = plt.bar(x=_xpos, height=height_PV_Empty, bottom=height_RH, hatch='///', fill=False)
plt.xticks(_sc_names_xpos, ['base', 'a', 'b', 'c'])
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)

ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
pooled = mpatches.Patch(facecolor='white', label='The white data', hatch='xxx')
for ind in _range:
    plt.text(_xpos[ind], height_all[ind] + 2,  _names[ind], ha='center')
ax.set_ylim((0, 400))
plt.ylabel('Light Duty Vehicle Miles Traveled (millions)')
plt.legend((plt_pv, plt_rh, empty, pooled), ('Personal Vehicle', 'Ridehail', 'Empty', 'Shared'), bbox_to_anchor=(1.05, 0.5),
           frameon=False)
plt.savefig('{}.vmt_rh_empty.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))

height_RH = (df['VMT_car_RH'].values+df['VMT_car_RH_CAV'].values) * expansion_factor / 1000000
height_RH_Empty = df['VMT_car_RH_empty'].values * expansion_factor / 1000000
height_RH_Pooled = df['VMT_car_RH_pooled'].values * expansion_factor / 1000000
height_PV = (df['VMT_car'].values+df['VMT_car_CAV'].values) * expansion_factor / 1000000
height_PV_Empty = df['VMT_car_CAV_empty'].values * expansion_factor / 1000000
height_all = height_RH
plt_rh = plt.bar(x=_xpos, height=height_RH, color=mode_colors['Ride Hail'])
rh_empty = plt.bar(x=_xpos, height=height_RH_Empty, hatch='///', fill=False)
rh_pooled = plt.bar(x=_xpos, height=-height_RH_Pooled, bottom=height_RH, hatch='xxx', fill=False)
plt.xticks(_sc_names_xpos, ['base', 'a', 'b', 'c'])
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)

ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
pooled = mpatches.Patch(facecolor='white', label='The white data', hatch='xxx')
for ind in _range:
    plt.text(_xpos[ind], height_all[ind] + 2,  _names[ind], ha='center')
ax.set_ylim((0, 100))
plt.ylabel('Light Duty Vehicle Miles Traveled (millions)')
plt.legend((plt_rh, empty, pooled), ('Total Ridehail VMT', 'Empty VMT', 'Shared VMT'), bbox_to_anchor=(1.05, 0.5),
           frameon=False)
plt.savefig('{}.vmt_just_rh_empty.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))

height_CAV = df['VMT_car_CAV'].values * expansion_factor / 1000000
height_CAV_Empty = df['VMT_car_CAV_empty'].values * expansion_factor / 1000000
height_CAV_Pooled = df['VMT_cav_shared'].values * expansion_factor / 1000000
height_PV = (df['VMT_car'].values+df['VMT_car_CAV'].values) * expansion_factor / 1000000 - height_CAV
height_PV_Empty = df['VMT_car_CAV_empty'].values * expansion_factor / 1000000
height_all = height_CAV + height_PV
plt_cav = plt.bar(x=_xpos, height=height_CAV, bottom=height_PV, color=mode_colors['Ride Hail'])
plt_pv = plt.bar(x=_xpos, height=height_PV, color=mode_colors['Car'])
rh_empty = plt.bar(x=_xpos, height=height_CAV_Empty, bottom=height_PV, hatch='///', fill=False)
rh_pooled = plt.bar(x=_xpos, height=-height_CAV_Pooled, bottom=height_CAV + height_PV, hatch='xxx', fill=False)
plt.xticks(_sc_names_xpos, ['base', 'a', 'b', 'c'])
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)

ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
pooled = mpatches.Patch(facecolor='white', label='The white data', hatch='xxx')
for ind in _range:
    plt.text(_xpos[ind], height_all[ind] + 2,  _names[ind], ha='center')
ax.set_ylim((0, 400))
plt.ylabel('Personal Vehicle Miles Traveled (millions)')
plt.legend((plt_cav, plt_pv, empty, pooled), ('CAV', 'Human Driven','Empty','Shared'), bbox_to_anchor=(1.05, 0.5),
           frameon=False)
plt.savefig('{}.vmt_just_cav_empty.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()


# %%
plt.figure(figsize=(6, 3.5))

height_wait = df['averageOnDemandRideWaitingTimeInSeconds'].values
plt_rh = plt.bar(x=_xpos, height=height_wait, color=mode_colors['Ride Hail'])

plt.xticks(_sc_names_xpos, ['base', 'a', 'b', 'c'])
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)

ax = plt.gca()

for ind in _range:
    plt.text(_xpos[ind], height_wait[ind] + 0.2,  _names[ind], ha='center')
ax.set_ylim((0, 4.5))
plt.ylabel('Average Ride Hail Wait (min)')
#plt.legend((plt_rh, empty, pooled), ('Total Ridehail VMT', 'Empty VMT', 'Shared VMT'), bbox_to_anchor=(1.05, 0.5),
#           frameon=False)
plt.savefig('{}.wait_time.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))

height_Gas = df['totalEnergy_Gasoline'].values * expansion_factor / 1000000000 / _population

height_Electricity = df['totalEnergy_Electricity'].values * expansion_factor / 1000000000 / _population
height_all = height_Gas + height_Electricity
plt_Gas = plt.bar(x=_xpos, height=height_Gas, color=colors['purple'])
plt_Electricity = plt.bar(x=_xpos, height=height_Electricity, bottom=height_Gas, color=colors['yellow'])
plt.xticks(_sc_names_xpos, ['base', 'a', 'b', 'c'])
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)

ax = plt.gca()
for ind in _range:
    plt.text(_xpos[ind], height_all[ind] + 0.02,  _names[ind], ha='center')
plt.ylabel('Light Duty Vehicle Energy per Capita (GJ)')
# ax.set_ylim((0,310))
plt.legend((plt_Electricity, plt_Gas), ('Electricity', 'Gasoline'), bbox_to_anchor=(1.05, 0.5), frameon=False)
plt.savefig('{}.energy_fuelsource_percapita.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()


#%%


plt.figure(figsize=(6, 3.5))

height_Transit = df['personTravelTime_drive_transit'].values * expansion_factor / 1000000 / 60 + df[
    'personTravelTime_onDemandRide_transit'].values * expansion_factor / 1000000 / 60 + df[
                     'personTravelTime_walk_transit'].values * expansion_factor / 1000000 / 60
height_Car = df['personTravelTime_car'].values * expansion_factor / 1000000 / 60
height_Cav = df['personTravelTime_cav'].values * expansion_factor / 1000000 / 60
height_RideHail = df['personTravelTime_onDemandRide'].values * expansion_factor / 1000000 / 60
height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values * expansion_factor / 1000000 / 60
height_nonMotorized = df['personTravelTime_walk'].values * expansion_factor / 1000000 / 60 + df['personTravelTime_bike'].values * expansion_factor / 1000000 / 60
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_RideHailPooled + height_Cav

plt_car = plt.bar(x=_xpos, height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=_xpos, height=height_Cav, bottom=height_Car, color=mode_colors['CAV'])
plt_transit = plt.bar(x=_xpos, height=height_Transit, bottom=height_Car + height_Cav, color=mode_colors['Transit'])
plt_rh = plt.bar(x=_xpos, height=height_RideHail, bottom=height_Transit + height_Car + height_Cav,
                 color=mode_colors['Ride Hail'])
plt_rhp = plt.bar(x=_xpos, height=height_RideHailPooled,
                  bottom=height_RideHail + height_Car + height_Transit + height_Cav,
                  color=mode_colors['Ride Hail - Transit'])
plt_nm = plt.bar(x=_xpos, height=height_nonMotorized,
                 bottom=height_Car + height_Transit + height_RideHail + height_RideHailPooled + height_Cav,
                 color=mode_colors['Bike'])
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)
plt.legend((plt_car, plt_cav, plt_transit, plt_rh, plt_rhp, plt_nm),
           ('Car', 'CAV', 'Transit', 'Ridehail', 'Ridehail (Pooled)', 'NonMotorized'), labelspacing=-2.5,
           bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in _range:
    plt.text(_xpos[ind], height_all[ind] + 0.3,  _names[ind], ha='center')
ax.set_ylim((0, 18))
plt.ylabel('Person Hours Traveled (millions)')
plt.savefig('{}.pht.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%


plt.figure(figsize=(6, 3.5))

height_Transit = df['personTravelTime_drive_transit'].values * expansion_factor / 1000000 / 60 + df[
    'personTravelTime_onDemandRide_transit'].values * expansion_factor / 1000000 / 60 + df[
                     'personTravelTime_walk_transit'].values * expansion_factor / 1000000 / 60
height_Car = df['personTravelTime_car'].values * expansion_factor / 1000000 / 60
height_Cav = df['personTravelTime_cav'].values * expansion_factor / 1000000 / 60
height_RideHail = df['personTravelTime_onDemandRide'].values * expansion_factor / 1000000 / 60
height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values * expansion_factor / 1000000 / 60
height_nonMotorized = df['personTravelTime_walk'].values * expansion_factor / 1000000 / 60 + df['personTravelTime_bike'].values * expansion_factor / 1000000 / 60
height_all = height_Car + height_RideHail + height_RideHailPooled + height_Cav

plt_car = plt.bar(x=_xpos, height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=_xpos, height=height_Cav, bottom=height_Car, color=mode_colors['CAV'])
#plt_transit = plt.bar(x=_xpos, height=height_Transit, bottom=height_Car + height_Cav, color=mode_colors['Transit'])
plt_rh = plt.bar(x=_xpos, height=height_RideHail, bottom= height_Car + height_Cav,
                 color=mode_colors['Ride Hail'])
plt_rhp = plt.bar(x=_xpos, height=height_RideHailPooled,
                  bottom=height_RideHail + height_Car + height_Cav,
                  color=mode_colors['Ride Hail - Transit'])
#plt_nm = plt.bar(x=_xpos, height=height_nonMotorized,
#                 bottom=height_Car + height_Transit + height_RideHail + height_RideHailPooled + height_Cav,
#                 color=mode_colors['Bike'])
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)
plt.legend((plt_car, plt_cav, plt_rh, plt_rhp),
           ('Car', 'CAV', 'Ridehail', 'Ridehail (Pooled)'), labelspacing=-2.5,
           bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in _range:
    plt.text(_xpos[ind], height_all[ind] + 0.2,  _names[ind], ha='center')
ax.set_ylim((0, 14))
plt.ylabel('Person Hours Traveled (millions)')
plt.savefig('{}.pht_ldv.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%



plt.figure(figsize=(6, 3.5))

height_Transit = df['personTravelTime_drive_transit'].values / _population / 60 + df[
    'personTravelTime_onDemandRide_transit'].values / _population / 60 + df[
                     'personTravelTime_walk_transit'].values / _population / 60
height_Car = df['personTravelTime_car'].values / _population / 60
height_Cav = df['personTravelTime_cav'].values / _population / 60
height_RideHail = df['personTravelTime_onDemandRide'].values / _population / 60
height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values / _population / 60
height_nonMotorized = df['personTravelTime_walk'].values / _population / 60 + df[
    'personTravelTime_bike'].values / _population / 60
height_all = height_Car + height_RideHail + height_RideHailPooled + height_Cav

plt_car = plt.bar(x=_xpos, height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=_xpos, height=height_Cav, bottom=height_Car, color=mode_colors['CAV'])
# plt_transit = plt.bar(x=_xpos,height=height_Transit,bottom=height_Car + height_Cav, color=mode_colors['Transit'])
plt_rh = plt.bar(x=_xpos, height=height_RideHail, bottom=height_Car + height_Cav, color=mode_colors['Ride Hail'])
plt_rhp = plt.bar(x=_xpos, height=height_RideHailPooled, bottom=height_RideHail + height_Car + height_Cav,
                  color=mode_colors['Ride Hail - Transit'])
# plt_nm = plt.bar(x=_xpos,height=height_nonMotorized,bottom= height_Car + height_Transit + height_RideHail+ height_RideHailPooled + height_Cav, color=mode_colors['Bike'])
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)
plt.legend((plt_car, plt_cav, plt_rh, plt_rhp), ('Car', 'CAV', 'Ridehail', 'Ridehail (Pooled)'), labelspacing=-2.5,
           bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in _range:
    plt.text(_xpos[ind], height_all[ind] + 0.025,  _names[ind], ha='center')
ax.set_ylim((0, 1.4))
plt.ylabel('LDV Person Hours Traveled (per capita)')
plt.savefig('{}.pht_percapita.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))

height_Transit = df['personTravelTime_drive_transit'].values / _population / 60 + df[
    'personTravelTime_onDemandRide_transit'].values / _population / 60 + df[
                     'personTravelTime_walk_transit'].values / _population / 60
height_Car = df['personTravelTime_car'].values / _population / 60
height_Cav = df['personTravelTime_cav'].values / _population / 60
height_RideHail = df['personTravelTime_onDemandRide'].values / _population / 60
height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values / _population / 60
height_nonMotorized = df['personTravelTime_walk'].values / _population / 60 + df[
    'personTravelTime_bike'].values / _population / 60
height_all = height_Car + height_RideHail + height_RideHailPooled + height_Cav

#plt_transit = plt.bar(x=_xpos, height=height_Transit, color=mode_colors['Transit'])
plt_rhp = plt.bar(x=_xpos, height=height_RideHailPooled, color=mode_colors['Ride Hail - Transit'])
plt_rh = plt.bar(x=_xpos, height=height_RideHail, bottom= height_RideHailPooled,
                 color=mode_colors['Ride Hail'])
plt_cav = plt.bar(x=_xpos, height=height_Cav, bottom=height_RideHailPooled + height_RideHail,
                  color=mode_colors['CAV'])
plt_car = plt.bar(x=_xpos, height=height_Car,
                  bottom=height_RideHailPooled + height_RideHail + height_Cav,
                  color=mode_colors['Car'])
#plt_nm = plt.bar(x=_xpos, height=height_nonMotorized,
#                 bottom=height_Car + height_Transit + height_RideHail + height_RideHailPooled + height_Cav,
#                 color=mode_colors['Bike'])
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)
plt.legend((plt_rhp, plt_rh, plt_cav, plt_car),
           ('Ride Hail (Pooled)', 'Ride Hail', 'CAV', 'Car'), labelspacing=-2.5,
           bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in _range:
    plt.text(_xpos[ind], height_all[ind] + 0.025,  _names[ind], ha='center')
ax.set_ylim((0, 1.4))
plt.ylabel('LDV Person Hours Traveled (per capita)')
plt.savefig('{}.pht_percapita_reorder.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(5,6))
matching_portion = np.array([0.1,0.126,0.122,0.192,0.187,0.069,0.0874])

height_Transit = df['drive_transit_counts'].values * expansion_factor / 1000000 + df['ride_hail_transit_counts'].values * expansion_factor / 1000000 + df[
    'walk_transit_counts'].values * expansion_factor / 1000000
height_Car = df['car_counts'].values * expansion_factor / 1000000
height_Cav = df['cav_counts'].values * expansion_factor / 1000000
height_RideHail = df['ride_hail_counts'].values * expansion_factor / 1000000
height_RideHailPooled = df['ride_hail_pooled_counts'].values * expansion_factor / 1000000

height_nonMotorized = df['walk_counts'].values * expansion_factor / 1000000 / 60 + df['bike_counts'].values * expansion_factor / 1000000
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_RideHailPooled + height_Cav
height_Transit /= height_all
height_Car /= height_all
height_Cav /= height_all
height_RideHail /= height_all
height_RideHailPooled /= height_all
height_RideHailPooled_match = height_RideHailPooled * matching_portion
# height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/50000/60
height_nonMotorized /= height_all

plt_transit = plt.bar(x=_xpos, height=height_Transit, color=mode_colors['Transit'])
plt_rhp = plt.bar(x=_xpos, height=height_RideHailPooled, bottom=height_Transit, color=mode_colors['Ride Hail - Pooled'])
plt_rh = plt.bar(x=_xpos, height=height_RideHail, bottom=height_Transit + height_RideHailPooled,
                 color=mode_colors['Ride Hail'])
plt_cav = plt.bar(x=_xpos, height=height_Cav, bottom=height_Transit + height_RideHailPooled + height_RideHail,
                  color=mode_colors['CAV'])
plt_car = plt.bar(x=_xpos, height=height_Car,
                  bottom=height_Transit + height_RideHailPooled + height_RideHail + height_Cav,
                  color=mode_colors['Car'])
plt_nm = plt.bar(x=_xpos, height=height_nonMotorized,
                 bottom=height_RideHailPooled + height_Car + height_Transit + height_RideHail + height_Cav,
                 color=mode_colors['Bike'])
plt_rhp_m = plt.bar(x=_xpos,height=-height_RideHailPooled_match,bottom=height_Transit + height_RideHailPooled ,hatch='///',fill=False)

matched = mpatches.Patch(facecolor='white', label='The white data', hatch='///')

plt.xticks(_sc_names_xpos, _sc_names, rotation=10)
plt.legend((plt_transit, matched, plt_rhp, plt_rh, plt_cav, plt_car, plt_nm),
           ('Transit', 'Ride Hail (Matched)', 'Ride Hail (Pooled Requested)', 'Ride Hail', 'CAV', 'Car', 'Non-motorized'), labelspacing=-2.5,
           bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in _range:
    plt.text(_xpos[ind], 1.02,  _names[ind], ha='center')
ax.set_ylim((0, 1.0))
plt.ylabel('Portion of Trips')
plt.savefig('{}.modesplit_reorder.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()



# %%
plt.figure(figsize=(6, 3.5))


height_Transit = df['PMT_bus'].values / _population
height_Transit += df['PMT_ferry'].values / _population
height_Transit += df['PMT_rail'].values / _population
height_Transit += df['PMT_subway'].values / _population
height_Transit += df['PMT_tram'].values / _population
# height_Transit += df['PMT_cable_car'].values/50000
height_Car = df['PMT_car'].values / _population
height_Cav = df['PMT_car_CAV'].values / _population
height_RideHail = df['PMT_car_RH'].values / _population
height_RideHail += df['PMT_car_RH_CAV'].values / _population
# height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/50000
height_nonMotorized = df['PMT_walk'].values / _population
height_nonMotorized += df['PMT_bike'].values / _population
height_all = height_Car  + height_RideHail + height_Cav

plt_car = plt.bar(x=_xpos, height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=_xpos, height=height_Cav, bottom=height_Car, color=mode_colors['CAV'])
# plt_transit = plt.bar(x=_xpos,height=height_Transit,bottom=height_Car + height_Cav,color=mode_colors['Transit'])
plt_rh = plt.bar(x=_xpos, height=height_RideHail, bottom=height_Car + height_Cav, color=mode_colors['Ride Hail'])
# plt_nm = plt.bar(x=_xpos,height=height_nonMotorized,bottom= height_Car + height_Transit + height_RideHail+ height_Cav,color=mode_colors['Bike'])
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)
plt.legend((plt_car, plt_cav, plt_rh), ('Car', 'CAV', 'Ridehail'), labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5),
           frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in _range:
    plt.text(_xpos[ind], height_all[ind] + 0.5,  _names[ind], ha='center')
# ax.set_ylim((0,160))
plt.ylabel('LDV Person Miles Traveled per Capita')
plt.savefig('{}.pmt_percapita_mode.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))


totalEnergy = df['totalEnergy_Gasoline'] / 1000000 + df['totalEnergy_Electricity'] / 1000000

totalPMT = df['PMT_car'].values
totalPMT += df['PMT_car_CAV'].values
totalPMT += df['PMT_car_RH'].values
totalPMT += df['PMT_car_RH_CAV'].values

totalVMT = df['VMT_car'].values
totalVMT += df['VMT_car_CAV'].values
totalVMT += df['VMT_car_RH'].values
totalVMT += df['VMT_car_RH_CAV'].values

height = totalEnergy / totalPMT

plt_e_pmt = plt.bar(x=_xpos, height=height, color=colors['grey'])
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)
ax = plt.gca()
ax.grid(False)
for ind in _range:
    plt.text(_xpos[ind], height[ind] + 0.025,  _names[ind], ha='center')
plt.ylabel('Energy per Light Duty Vehicle Passenger Mile (MJ/mi)')
plt.savefig('{}.energy_per_pmt.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))


totalEnergy = df['totalEnergy_Gasoline'] / 1000000 + df['totalEnergy_Electricity'] / 1000000

totalPMT = df['PMT_car'].values
totalPMT += df['PMT_car_CAV'].values
totalPMT += df['PMT_car_RH'].values
totalPMT += df['PMT_car_RH_CAV'].values

totalVMT = df['VMT_car'].values
totalVMT += df['VMT_car_CAV'].values
totalVMT += df['VMT_car_RH'].values
totalVMT += df['VMT_car_RH_CAV'].values

height = totalEnergy / totalVMT

plt_e_pmt = plt.bar(x=_xpos, height=height, color=colors['grey'])
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)
ax = plt.gca()
ax.grid(False)
for ind in _range:
    plt.text(_xpos[ind], height[ind] + 0.025,  _names[ind], ha='center')
plt.ylabel('Energy per Light Duty Vehicle Mile (MJ/mi)')
plt.savefig('{}.energy_per_vmt.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))


totalPMT = df['PMT_car'].values
totalPMT += df['PMT_car_CAV'].values
totalPMT += df['PMT_car_RH'].values
totalPMT += df['PMT_car_RH_CAV'].values

totalVMT = df['VMT_car'].values
totalVMT += df['VMT_car_CAV'].values
totalVMT += df['VMT_car_RH'].values
totalVMT += df['VMT_car_RH_CAV'].values

height = totalPMT / totalVMT

plt_e_pmt = plt.bar(x=_xpos, height=height, color=colors['grey'])
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)
ax = plt.gca()
ax.grid(False)
for ind in _range:
    plt.text(_xpos[ind], height[ind] + 0.01,  _names[ind], ha='center')
plt.ylabel('Mean Occupancy')
plt.savefig('{}.occupancy.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))


height_Transit = df['PMT_bus'].values / _population
height_Transit += df['PMT_ferry'].values / _population
height_Transit += df['PMT_rail'].values / _population
height_Transit += df['PMT_subway'].values / _population
height_Transit += df['PMT_tram'].values / _population
# height_Transit += df['PMT_cable_car'].values/50000
height_Car = df['PMT_car'].values / _population
height_Cav = df['PMT_car_CAV'].values / _population
height_RideHail = df['PMT_car_RH'].values / _population
height_RideHail += df['PMT_car_RH_CAV'].values / _population
# height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/50000
height_nonMotorized = df['PMT_walk'].values / _population
height_nonMotorized += df['PMT_bike'].values / _population
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_Cav

plt_transit = plt.bar(x=_xpos, height=height_Transit, color=mode_colors['Transit'])
plt_rh = plt.bar(x=_xpos, height=height_RideHail, bottom=height_Transit, color=mode_colors['Ride Hail'])
plt_cav = plt.bar(x=_xpos, height=height_Cav, bottom=height_Transit + height_RideHail, color=mode_colors['CAV'])
plt_car = plt.bar(x=_xpos, height=height_Car, bottom=height_Transit + height_RideHail + height_Cav,
                  color=mode_colors['Car'])
plt_nm = plt.bar(x=_xpos, height=height_nonMotorized, bottom=height_Car + height_Transit + height_RideHail + height_Cav,
                 color=mode_colors['Bike'])
plt.xticks(_sc_names_xpos, _sc_names, rotation=10)
plt.legend((plt_transit, plt_rh, plt_cav, plt_car, plt_nm), ('Transit', 'Ride Hail', 'CAV', 'Car', 'NonMotorized'),
           labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in _range:
    plt.text(_xpos[ind], height_all[ind] + 0.5,  _names[ind], ha='center')
# ax.set_ylim((0,160))
plt.ylabel('Person Miles Traveled per Capita')
plt.savefig('{}.pmt_percapita_mode_reorder.png'.format(_output_folder), transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
