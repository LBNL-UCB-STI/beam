import pandas as pd
import matplotlib

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

df = pd.read_csv('final_output.csv')

expansion_factor = 8000000/630000

# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
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

plt_car = plt.bar(x=xpos, height=height_Car)
plt_cav = plt.bar(x=xpos, height=height_Cav, bottom=height_Car)
plt_transit = plt.bar(x=xpos, height=height_Transit, bottom=height_Car + height_Cav)
plt_rh = plt.bar(x=xpos, height=height_RideHail, bottom=height_Transit + height_Car + height_Cav)
# plt_rhp = plt.bar(x=xpos,height=height_RideHailPooled,bottom=height_RideHail + height_Car + height_Transit+ height_Cav)
plt_nm = plt.bar(x=xpos, height=height_nonMotorized, bottom=height_Car + height_Transit + height_RideHail + height_Cav)
plt_rhp = plt.bar(x=xpos, height=height_RideHailPooled, bottom=height_Transit + height_Car + height_Cav, hatch='///',
                  fill=False)

plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
plt.legend((plt_car, plt_cav, plt_transit, plt_rh, plt_nm), ('Car', 'CAV', 'Transit', 'Ridehail', 'NonMotorized'),
           labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid(False)
for ind in range(7):
    plt.text(xpos[ind], 1.02, names[ind], ha='center')
ax.set_ylim((0, 1.0))
plt.ylabel('Portion of Trips')
plt.savefig('Plots/modesplit.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_Transit = df['VMT_cable_car'].values * expansion_factor / 1000000
height_Transit += df['VMT_bus'].values * expansion_factor / 1000000
height_Transit += df['VMT_ferry'].values * expansion_factor / 1000000
height_Transit += df['VMT_rail'].values * expansion_factor / 1000000
height_Transit += df['VMT_subway'].values * expansion_factor / 1000000
height_Transit += df['VMT_tram'].values * expansion_factor / 1000000
height_Car = df['VMT_car'].values * expansion_factor / 1000000
height_Cav = df['VMT_car_CAV'].values * expansion_factor / 1000000
height_RideHail = df['VMT_car_RH'].values * expansion_factor / 1000000
height_RideHail += df['VMT_car_RH_CAV'].values * expansion_factor / 1000000
# height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/50000
# height_nonMotorized = df['VMT_walk'].values/50000
height_nonMotorized = df['VMT_bike'].values * expansion_factor / 1000000
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_Cav

plt_car = plt.bar(x=xpos, height=height_Car)
plt_cav = plt.bar(x=xpos, height=height_Cav, bottom=height_Car)
plt_transit = plt.bar(x=xpos, height=height_Transit, bottom=height_Car + height_Cav)
plt_rh = plt.bar(x=xpos, height=height_RideHail, bottom=height_Transit + height_Car + height_Cav)
plt_nm = plt.bar(x=xpos, height=height_nonMotorized, bottom=height_Car + height_Transit + height_RideHail + height_Cav)
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
plt_cav_emprt = plt.bar(x=xpos, height=df['VMT_cav_empty'].values * expansion_factor / 1000000, bottom=height_Car, hatch='///', fill=False,
                        linewidth=0)
plt_rh_empty = plt.bar(x=xpos, height=df['VMT_car_RH_empty'].values * expansion_factor / 1000000,
                       bottom=height_Transit + height_Car + height_Cav, hatch='///', fill=False, linewidth=0)
plt_rh_pooled = plt.bar(x=xpos, height=-df['VMT_car_RH_pooled'].values * expansion_factor / 1000000,
                        bottom=height_Transit + height_Car + height_Cav + height_RideHail, hatch="xx", fill=False,
                        linewidth=0)

ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
shared = mpatches.Patch(facecolor='white', label='The white data', hatch='xx')
ax.grid(False)
plt.legend((plt_car, plt_cav, plt_transit, plt_rh, plt_nm, empty, shared),
           ('Car', 'CAV', 'Transit', 'Ridehail', 'NonMotorized', 'Empty', 'Shared'), labelspacing=-2.5,
           bbox_to_anchor=(1.05, 0.5), frameon=False)
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 1.5, names[ind], ha='center')
#ax.set_ylim((0, 160))
plt.ylabel('Vehicle Miles Traveled (millions)')
plt.savefig('Plots/vmt_mode.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']

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

plt_car = plt.bar(x=xpos, height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=xpos, height=height_Cav, bottom=height_Car, color=mode_colors['CAV'])
#plt_transit = plt.bar(x=xpos, height=height_Transit, bottom=height_Car + height_Cav, color=mode_colors['Transit'])
plt_rh = plt.bar(x=xpos, height=height_RideHail, bottom= height_Car + height_Cav,
                 color=mode_colors['Ride Hail'])
#plt_nm = plt.bar(x=xpos, height=height_nonMotorized, bottom=height_Car + height_Transit + height_RideHail + height_Cav,
#                 color=mode_colors['Bike'])
plt.xticks([1, 3.5, 6.5, 9.5], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
plt.legend((plt_car, plt_cav, plt_rh), ('Car', 'CAV', 'Ridehail'),
           labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid(False)
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 2.5, names[ind], ha='center')
ax.set_ylim((0, 400))
plt.ylabel('LDV Person Miles Traveled (millions)')
plt.savefig('Plots/pmt_mode.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']

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

plt_car = plt.bar(x=xpos, height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=xpos, height=height_Cav, bottom=height_Car, color=mode_colors['CAV'])
plt_rh = plt.bar(x=xpos, height=height_RideHail, bottom= height_Car + height_Cav,
                 color=mode_colors['Ride Hail'])
plt_transit = plt.bar(x=xpos, height=height_Transit, bottom=height_Car + height_Cav + height_RideHail, color=mode_colors['Transit'])

plt_nm = plt.bar(x=xpos, height=height_nonMotorized, bottom=height_Car + height_Transit + height_RideHail + height_Cav,
                 color=mode_colors['Bike'])
plt.xticks([1, 3.5, 6.5, 9.5], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
plt.legend((plt_car, plt_cav, plt_rh, plt_transit, plt_nm), ('Car', 'CAV', 'Ridehail','Transit','NonMotorized'),
           labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid(False)
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 2.5, names[ind], ha='center')
ax.set_ylim((0, 420))
plt.ylabel('Person Miles Traveled (millions)')
plt.savefig('Plots/pmt_mode_2.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_Gas = df['totalEnergy_gasoline'].values * expansion_factor / 1000000000000
height_Diesel = 0  # df['totalEnergy_diesel'].values/50000000
height_Electricity = df['totalEnergy_electricity'].values * expansion_factor / 1000000000000
height_all = height_Gas + height_Electricity

plt_g = plt.bar(x=xpos, height=height_Gas)
plt_d = plt.bar(x=xpos, height=height_Diesel, bottom=height_Gas)
plt_e = plt.bar(x=xpos, height=height_Electricity, bottom=height_Diesel + height_Gas)

plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
plt.legend((plt_g, plt_d, plt_e), ('Gasoline', 'Diesel', 'Electricity'), labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5),
           frameon=False)
ax = plt.gca()
ax.grid(False)
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 5, names[ind], ha='center')
# ax.set_ylim((0,400))
plt.ylabel('Light duty vehicle energy use (TJ)')
plt.savefig('Plots/energy_source.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()


# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_Gas = df['totalEnergy_gasoline'].values/1000000
height_Diesel = 0  # df['totalEnergy_diesel'].values/50000000
height_Electricity = df['totalEnergy_electricity'].values/1000000
height_all = height_Gas + height_Electricity
VMT_all = df['motorizedVehicleMilesTraveled_total']
energy_intensity = VMT_all/height_all

plt_g = plt.bar(x=xpos, height=energy_intensity)

plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)

ax = plt.gca()
ax.grid(False)
for ind in range(7):
    plt.text(xpos[ind], energy_intensity[ind] + 0.005, names[ind], ha='center')
# ax.set_ylim((0,400))
plt.ylabel('Energy productivity (mi/MJ)')
plt.savefig('Plots/energy_intensity.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_Low = df['Low_VMT'].values * expansion_factor / 1000000
height_High = df['High_VMT'].values * expansion_factor / 1000000
height_CAV = df['CAV_VMT'].values * expansion_factor / 1000000
height_All = height_Low + height_High + height_CAV
height_RH_Empty = df['rh_empty_miles'].values * expansion_factor / 1000000
height_PV_Empty = df['VMT_cav_empty'].values * expansion_factor / 1000000

plt_Low = plt.bar(x=xpos, height=height_Low)
plt_High = plt.bar(x=xpos, height=height_High, bottom=height_Low)
plt_CAV = plt.bar(x=xpos, height=height_CAV, bottom=height_High + height_Low)
plt_empty_car = plt.bar(x=xpos, height=height_RH_Empty, hatch='///', fill=False)
plt_empty_cav = plt.bar(x=xpos, height=height_PV_Empty, bottom=height_High + height_Low, hatch='///', fill=False)
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)

ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
plt.legend((plt_Low, plt_High, plt_CAV, empty), ('Low Automation', 'Partial Automation', 'CAV','No Passengers'), labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5),
           frameon=False)
plt.grid(b=None)
# ax.set_ylim((0,10))CAV', 'Partial Automation', 'Low Automation'
ax.grid(False)
for ind in range(7):
    plt.text(xpos[ind], height_All[ind] + 2, names[ind], ha='center')
#ax.set_ylim((0, 160))
plt.ylabel('Light duty vehicle miles traveled (millions)')
plt.savefig('Plots/vmt_tech.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
populations = [612597, 612598, 612598, 755015, 755006, 755022, 754962]
height_Transit = df['totalCost_drive_transit'].values / populations + df[
    'totalCost_onDemandRide_transit'].values / populations + df[
                     'totalCost_walk_transit'].values / populations
height_Car = df['totalCost_car'].values / populations
height_Car += df['totalCost_cav'].values / populations
height_RideHail = df['totalCost_onDemandRide'].values / populations + df[
    'totalCost_onDemandRide_pooled'].values / populations
# height_RideHailPooled = df['totalCost_onDemandRide_pooled'].values*5
height_nonMotorized = df['totalCost_walk'].values / populations + df['totalCost_bike'].values / populations
height_All = height_Transit + height_Car + height_RideHail + height_nonMotorized

plt_car = plt.bar(x=xpos, height=height_Car)
plt_transit = plt.bar(x=xpos, height=height_Transit, bottom=height_Car)
plt_rh = plt.bar(x=xpos, height=height_RideHail, bottom=height_Transit + height_Car)
# plt_rhp = plt.bar(x=xpos,height=height_RideHailPooled,bottom=height_RideHail + height_Car + height_Transit)
plt_nm = plt.bar(x=xpos, height=height_nonMotorized, bottom=height_Car + height_Transit + height_RideHail)
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
for ind in range(7):
    plt.text(xpos[ind], height_All[ind] + 0.07, names[ind], ha='center')
ax = plt.gca()
ax.set_ylim((0, 10))
plt.ylabel('Cost per Capita')
plt.legend((plt_car, plt_transit, plt_rh, plt_nm),
           ('Car', 'Transit', 'Ridehail', 'NonMotorized'), labelspacing=-2.5,
           bbox_to_anchor=(1.05, 0.5), frameon=False)
plt.savefig('Plots/cost.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
# %%
xa = np.array([2019, 2025])
xbc = np.array([2019, 2040])
key = 'averageOnDemandRideWaitTimeInMin'

A_LT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'a') & (df['Technology'] == 'Low Tech')]], axis=0,
                 ignore_index=True)
A_HT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'a') & (df['Technology'] == 'High Tech')]],
                 axis=0, ignore_index=True)
B_LT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'b') & (df['Technology'] == 'Low Tech')]], axis=0,
                 ignore_index=True)
B_HT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'b') & (df['Technology'] == 'High Tech')]],
                 axis=0, ignore_index=True)
C_LT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'c') & (df['Technology'] == 'Low Tech')]], axis=0,
                 ignore_index=True)
C_HT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'c') & (df['Technology'] == 'High Tech')]],
                 axis=0, ignore_index=True)

fig, ax = plt.subplots()
# ax.plot(x,B_LT['motorizedVehicleMilesTraveled_total'])
# ax.plot(x,B_HT['motorizedVehicleMilesTraveled_total'])
# ax.plot(x,C_LT['motorizedVehicleMilesTraveled_total'])
# ax.plot(x,C_HT['motorizedVehicleMilesTraveled_total'])
line_alt = ax.plot(xa, A_LT[key], color='black', marker='o')
line_aht = ax.plot(xa, A_HT[key], color='black', marker='v')
line_lt = ax.plot(xbc, C_LT[key], xbc, B_LT[key], color='black', marker='o')
line_ht = ax.plot(xbc, C_HT[key], xbc, B_HT[key], color='black', marker='v')
area_A = ax.fill_between(xa, A_LT[key], A_HT[key], alpha=0.5)
area_C = ax.fill_between(xbc, C_LT[key], C_HT[key], alpha=0.5)
area_B = ax.fill_between(xbc, B_LT[key], B_HT[key], alpha=0.5)
tech_legend = plt.legend((area_A, area_B, area_C),
                         ('A: Sharing is Caring', 'B: Technology takes over', "C: We're in trouble"), loc=3)
ax.add_artist(tech_legend)
ax.axis('on')
circles = mlines.Line2D([], [], color='black', marker='o',
                        markersize=5, label='Low Tech')
triangles = mlines.Line2D([], [], color='black', marker='v',
                          markersize=5, label='High Tech')
plt.legend(handles=[circles, triangles], loc=1)
plt.xlabel('Year')
plt.ylabel('Average on demand wait time (min)')
# ax.spines['bottom'].set_color('green')
# ax.spines['left'].set_color('green')
plt.savefig('Plots/OD_Wait_Time.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
# %%
xa = np.array([2019, 2025])
xbc = np.array([2019, 2040])

# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
populations = [612597, 612598, 612598, 755015, 755006, 755022, 754962]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_Low = df['Low_VMT'].values / populations
height_High = df['High_VMT'].values / populations
height_CAV = df['CAV_VMT'].values / populations
height_all = height_Low + height_High + height_CAV
plt_Low = plt.bar(x=xpos, height=height_Low, color=colors['blue'])
plt_High = plt.bar(x=xpos, height=height_High, bottom=height_Low, color=colors['green'])
plt_CAV = plt.bar(x=xpos, height=height_CAV, bottom=height_Low + height_High, color=colors['red'])
plt.xticks([1, 3, 5.5, 8], ['base', 'a', 'b', 'c'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)

ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 0.3, names[ind], ha='center')
ax.set_ylim((0, 40))
plt.ylabel('Light Duty Vehicle Miles per Capita')
plt.legend((plt_CAV, plt_High, plt_Low), ('CAV', 'Partial Automation', 'Low Automation'), bbox_to_anchor=(1.05, 0.5),
           frameon=False)
plt.savefig('Plots/vmt_percapita_tech.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
populations = [612597, 612598, 612598, 755015, 755006, 755022, 754962]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_Low = df['Low_VMT'].values * expansion_factor / 1000000
height_High = df['High_VMT'].values * expansion_factor / 1000000
height_CAV = df['CAV_VMT'].values * expansion_factor / 1000000
height_all = height_Low + height_High + height_CAV
plt_Low = plt.bar(x=xpos, height=height_Low, color=colors['blue'])
plt_High = plt.bar(x=xpos, height=height_High, bottom=height_Low, color=colors['green'])
plt_CAV = plt.bar(x=xpos, height=height_CAV, bottom=height_Low + height_High, color=colors['red'])
plt.xticks([1, 3, 5.5, 8], ['base', 'a', 'b', 'c'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)

ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 2, names[ind], ha='center')
# ax.set_ylim((0,40))
plt.ylabel('Light Duty Vehicle Miles (millions)')
plt.legend((plt_CAV, plt_High, plt_Low), ('CAV', 'Partial Automation', 'Low Automation'), bbox_to_anchor=(1.05, 0.5),
           frameon=False)
plt.savefig('Plots/vmt_tech_2.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')

plt.clf()
# %%
plt.figure(figsize=(6, 5.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_RH = df['RH_VMT'].values * expansion_factor / 1000000
height_RH_Empty = df['rh_empty_miles'].values * expansion_factor / 1000000
height_RH_Pooled = df['VMT_car_RH_pooled'].values * expansion_factor / 1000000
height_PV = df['PV_VMT'].values * expansion_factor / 1000000
height_PV_Empty = df['cav_empty_miles'].values * expansion_factor / 1000000
height_all = height_RH + height_PV
plt_rh = plt.bar(x=xpos, height=height_RH, color=mode_colors['Ride Hail'])
rh_empty = plt.bar(x=xpos, height=height_RH_Empty, hatch='///', fill=False)
rh_pooled = plt.bar(x=xpos, height=-height_RH_Pooled, bottom=height_RH, hatch='xxx', fill=False)
plt_pv = plt.bar(x=xpos, height=height_PV, bottom=height_RH, color=mode_colors['Car'])
pv_empty = plt.bar(x=xpos, height=height_PV_Empty, bottom=height_RH, hatch='///', fill=False)
plt.xticks([1, 3, 5.5, 8], ['base', 'a', 'b', 'c'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)

ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
pooled = mpatches.Patch(facecolor='white', label='The white data', hatch='xxx')
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 2, names[ind], ha='center')
ax.set_ylim((0, 400))
plt.ylabel('Light Duty Vehicle Miles Traveled (millions)')
plt.legend((plt_pv, plt_rh, empty, pooled), ('Personal Vehicle', 'Ridehail', 'Empty', 'Shared'), bbox_to_anchor=(1.05, 0.5),
           frameon=False)
plt.savefig('Plots/vmt_rh_empty.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_RH = df['RH_VMT'].values * expansion_factor / 1000000
height_RH_Empty = df['rh_empty_miles'].values * expansion_factor / 1000000
height_RH_Pooled = df['VMT_car_RH_pooled'].values * expansion_factor / 1000000
height_PV = df['PV_VMT'].values * expansion_factor / 1000000
height_PV_Empty = df['cav_empty_miles'].values * expansion_factor / 1000000
height_all = height_RH
plt_rh = plt.bar(x=xpos, height=height_RH, color=mode_colors['Ride Hail'])
rh_empty = plt.bar(x=xpos, height=height_RH_Empty, hatch='///', fill=False)
rh_pooled = plt.bar(x=xpos, height=-height_RH_Pooled, bottom=height_RH, hatch='xxx', fill=False)
plt.xticks([1, 3, 5.5, 8], ['base', 'a', 'b', 'c'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)

ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
pooled = mpatches.Patch(facecolor='white', label='The white data', hatch='xxx')
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 2, names[ind], ha='center')
ax.set_ylim((0, 100))
plt.ylabel('Light Duty Vehicle Miles Traveled (millions)')
plt.legend((plt_rh, empty, pooled), ('Total Ridehail VMT', 'Empty VMT', 'Shared VMT'), bbox_to_anchor=(1.05, 0.5),
           frameon=False)
plt.savefig('Plots/vmt_just_rh_empty.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_CAV = df['VMT_car_CAV'].values * expansion_factor / 1000000
height_CAV_Empty = df['VMT_cav_empty'].values * expansion_factor / 1000000
height_CAV_Pooled = df['VMT_cav_shared'].values * expansion_factor / 1000000
height_PV = df['PV_VMT'].values * expansion_factor / 1000000 - height_CAV
height_PV_Empty = df['cav_empty_miles'].values * expansion_factor / 1000000
height_all = height_CAV + height_PV
plt_cav = plt.bar(x=xpos, height=height_CAV, bottom=height_PV, color=mode_colors['Ride Hail'])
plt_pv = plt.bar(x=xpos, height=height_PV, color=mode_colors['Car'])
rh_empty = plt.bar(x=xpos, height=height_CAV_Empty, bottom=height_PV, hatch='///', fill=False)
rh_pooled = plt.bar(x=xpos, height=-height_CAV_Pooled, bottom=height_CAV + height_PV, hatch='xxx', fill=False)
plt.xticks([1, 3, 5.5, 8], ['base', 'a', 'b', 'c'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)

ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
pooled = mpatches.Patch(facecolor='white', label='The white data', hatch='xxx')
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 2, names[ind], ha='center')
ax.set_ylim((0, 400))
plt.ylabel('Personal Vehicle Miles Traveled (millions)')
plt.legend((plt_cav, plt_pv, empty, pooled), ('CAV', 'Human Driven','Empty','Shared'), bbox_to_anchor=(1.05, 0.5),
           frameon=False)
plt.savefig('Plots/vmt_just_cav_empty.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()


# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_wait = df['averageOnDemandRideWaitingTimeInSeconds'].values
plt_rh = plt.bar(x=xpos, height=height_wait, color=mode_colors['Ride Hail'])

plt.xticks([1, 3, 5.5, 8], ['base', 'a', 'b', 'c'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)

ax = plt.gca()

for ind in range(7):
    plt.text(xpos[ind], height_wait[ind] + 0.2, names[ind], ha='center')
ax.set_ylim((0, 4.5))
plt.ylabel('Average Ride Hail Wait (min)')
#plt.legend((plt_rh, empty, pooled), ('Total Ridehail VMT', 'Empty VMT', 'Shared VMT'), bbox_to_anchor=(1.05, 0.5),
#           frameon=False)
plt.savefig('Plots/wait_time.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_Gas = df['totalEnergy_gasoline'].values * expansion_factor / 1000000000000

height_Electricity = df['totalEnergy_electricity'].values * expansion_factor / 1000000000000
height_all = height_Gas + height_Electricity
plt_Gas = plt.bar(x=xpos, height=height_Gas, color=colors['purple'])

plt_Electricity = plt.bar(x=xpos, height=height_Electricity, bottom=height_Gas, color=colors['yellow'])
plt.xticks([1, 3, 5.5, 8], ['base', 'a', 'b', 'c'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)

ax = plt.gca()
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 15, names[ind], ha='center')
plt.ylabel('Light Duty Vehicle Energy (TJ)')
ax.set_ylim((0, 1100))
plt.legend((plt_Electricity, plt_Gas), ('Electricity', 'Gasoline'), bbox_to_anchor=(1.05, 0.5), frameon=False)
plt.savefig('Plots/energy_fuelsource.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
populations = [612597, 612598, 612598, 755015, 755006, 755022, 754962]
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_Gas = df['totalEnergy_gasoline'].values * expansion_factor / 1000000000 / populations

height_Electricity = df['totalEnergy_electricity'].values * expansion_factor / 1000000000 / populations
height_all = height_Gas + height_Electricity
plt_Gas = plt.bar(x=xpos, height=height_Gas, color=colors['purple'])
plt_Electricity = plt.bar(x=xpos, height=height_Electricity, bottom=height_Gas, color=colors['yellow'])
plt.xticks([1, 3, 5.5, 8], ['base', 'a', 'b', 'c'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)

ax = plt.gca()
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 0.02, names[ind], ha='center')
plt.ylabel('Light Duty Vehicle Energy per Capita (GJ)')
# ax.set_ylim((0,310))
plt.legend((plt_Electricity, plt_Gas), ('Electricity', 'Gasoline'), bbox_to_anchor=(1.05, 0.5), frameon=False)
plt.savefig('Plots/energy_fuelsource_percapita.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()


#%%


plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_Transit = df['personTravelTime_drive_transit'].values * expansion_factor / 1000000 / 60 + df[
    'personTravelTime_onDemandRide_transit'].values * expansion_factor / 1000000 / 60 + df[
                     'personTravelTime_walk_transit'].values * expansion_factor / 1000000 / 60
height_Car = df['personTravelTime_car'].values * expansion_factor / 1000000 / 60
height_Cav = df['personTravelTime_cav'].values * expansion_factor / 1000000 / 60
height_RideHail = df['personTravelTime_onDemandRide'].values * expansion_factor / 1000000 / 60
height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values * expansion_factor / 1000000 / 60
height_nonMotorized = df['personTravelTime_walk'].values * expansion_factor / 1000000 / 60 + df['personTravelTime_bike'].values * expansion_factor / 1000000 / 60
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_RideHailPooled + height_Cav

plt_car = plt.bar(x=xpos, height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=xpos, height=height_Cav, bottom=height_Car, color=mode_colors['CAV'])
plt_transit = plt.bar(x=xpos, height=height_Transit, bottom=height_Car + height_Cav, color=mode_colors['Transit'])
plt_rh = plt.bar(x=xpos, height=height_RideHail, bottom=height_Transit + height_Car + height_Cav,
                 color=mode_colors['Ride Hail'])
plt_rhp = plt.bar(x=xpos, height=height_RideHailPooled,
                  bottom=height_RideHail + height_Car + height_Transit + height_Cav,
                  color=mode_colors['Ride Hail - Transit'])
plt_nm = plt.bar(x=xpos, height=height_nonMotorized,
                 bottom=height_Car + height_Transit + height_RideHail + height_RideHailPooled + height_Cav,
                 color=mode_colors['Bike'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
plt.legend((plt_car, plt_cav, plt_transit, plt_rh, plt_rhp, plt_nm),
           ('Car', 'CAV', 'Transit', 'Ridehail', 'Ridehail (Pooled)', 'NonMotorized'), labelspacing=-2.5,
           bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 0.3, names[ind], ha='center')
ax.set_ylim((0, 18))
plt.ylabel('Person Hours Traveled (millions)')
plt.savefig('Plots/pht.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%


plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_Transit = df['personTravelTime_drive_transit'].values * expansion_factor / 1000000 / 60 + df[
    'personTravelTime_onDemandRide_transit'].values * expansion_factor / 1000000 / 60 + df[
                     'personTravelTime_walk_transit'].values * expansion_factor / 1000000 / 60
height_Car = df['personTravelTime_car'].values * expansion_factor / 1000000 / 60
height_Cav = df['personTravelTime_cav'].values * expansion_factor / 1000000 / 60
height_RideHail = df['personTravelTime_onDemandRide'].values * expansion_factor / 1000000 / 60
height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values * expansion_factor / 1000000 / 60
height_nonMotorized = df['personTravelTime_walk'].values * expansion_factor / 1000000 / 60 + df['personTravelTime_bike'].values * expansion_factor / 1000000 / 60
height_all = height_Car + height_RideHail + height_RideHailPooled + height_Cav

plt_car = plt.bar(x=xpos, height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=xpos, height=height_Cav, bottom=height_Car, color=mode_colors['CAV'])
#plt_transit = plt.bar(x=xpos, height=height_Transit, bottom=height_Car + height_Cav, color=mode_colors['Transit'])
plt_rh = plt.bar(x=xpos, height=height_RideHail, bottom= height_Car + height_Cav,
                 color=mode_colors['Ride Hail'])
plt_rhp = plt.bar(x=xpos, height=height_RideHailPooled,
                  bottom=height_RideHail + height_Car + height_Cav,
                  color=mode_colors['Ride Hail - Transit'])
#plt_nm = plt.bar(x=xpos, height=height_nonMotorized,
#                 bottom=height_Car + height_Transit + height_RideHail + height_RideHailPooled + height_Cav,
#                 color=mode_colors['Bike'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
plt.legend((plt_car, plt_cav, plt_rh, plt_rhp),
           ('Car', 'CAV', 'Ridehail', 'Ridehail (Pooled)'), labelspacing=-2.5,
           bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 0.2, names[ind], ha='center')
ax.set_ylim((0, 14))
plt.ylabel('Person Hours Traveled (millions)')
plt.savefig('Plots/pht_ldv.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%



plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
populations = [612597, 612598, 612598, 755015, 755006, 755022, 754962]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_Transit = df['personTravelTime_drive_transit'].values / populations / 60 + df[
    'personTravelTime_onDemandRide_transit'].values / populations / 60 + df[
                     'personTravelTime_walk_transit'].values / populations / 60
height_Car = df['personTravelTime_car'].values / populations / 60
height_Cav = df['personTravelTime_cav'].values / populations / 60
height_RideHail = df['personTravelTime_onDemandRide'].values / populations / 60
height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values / populations / 60
height_nonMotorized = df['personTravelTime_walk'].values / populations / 60 + df[
    'personTravelTime_bike'].values / populations / 60
height_all = height_Car + height_RideHail + height_RideHailPooled + height_Cav

plt_car = plt.bar(x=xpos, height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=xpos, height=height_Cav, bottom=height_Car, color=mode_colors['CAV'])
# plt_transit = plt.bar(x=xpos,height=height_Transit,bottom=height_Car + height_Cav, color=mode_colors['Transit'])
plt_rh = plt.bar(x=xpos, height=height_RideHail, bottom=height_Car + height_Cav, color=mode_colors['Ride Hail'])
plt_rhp = plt.bar(x=xpos, height=height_RideHailPooled, bottom=height_RideHail + height_Car + height_Cav,
                  color=mode_colors['Ride Hail - Transit'])
# plt_nm = plt.bar(x=xpos,height=height_nonMotorized,bottom= height_Car + height_Transit + height_RideHail+ height_RideHailPooled + height_Cav, color=mode_colors['Bike'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
plt.legend((plt_car, plt_cav, plt_rh, plt_rhp), ('Car', 'CAV', 'Ridehail', 'Ridehail (Pooled)'), labelspacing=-2.5,
           bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 0.025, names[ind], ha='center')
ax.set_ylim((0, 1.4))
plt.ylabel('LDV Person Hours Traveled (per capita)')
plt.savefig('Plots/pht_percapita.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
populations = [612597, 612598, 612598, 755015, 755006, 755022, 754962]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_Transit = df['personTravelTime_drive_transit'].values / populations / 60 + df[
    'personTravelTime_onDemandRide_transit'].values / populations / 60 + df[
                     'personTravelTime_walk_transit'].values / populations / 60
height_Car = df['personTravelTime_car'].values / populations / 60
height_Cav = df['personTravelTime_cav'].values / populations / 60
height_RideHail = df['personTravelTime_onDemandRide'].values / populations / 60
height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values / populations / 60
height_nonMotorized = df['personTravelTime_walk'].values / populations / 60 + df[
    'personTravelTime_bike'].values / populations / 60
height_all = height_Car + height_RideHail + height_RideHailPooled + height_Cav

#plt_transit = plt.bar(x=xpos, height=height_Transit, color=mode_colors['Transit'])
plt_rhp = plt.bar(x=xpos, height=height_RideHailPooled, color=mode_colors['Ride Hail - Transit'])
plt_rh = plt.bar(x=xpos, height=height_RideHail, bottom= height_RideHailPooled,
                 color=mode_colors['Ride Hail'])
plt_cav = plt.bar(x=xpos, height=height_Cav, bottom=height_RideHailPooled + height_RideHail,
                  color=mode_colors['CAV'])
plt_car = plt.bar(x=xpos, height=height_Car,
                  bottom=height_RideHailPooled + height_RideHail + height_Cav,
                  color=mode_colors['Car'])
#plt_nm = plt.bar(x=xpos, height=height_nonMotorized,
#                 bottom=height_Car + height_Transit + height_RideHail + height_RideHailPooled + height_Cav,
#                 color=mode_colors['Bike'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
plt.legend((plt_rhp, plt_rh, plt_cav, plt_car),
           ('Ride Hail (Pooled)', 'Ride Hail', 'CAV', 'Car'), labelspacing=-2.5,
           bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 0.025, names[ind], ha='center')
ax.set_ylim((0, 1.4))
plt.ylabel('LDV Person Hours Traveled (per capita)')
plt.savefig('Plots/pht_percapita_reorder.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
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
# height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/50000/60
height_nonMotorized /= height_all

plt_car = plt.bar(x=xpos, height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=xpos, height=height_Cav, bottom=height_Car, color=mode_colors['CAV'])
plt_transit = plt.bar(x=xpos, height=height_Transit, bottom=height_Car + height_Cav, color=mode_colors['Transit'])
plt_rh = plt.bar(x=xpos, height=height_RideHail, bottom=height_Transit + height_Car + height_Cav,
                 color=mode_colors['Ride Hail'])
plt_rhp = plt.bar(x=xpos, height=height_RideHailPooled,
                  bottom=height_RideHail + height_Car + height_Transit + height_Cav,
                  color=mode_colors['Ride Hail - Pooled'])
plt_nm = plt.bar(x=xpos, height=height_nonMotorized,
                 bottom=height_RideHailPooled + height_Car + height_Transit + height_RideHail + height_Cav,
                 color=mode_colors['Bike'])
# plt_rhp = plt.bar(x=xpos,height=height_RideHailPooled,bottom=height_Transit + height_Car+ height_Cav,hatch='///',fill=False)

plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
plt.legend((plt_car, plt_cav, plt_transit, plt_rh, plt_rhp, plt_nm),
           ('Car', 'CAV', 'Transit', 'Ridehail', 'Ridehail Pooled', 'NonMotorized'), labelspacing=-2.5,
           bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind], 1.02, names[ind], ha='center')
ax.set_ylim((0, 1.0))
plt.ylabel('Portion of Trips')
plt.savefig('Plots/modesplit.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
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
# height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/50000/60
height_nonMotorized /= height_all

plt_transit = plt.bar(x=xpos, height=height_Transit, color=mode_colors['Transit'])
plt_rhp = plt.bar(x=xpos, height=height_RideHailPooled, bottom=height_Transit, color=mode_colors['Ride Hail - Pooled'])
plt_rh = plt.bar(x=xpos, height=height_RideHail, bottom=height_Transit + height_RideHailPooled,
                 color=mode_colors['Ride Hail'])
plt_cav = plt.bar(x=xpos, height=height_Cav, bottom=height_Transit + height_RideHailPooled + height_RideHail,
                  color=mode_colors['CAV'])
plt_car = plt.bar(x=xpos, height=height_Car,
                  bottom=height_Transit + height_RideHailPooled + height_RideHail + height_Cav,
                  color=mode_colors['Car'])
plt_nm = plt.bar(x=xpos, height=height_nonMotorized,
                 bottom=height_RideHailPooled + height_Car + height_Transit + height_RideHail + height_Cav,
                 color=mode_colors['Bike'])
# plt_rhp = plt.bar(x=xpos,height=height_RideHailPooled,bottom=height_Transit + height_Car+ height_Cav,hatch='///',fill=False)

plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
plt.legend((plt_transit, plt_rhp, plt_rh, plt_cav, plt_car, plt_nm),
           ('Transit', 'Ride Hail (Pooled)', 'Ride Hail', 'CAV', 'Car', 'NonMotorized'), labelspacing=-2.5,
           bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind], 1.02, names[ind], ha='center')
ax.set_ylim((0, 1.0))
plt.ylabel('Portion of Trips')
plt.savefig('Plots/modesplit_reorder.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()


# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_Transit = df['VMT_cable_car'].values * expansion_factor / 1000000
height_Transit += df['VMT_bus'].values * expansion_factor / 1000000
height_Transit += df['VMT_ferry'].values * expansion_factor / 1000000
height_Transit += df['VMT_rail'].values * expansion_factor / 1000000
height_Transit += df['VMT_subway'].values * expansion_factor / 1000000
height_Transit += df['VMT_tram'].values * expansion_factor / 1000000
height_Car = df['VMT_car'].values * expansion_factor / 1000000
height_Cav = df['VMT_car_CAV'].values * expansion_factor / 1000000
height_RideHail = df['VMT_car_RH'].values * expansion_factor / 1000000
height_RideHail += df['VMT_car_RH_CAV'].values * expansion_factor / 1000000
# height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/50000
# height_nonMotorized = df['VMT_walk'].values/50000
height_nonMotorized = df['VMT_bike'].values * expansion_factor / 1000000
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_Cav

plt_car = plt.bar(x=xpos, height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=xpos, height=height_Cav, bottom=height_Car, color=mode_colors['CAV'])
# plt_transit = plt.bar(x=xpos,height=height_Transit,bottom=height_Car + height_Cav, color=mode_colors['Transit'])
plt_rh = plt.bar(x=xpos, height=height_RideHail, bottom=height_Car + height_Cav, color=mode_colors['Ride Hail'])
# plt_nm = plt.bar(x=xpos,height=height_nonMotorized,bottom= height_Car + height_RideHail+ height_Cav, color=mode_colors['Bike'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
plt_cav_emprt = plt.bar(x=xpos, height=df['VMT_cav_empty'].values * expansion_factor / 1000000, bottom=height_Car, hatch='///', fill=False,
                        linewidth=1)
plt_rh_empty = plt.bar(x=xpos, height=df['VMT_car_RH_empty'].values * expansion_factor / 1000000,
                       bottom=height_Car + height_Cav, hatch='///', fill=False, linewidth=1)
plt_rh_pooled = plt.bar(x=xpos, height=-df['VMT_car_RH_pooled'].values * expansion_factor / 1000000,
                        bottom=height_Car + height_Cav + height_RideHail, hatch="xx", fill=False,
                        linewidth=1)
plt_cav_shared = plt.bar(x=xpos, height=-df['VMT_cav_shared'].values * expansion_factor / 1000000, bottom=height_Car + height_Cav, hatch='xxx', fill=False, linewidth=1)
ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
shared = mpatches.Patch(facecolor='white', label='The white data', hatch='xxx')
ax.grid('off')
plt.legend((plt_car, plt_cav, plt_rh, empty, shared), ('Car', 'CAV', 'Ridehail', 'Empty', 'Shared'), labelspacing=-2.5,
           bbox_to_anchor=(1.05, 0.5), frameon=False)
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 5, names[ind], ha='center')
ax.set_ylim((0, 400))
plt.ylabel('Vehicle Miles Traveled (millions)')
plt.savefig('Plots/vmt_mode_2.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))
populations = [612597, 612598, 612598, 755015, 755006, 755022, 754962]
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']

height_Transit = df['PMT_bus'].values / populations
height_Transit += df['PMT_ferry'].values / populations
height_Transit += df['PMT_rail'].values / populations
height_Transit += df['PMT_subway'].values / populations
height_Transit += df['PMT_tram'].values / populations
# height_Transit += df['PMT_cable_car'].values/50000
height_Car = df['PMT_car'].values / populations
height_Cav = df['PMT_car_CAV'].values / populations
height_RideHail = df['PMT_car_RH'].values / populations
height_RideHail += df['PMT_car_RH_CAV'].values / populations
# height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/50000
height_nonMotorized = df['PMT_walk'].values / populations
height_nonMotorized += df['PMT_bike'].values / populations
height_all = height_Car  + height_RideHail + height_Cav

plt_car = plt.bar(x=xpos, height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=xpos, height=height_Cav, bottom=height_Car, color=mode_colors['CAV'])
# plt_transit = plt.bar(x=xpos,height=height_Transit,bottom=height_Car + height_Cav,color=mode_colors['Transit'])
plt_rh = plt.bar(x=xpos, height=height_RideHail, bottom=height_Car + height_Cav, color=mode_colors['Ride Hail'])
# plt_nm = plt.bar(x=xpos,height=height_nonMotorized,bottom= height_Car + height_Transit + height_RideHail+ height_Cav,color=mode_colors['Bike'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
plt.legend((plt_car, plt_cav, plt_rh), ('Car', 'CAV', 'Ridehail'), labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5),
           frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 0.5, names[ind], ha='center')
# ax.set_ylim((0,160))
plt.ylabel('LDV Person Miles Traveled per Capita')
plt.savefig('Plots/pmt_percapita_mode.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']

totalEnergy = df['totalEnergy_gasoline'] / 1000000 + df['totalEnergy_electricity'] / 1000000

totalPMT = df['PMT_car'].values
totalPMT += df['PMT_car_CAV'].values
totalPMT += df['PMT_car_RH'].values
totalPMT += df['PMT_car_RH_CAV'].values

totalVMT = df['VMT_car'].values
totalVMT += df['VMT_car_CAV'].values
totalVMT += df['VMT_car_RH'].values
totalVMT += df['VMT_car_RH_CAV'].values

height = totalEnergy / totalPMT

plt_e_pmt = plt.bar(x=xpos, height=height, color=colors['grey'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
ax = plt.gca()
ax.grid(False)
for ind in range(7):
    plt.text(xpos[ind], height[ind] + 0.025, names[ind], ha='center')
plt.ylabel('Energy per Light Duty Vehicle Passenger Mile (MJ/mi)')
plt.savefig('Plots/energy_per_pmt.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']

totalEnergy = df['totalEnergy_gasoline'] / 1000000 + df['totalEnergy_electricity'] / 1000000

totalPMT = df['PMT_car'].values
totalPMT += df['PMT_car_CAV'].values
totalPMT += df['PMT_car_RH'].values
totalPMT += df['PMT_car_RH_CAV'].values

totalVMT = df['VMT_car'].values
totalVMT += df['VMT_car_CAV'].values
totalVMT += df['VMT_car_RH'].values
totalVMT += df['VMT_car_RH_CAV'].values

height = totalEnergy / totalVMT

plt_e_pmt = plt.bar(x=xpos, height=height, color=colors['grey'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
ax = plt.gca()
ax.grid(False)
for ind in range(7):
    plt.text(xpos[ind], height[ind] + 0.025, names[ind], ha='center')
plt.ylabel('Energy per Light Duty Vehicle Mile (MJ/mi)')
plt.savefig('Plots/energy_per_vmt.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']

totalPMT = df['PMT_car'].values
totalPMT += df['PMT_car_CAV'].values
totalPMT += df['PMT_car_RH'].values
totalPMT += df['PMT_car_RH_CAV'].values

totalVMT = df['VMT_car'].values
totalVMT += df['VMT_car_CAV'].values
totalVMT += df['VMT_car_RH'].values
totalVMT += df['VMT_car_RH_CAV'].values

height = totalPMT / totalVMT

plt_e_pmt = plt.bar(x=xpos, height=height, color=colors['grey'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
ax = plt.gca()
ax.grid(False)
for ind in range(7):
    plt.text(xpos[ind], height[ind] + 0.01, names[ind], ha='center')
plt.ylabel('Mean Occupancy')
plt.savefig('Plots/occupancy.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))
populations = [612597, 612598, 612598, 755015, 755006, 755022, 754962]
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']

height_Transit = df['PMT_bus'].values / populations
height_Transit += df['PMT_ferry'].values / populations
height_Transit += df['PMT_rail'].values / populations
height_Transit += df['PMT_subway'].values / populations
height_Transit += df['PMT_tram'].values / populations
# height_Transit += df['PMT_cable_car'].values/50000
height_Car = df['PMT_car'].values / populations
height_Cav = df['PMT_car_CAV'].values / populations
height_RideHail = df['PMT_car_RH'].values / populations
height_RideHail += df['PMT_car_RH_CAV'].values / populations
# height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/50000
height_nonMotorized = df['PMT_walk'].values / populations
height_nonMotorized += df['PMT_bike'].values / populations
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_Cav

plt_transit = plt.bar(x=xpos, height=height_Transit, color=mode_colors['Transit'])
plt_rh = plt.bar(x=xpos, height=height_RideHail, bottom=height_Transit, color=mode_colors['Ride Hail'])
plt_cav = plt.bar(x=xpos, height=height_Cav, bottom=height_Transit + height_RideHail, color=mode_colors['CAV'])
plt_car = plt.bar(x=xpos, height=height_Car, bottom=height_Transit + height_RideHail + height_Cav,
                  color=mode_colors['Car'])
plt_nm = plt.bar(x=xpos, height=height_nonMotorized, bottom=height_Car + height_Transit + height_RideHail + height_Cav,
                 color=mode_colors['Bike'])
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
plt.legend((plt_transit, plt_rh, plt_cav, plt_car, plt_nm), ('Transit', 'Ride Hail', 'CAV', 'Car', 'NonMotorized'),
           labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind], height_all[ind] + 0.5, names[ind], ha='center')
# ax.set_ylim((0,160))
plt.ylabel('Person Miles Traveled per Capita')
plt.savefig('Plots/pmt_percapita_mode_reorder.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
'''
#%%
plt.figure(figsize=(5,6))
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']


height_Transit = df['PMT_bus'].values/50000
height_Transit += df['PMT_ferry'].values/50000
height_Transit += df['PMT_rail'].values/50000
height_Transit += df['PMT_subway'].values/50000
height_Transit += df['PMT_tram'].values/50000
height_Car = df['PMT_car'].values/50000
height_Cav = df['PMT_car_CAV'].values/50000
height_RideHail = df['PMT_car_RH'].values/50000
height_RideHail += df['PMT_car_RH_CAV'].values/50000
height_nonMotorized = df['PMT_walk'].values/50000
height_nonMotorized += df['PMT_bike'].values/50000
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_Cav

plt_car = plt.bar(x=xpos,height=height_Car,color=mode_colors['Car'])
plt_cav = plt.bar(x=xpos,height=height_Cav,bottom=height_Car,color=mode_colors['CAV'])
plt_transit = plt.bar(x=xpos,height=height_Transit,bottom=height_Car + height_Cav,color=mode_colors['Transit'])
plt_rh = plt.bar(x=xpos,height=height_RideHail,bottom=height_Transit + height_Car+ height_Cav,color=mode_colors['Ride Hail'])
plt_nm = plt.bar(x=xpos,height=height_nonMotorized,bottom= height_Car + height_Transit + height_RideHail+ height_Cav,color=mode_colors['Bike'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)
plt.legend((plt_car,plt_cav,plt_transit,plt_rh,plt_nm),('Car','CAV','Transit','Ridehail','NonMotorized'),labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 2.5,names[ind],ha='center')
#ax.set_ylim((0,160))
plt.ylabel('Person Miles Traveled (millions)')
plt.savefig('Plots/pmt_mode.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')
plt.clf()
'''
# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_Gas = df['totalEnergy_gasoline'].values * expansion_factor / 1000000000000
height_Electricity = df['totalEnergy_electricity'].values * expansion_factor / 1000000000000
height_all = height_Gas + height_Electricity

plt_g = plt.bar(x=xpos, height=height_Gas)
plt_e = plt.bar(x=xpos, height=height_Electricity, bottom=height_Gas)

plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
plt.legend((plt_g, plt_e), ('Gasoline', 'Electricity'), labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 5,names[ind],ha='center')
#ax.set_ylim((0,400))
plt.ylabel('Light duty vehicle energy use (TJ)')
plt.savefig('Plots/energy_source_2.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_Low = df['Low_VMT'].values * expansion_factor / 1000000
height_High = df['High_VMT'].values * expansion_factor / 1000000
height_CAV = df['CAV_VMT'].values * expansion_factor / 1000000
height_All = height_Low + height_High + height_CAV
height_RH_Empty = df['rh_empty_miles'].values * expansion_factor / 1000000
height_PV_Empty = df['VMT_cav_empty'].values * expansion_factor / 1000000

plt_Low = plt.bar(x=xpos, height=height_Low)
plt_High = plt.bar(x=xpos, height=height_High, bottom=height_Low)
plt_CAV = plt.bar(x=xpos, height=height_CAV, bottom=height_High + height_Low)
plt_empty_car = plt.bar(x=xpos, height=height_RH_Empty, hatch='///', fill=False)
plt_empty_cav = plt.bar(x=xpos, height=height_PV_Empty, bottom=height_High + height_Low, hatch='///', fill=False)
plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)
empty = mpatches.Patch(facecolor='white', label='The white data', hatch='///')
plt.legend((plt_Low, plt_High, plt_CAV), ('Low', 'High', 'CAV', 'Empty'), labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5),
           frameon=False)

ax = plt.gca()
plt.grid(b=None)
# ax.set_ylim((0,10))
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind], height_All[ind] + 2, names[ind], ha='center')
ax.set_ylim((0, 400))
plt.ylabel('Light duty vehicle miles traveled (millions)')
plt.savefig('Plots/vmt_tech_3.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()

# %%
plt.figure(figsize=(6, 3.5))
MEP = [380, 467, 477, 569, 617, 554, 667]
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']

plt_MEP= plt.bar(x=xpos, height=MEP)

plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)


ax = plt.gca()
plt.grid(b=None)
# ax.set_ylim((0,10))
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind], MEP[ind] + 4, names[ind], ha='center')
ax.set_ylim((0, 700))
plt.ylabel('MEP')
plt.savefig('Plots/MEP.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()



#%%



df['MEP'] = MEP
key = 'MEP'

A_LT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'a') & (df['Technology'] == 'Low Tech')]], axis=0,
                 ignore_index=True)
A_HT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'a') & (df['Technology'] == 'High Tech')]],
                 axis=0, ignore_index=True)
B_LT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'b') & (df['Technology'] == 'Low Tech')]], axis=0,
                 ignore_index=True)
B_HT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'b') & (df['Technology'] == 'High Tech')]],
                 axis=0, ignore_index=True)
C_LT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'c') & (df['Technology'] == 'Low Tech')]], axis=0,
                 ignore_index=True)
C_HT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'c') & (df['Technology'] == 'High Tech')]],
                 axis=0, ignore_index=True)

fig, ax = plt.subplots(figsize=(5,6))

# ax.plot(x,B_LT['motorizedVehicleMilesTraveled_total'])
# ax.plot(x,B_HT['motorizedVehicleMilesTraveled_total'])
# ax.plot(x,C_LT['motorizedVehicleMilesTraveled_total'])
# ax.plot(x,C_HT['motorizedVehicleMilesTraveled_total'])
line_alt = ax.plot(xa, A_LT[key] , color='black', marker='o')
line_aht = ax.plot(xa, A_HT[key] , color='black', marker='v')
line_lt = ax.plot(xbc, C_LT[key] , xbc, B_LT[key] , color='black', marker='o')
line_ht = ax.plot(xbc, C_HT[key] , xbc, B_HT[key] , color='black', marker='v')
area_A = ax.fill_between(xa, A_LT[key] , A_HT[key] , alpha=0.5)
area_C = ax.fill_between(xbc, C_LT[key] , C_HT[key] , alpha=0.5)
area_B = ax.fill_between(xbc, B_LT[key] , B_HT[key] , alpha=0.5)
tech_legend = plt.legend((area_A, area_B, area_C),
                         ('Sharing is Caring', 'Technology Takeover', "All About Me"), loc=2)
ax.add_artist(tech_legend)
ax.axis('on')
circles = mlines.Line2D([], [], color='black', marker='o',
                        markersize=5, label='Low Tech')
triangles = mlines.Line2D([], [], color='black', marker='v',
                          markersize=5, label='High Tech')
plt.legend(handles=[circles, triangles], loc=4)
plt.xlabel('Year')
plt.ylabel('MEP')
# ax.spines['bottom'].set_color('green')
# ax.spines['left'].set_color('green')
plt.savefig('Plots/MEP_lineplot.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()








df['totalEnergy'] = df['totalEnergy_gasoline'] + df['totalEnergy_electricity']
key = 'totalEnergy'

A_LT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'a') & (df['Technology'] == 'Low Tech')]], axis=0,
                 ignore_index=True)
A_HT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'a') & (df['Technology'] == 'High Tech')]],
                 axis=0, ignore_index=True)
B_LT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'b') & (df['Technology'] == 'Low Tech')]], axis=0,
                 ignore_index=True)
B_HT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'b') & (df['Technology'] == 'High Tech')]],
                 axis=0, ignore_index=True)
C_LT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'c') & (df['Technology'] == 'Low Tech')]], axis=0,
                 ignore_index=True)
C_HT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'c') & (df['Technology'] == 'High Tech')]],
                 axis=0, ignore_index=True)

fig, ax = plt.subplots(figsize=(5,6))

# ax.plot(x,B_LT['motorizedVehicleMilesTraveled_total'])
# ax.plot(x,B_HT['motorizedVehicleMilesTraveled_total'])
# ax.plot(x,C_LT['motorizedVehicleMilesTraveled_total'])
# ax.plot(x,C_HT['motorizedVehicleMilesTraveled_total'])
line_alt = ax.plot(xa, A_LT[key] * expansion_factor / 1000000000000, color='black', marker='o')
line_aht = ax.plot(xa, A_HT[key] * expansion_factor / 1000000000000, color='black', marker='v')
line_lt = ax.plot(xbc, C_LT[key] * expansion_factor / 1000000000000, xbc, B_LT[key] * expansion_factor / 1000000000000, color='black', marker='o')
line_ht = ax.plot(xbc, C_HT[key] * expansion_factor / 1000000000000, xbc, B_HT[key] * expansion_factor / 1000000000000, color='black', marker='v')
area_A = ax.fill_between(xa, A_LT[key] * expansion_factor / 1000000000000, A_HT[key] * expansion_factor / 1000000000000, alpha=0.5)
area_C = ax.fill_between(xbc, C_LT[key] * expansion_factor / 1000000000000, C_HT[key] * expansion_factor / 1000000000000, alpha=0.5)
area_B = ax.fill_between(xbc, B_LT[key] * expansion_factor / 1000000000000, B_HT[key] * expansion_factor / 1000000000000, alpha=0.5)
tech_legend = plt.legend((area_A, area_B, area_C),
                         ('Sharing is Caring', 'Technology Takeover', "All About Me"), loc=3)
ax.add_artist(tech_legend)
ax.axis('on')
circles = mlines.Line2D([], [], color='black', marker='o',
                        markersize=5, label='Low Tech')
triangles = mlines.Line2D([], [], color='black', marker='v',
                          markersize=5, label='High Tech')
plt.legend(handles=[circles, triangles], loc=1)
plt.xlabel('Year')
plt.ylabel('Total light duty vehicle energy (TJ)')
# ax.spines['bottom'].set_color('green')
# ax.spines['left'].set_color('green')
plt.savefig('Plots/Energy.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
'''
xa = np.array([2019, 2025])
xbc = np.array([2019, 2040])
key = 'totalMiles'

A_LT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'a') & (df['Technology'] == 'Low Tech')]], axis=0,
                 ignore_index=True)
A_HT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'a') & (df['Technology'] == 'High Tech')]],
                 axis=0, ignore_index=True)
B_LT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'b') & (df['Technology'] == 'Low Tech')]], axis=0,
                 ignore_index=True)
B_HT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'b') & (df['Technology'] == 'High Tech')]],
                 axis=0, ignore_index=True)
C_LT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'c') & (df['Technology'] == 'Low Tech')]], axis=0,
                 ignore_index=True)
C_HT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'c') & (df['Technology'] == 'High Tech')]],
                 axis=0, ignore_index=True)

fig, ax = plt.subplots()
# ax.plot(x,B_LT['motorizedVehicleMilesTraveled_total'])
# ax.plot(x,B_HT['motorizedVehicleMilesTraveled_total'])
# ax.plot(x,C_LT['motorizedVehicleMilesTraveled_total'])
# ax.plot(x,C_HT['motorizedVehicleMilesTraveled_total'])
line_alt = ax.plot(xa, A_LT[key] * expansion_factor / 1000000, color='black', marker='o')
line_aht = ax.plot(xa, A_HT[key] * expansion_factor / 1000000, color='black', marker='v')
line_lt = ax.plot(xbc, C_LT[key] * expansion_factor / 1000000, xbc, B_LT[key] * expansion_factor / 1000000, color='black', marker='o')
line_ht = ax.plot(xbc, C_HT[key] * expansion_factor / 1000000, xbc, B_HT[key] * expansion_factor / 1000000, color='black', marker='v')
area_A = ax.fill_between(xa, A_LT[key] * expansion_factor / 1000000, A_HT[key] * expansion_factor / 1000000, alpha=0.5)
area_C = ax.fill_between(xbc, C_LT[key] * expansion_factor / 1000000, C_HT[key] * expansion_factor / 1000000, alpha=0.5)
area_B = ax.fill_between(xbc, B_LT[key] * expansion_factor / 1000000, B_HT[key] * expansion_factor / 1000000, alpha=0.5)
tech_legend = plt.legend((area_A, area_B, area_C),
                         ('A: Sharing is Caring', 'B: Technology takes over', "C: We're in trouble"), loc=4)
ax.add_artist(tech_legend)
ax.axis(True)
circles = mlines.Line2D([], [], color='black', marker='o',
                        markersize=5, label='Low Tech')
triangles = mlines.Line2D([], [], color='black', marker='v',
                          markersize=5, label='High Tech')
plt.legend(handles=[circles, triangles], loc=2)
plt.xlabel('Year')
plt.ylabel('Light duty vehicle miles traveled (millions)')
# ax.spines['bottom'].set_color('green')
# ax.spines['left'].set_color('green')
plt.savefig('Plots/vmt.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
'''
xa = np.array([2019, 2025])
xbc = np.array([2019, 2040])
key = 'MPH'

A_LT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'a') & (df['Technology'] == 'Low Tech')]], axis=0,
                 ignore_index=True)
A_HT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'a') & (df['Technology'] == 'High Tech')]],
                 axis=0, ignore_index=True)
B_LT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'b') & (df['Technology'] == 'Low Tech')]], axis=0,
                 ignore_index=True)
B_HT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'b') & (df['Technology'] == 'High Tech')]],
                 axis=0, ignore_index=True)
C_LT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'c') & (df['Technology'] == 'Low Tech')]], axis=0,
                 ignore_index=True)
C_HT = pd.concat([df[df['Scenario'] == 'base'], df[(df['Scenario'] == 'c') & (df['Technology'] == 'High Tech')]],
                 axis=0, ignore_index=True)

fig, ax = plt.subplots()
# ax.plot(x,B_LT['motorizedVehicleMilesTraveled_total'])
# ax.plot(x,B_HT['motorizedVehicleMilesTraveled_total'])
# ax.plot(x,C_LT['motorizedVehicleMilesTraveled_total'])
# ax.plot(x,C_HT['motorizedVehicleMilesTraveled_total'])
line_alt = ax.plot(xa, A_LT[key], color='black', marker='o')
line_aht = ax.plot(xa, A_HT[key], color='black', marker='v')
line_lt = ax.plot(xbc, C_LT[key], xbc, B_LT[key], color='black', marker='o')
line_ht = ax.plot(xbc, C_HT[key], xbc, B_HT[key], color='black', marker='v')
area_A = ax.fill_between(xa, A_LT[key], A_HT[key], alpha=0.5)
area_C = ax.fill_between(xbc, C_LT[key], C_HT[key], alpha=0.5, hatch='-')
area_B = ax.fill_between(xbc, B_LT[key], B_HT[key], alpha=0.5, hatch='|')
tech_legend = plt.legend((area_A, area_B, area_C),
                         ('A: Sharing is Caring', 'B: Technology takes over', "C: We're in trouble"), loc=3)
ax.add_artist(tech_legend)
ax.axis(True)
circles = mlines.Line2D([], [], color='black', marker='o',
                        markersize=5, label='Low Tech')
triangles = mlines.Line2D([], [], color='black', marker='v',
                          markersize=5, label='High Tech')
plt.legend(handles=[circles, triangles], loc=4)
plt.xlabel('Year')
plt.ylabel('Road network average speed (MPH)')
# ax.spines['bottom'].set_color('green')
# ax.spines['left'].set_color('green')
# ax.set_ylim((16,32))
plt.savefig('Plots/Speed.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()
#%%

veh_count = pd.read_csv('vehicle_counts2.csv')
plt.figure(figsize=(6, 3.5))
xpos = [1, 2.5, 3.5, 5, 6, 7.5, 8.5]
names = ['Base', 'Low', 'High', 'Low', 'High', 'Low', 'High']
height_PN = veh_count['Personal_NonCAV_Count'].values * expansion_factor / 1000000
height_PC = veh_count['Personal_CAV_Count'].values * expansion_factor / 1000000
height_RN = veh_count['RH_NonCAV_Count'].values * expansion_factor / 1000000
height_RC = veh_count['RH_CAV_Count'].values * expansion_factor / 1000000
height_All = height_PN + height_PC + height_RN + height_RC

plt_PN = plt.bar(x=xpos, height=height_PN)
plt_PC = plt.bar(x=xpos, height=height_PC, bottom=height_PN)
plt_RN = plt.bar(x=xpos, height=height_RN, bottom=height_PC + height_PN)
plt_RC = plt.bar(x=xpos, height=height_RC, bottom=height_PC + height_PN + height_RN)

plt.xticks([1, 3, 5.5, 8], ['Base', 'Sharing is Caring', 'Technology Takeover', "All About Me"], rotation=10)

plt.legend((plt_PN, plt_PC, plt_RN, plt_RC), ('Personal Non-CAV', 'Personal CAV', 'Ride Hail Non-CAV', 'Ride Hail CAV'), labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5),
           frameon=False)

ax = plt.gca()
plt.grid(b=None)
# ax.set_ylim((0,10))
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind], height_All[ind] + 0.1, names[ind], ha='center')
#ax.set_ylim((0, 400))
plt.ylabel('Number of Vehicles (millions)')
plt.savefig('Plots/nvehs.png', transparent=True, bbox_inches='tight', dpi=200, facecolor='white')
plt.clf()