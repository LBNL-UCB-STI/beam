# add SimBrokerDummy
# add vehicleGeneratorBeam #conversion to kWh is already done
# open ResultWriter and add directory

# chargingStation information with infrastructure file
# TODO: need infrastructure file / information

# create charging station object

# The following only for MPC
if isinstance(chargingStationClass, components.ChaDepMpcBase):
    pass
    # generatre predictions TODO: need old result file for this
    # perform btms size optimization
    # save btms size optimization results
    # create optimal day ahead plan
    # save optimal day ahead plan (TODO)

# now we should start the simulation cycle,
# which is done in the following way:
