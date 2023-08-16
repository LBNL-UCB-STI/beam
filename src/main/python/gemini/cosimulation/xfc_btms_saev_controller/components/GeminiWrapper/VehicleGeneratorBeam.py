import pandas as pd
import components

class VehicleGeneratorBeam:
# this is mainly a wrapper not doing anything interesting
    def __init__(self) -> None:
        pass

    def generateVehicle(self, VehicleId, VehicleType, VehicleArrival, VehicleDesEnd, VehicleEngyInKwh, VehicleDesEngyInKwh, VehicleMaxEngy, VehicleMaxPower) -> components.Vehicle:

        Vehicle = components.Vehicle(VehicleId, VehicleType, VehicleArrival, VehicleDesEnd, VehicleEngyInKwh, VehicleDesEngyInKwh, VehicleMaxEngy, VehicleMaxPower, ParkingZoneId=False)
        # TODO: do we need to add plug id informations here? i.e. for different charging power at different plugs.

        return Vehicle