import string
from components import ChaDepParent
import numpy.random as npr
from typing import List

class DermsDummy:

    def __init__(self, chargingStations: List[ChaDepParent]) -> None:
        self.data = {}
        '''
        convention for data:
        'chargingStationId': [GridPowerMax_Nom, GridPowerLower last step, GridPowerUpper last step, Desired charging power last step]
        '''
        for x in chargingStations:
            self.data[x.ChargingStationId] = [x.GridPowerMax_Nom, -x.GridPowerMax_Nom, x.GridPowerMax_Nom, x.GridPowerMax_Nom * 0.25]
        
    def input(self, chargingStationId: string, desiredChargingPower: float):
        self.data[chargingStationId][3] = desiredChargingPower

    def output(self, chargingStationId: string):
        # TODO derms turned off, as chBaMaxpower is multiplied by 0.35 in chadeparent so far
        variation = 1/0.35 #+ 0.05 * npr.randn() # 68% of the deviations lie within the 1sigma band of 5%
        # TODO adjust derms behavior
        GridPowerLower = 2 * -1 * variation * self.data[chargingStationId][0]
        GridPowerUpper = 2 * variation * self.data[chargingStationId][0]
        return GridPowerLower, GridPowerUpper