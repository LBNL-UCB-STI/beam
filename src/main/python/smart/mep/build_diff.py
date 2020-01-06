import pandas as pd

prefix = 'beam_dec13_'
suffix = '_mep.csv'

A_BAU = '2025_a_bau'
A_VTO = '2025_a_vto'
B_BAU = '2040_b_bau'
B_VTO = '2040_b_vto'
C_BAU = '2040_c_bau'
C_VTO = '2040_c_vto'

Base_2010 = '2010_base'
Base_2025_Short_BAU = '2025_base_short_bau'
Base_2025_Short_VTO = '2025_base_short_vto'
Base_2040_Long_BAU = '2040_base_long_bau'
Base_2040_Long_VTO = '2040_base_long_vto'

diffs = [
    [A_BAU, Base_2010],
    [A_BAU, Base_2025_Short_BAU],
    [A_VTO, Base_2010],
    [A_VTO, Base_2025_Short_VTO],
    [B_BAU, Base_2010],
    [B_BAU, Base_2040_Long_BAU],
    [B_VTO, Base_2010],
    [B_VTO, Base_2040_Long_VTO],
    [C_BAU, Base_2010],
    [C_BAU, Base_2040_Long_BAU],
    [C_VTO, Base_2010],
    [C_VTO, Base_2040_Long_VTO],
    [C_VTO, B_VTO],
    [C_BAU, B_BAU]
]

for pair in diffs:
    baseScenarioName = pair[1]
    scenarioName = pair[0]
    diffName = 'diff_' + scenarioName + '__' + baseScenarioName + '.csv'
    print('processing', diffName)
    baseScenario = pd.read_csv(prefix + baseScenarioName + suffix, sep=',')
    scenarioTwo = pd.read_csv(prefix + scenarioName + suffix, sep=',')
    joinedTable = pd.merge(baseScenario, scenarioTwo,  how='inner', left_on=['lon','lat'], right_on = ['lon','lat'])
    joinedTable=joinedTable.loc[~((joinedTable['mep_x']==joinedTable['mep_y']) & (joinedTable['mep_x']==0)),:]
    joinedTable['mep']=joinedTable['mep_y']-joinedTable['mep_x']
    joinedTable=joinedTable.loc[:, ['lat','lon','mep']]
    joinedTable.to_csv(diffName)
    print('done',len(joinedTable))