import string
from components import ChaDepParent
import numpy.random as npr
from typing import List

class PhySimDummy:

    def __init__(self, chargingStations: List[ChaDepParent]) -> None:
        self.data = {} # dict to store SOC of the CES
        '''convention for dictionary:
            'chargingStation1': [Net Load last step, CES charging power command last step, SOC after step, CES size]
            '''
        for x in chargingStations:
            self.data[x.ChargingStationId] = [float("NaN"), float("NaN"), x.BtmsEn/x.BtmsSize, x.BtmsSize]
        pass

    def input(self, chargingStationId: string, netLoad: float, CesPower: float, timestep: float):
        self.data[chargingStationId][0] = netLoad
        self.data[chargingStationId][1] = CesPower
        self.data[chargingStationId][2] = self.data[chargingStationId][2] + timestep/3600 * CesPower * (1 + 0.02 * npr.randn()) / self.data[chargingStationId][3] # 68% are in the 1sigma band (2%), normalized with ces size

    def output(self,chargingStationId: string):
        # return SOC
        return float("nan") #self.data[chargingStationId][2]