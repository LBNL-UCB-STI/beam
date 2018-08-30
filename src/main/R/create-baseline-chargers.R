

counties <- readShapePoly('~/Dropbox/ucb/vto/MATSimPEV/spatial-data/ca-counties/ca-counties.shp',proj4string=CRS("+proj=longlat +datum=WGS84"))
zips <- readShapePoly('~/Dropbox/ucb/vto/MATSimPEV/spatial-data/ca-zips/sf-bay-area-zips.shp',proj4string=CRS("+proj=longlat +datum=WGS84"))
sf.county.inds <- counties$NAME %in% c('Alameda','San Mateo','Sonoma','Marin','Contra Costa','Napa','Solano','Santa Clara','San Francisco')
zips.in.sf <- which(!is.na(over(zips,counties[sf.county.inds,])$NAME))

sf.zips <- zips[zips.in.sf,]
sf.counties <- counties[sf.county.inds,]

afdc <- data.table(read.csv('~/Dropbox/ucb/vto/MATSimPEV/misc/chargings-stations/alt-fuel-data-center-2016-08-11.csv'))
afdc.in.sf <- over(SpatialPoints(afdc[,list(Longitude,Latitude)],proj4string=CRS(proj4string(sf.zips))),sf.zips)
afdc.in.sf <- which(!is.na(afdc.in.sf[,1]))
afdc <- afdc[afdc.in.sf]


beam <- afdc[,list(longitude=Longitude,latitude=Latitude,l1=EV.Level1.EVSE.Num,l2=EV.Level2.EVSE.Num,l3=EV.DC.Fast.Count,network=EV.Network,plugTypes=EV.Connector.Types)]

beam[,id:=1:nrow(beam)]

pols <- data.table(read.csv('~/Dropbox/ucb/vto/MATSimPEV/model-inputs/sf-bay/charging-policies.csv'))

beam[,network.mapped:=ifelse(network=='ChargePoint Network','ChargingSitePolicyChargePoint',ifelse(network=='Blink Network','ChargingSitePolicyBlink',ifelse(network=='Tesla','ChargingSitePolicyTesla',ifelse(network=='eVgo Network','ChargingSitePolicyEVGo','ChargingSitePolicyOther'))))]

beam <- join.on(beam,pols,'network.mapped','className','id','policy.')
beam[,policyID:=policy.id]

# In the future it may be necessary to match chargers with net operators (which define how vehicles are charged) 
nets <- data.table(read.csv('~/Dropbox/ucb/vto/MATSimPEV/model-inputs/sf-bay/charging-network-operators.csv'))

beam[,networkOperatorID:=1] # dumb charging

# Write the sites
#write.csv(beam[,list(id,latitude,longitude,policyID,networkOperatorID)],'~/Dropbox/ucb/vto/MATSimPEV/model-inputs/sf-bay/charging-sites.csv',row.names=F)

plot(sf.counties)
plot(SpatialPoints(beam[,list(longitude,latitude)]),add=T,pch=beam$policyID,col=beam$policyID)
legend('bottomleft',legend=pols$className,col=pols$id,pch=pols$id)

beam.m <- melt(beam,measure.vars=pp('l',1:3))
beam.m <- beam.m[!is.na(value)]

setkey(beam.m,network,variable,plugTypes)
u(beam.m)

fix.types <- data.table(net=c(rep('ChargingSitePolicyOther',3),
                              rep('ChargingSitePolicyBlink',3),
                              rep('ChargingSitePolicyChargePoint',3),
                              rep('ChargingSitePolicyEVGo',3),
                              rep('ChargingSitePolicyTesla',3)),
                        level=rep(pp('l',1:3),5),
                        type=c('J-1772-1','J-1772-2','mixed',
                               'J-1772-1','J-1772-2','CHAdeMO',
                               'J-1772-1','J-1772-2','mixed',
                               'J-1772-1','J-1772-2','mixed',
                               'Tesla-1','Tesla-2','Tesla-3')
                      )

beam.m <- join.on(beam.m,fix.types,c('network.mapped','variable'),c('net','level'),'type','fix.')
beam.m[,contains.chad:=F]
beam.m[grep('CHADEMO',plugTypes),contains.chad:=T]
beam.m[fix.type=='mixed' & contains.chad==T,fix.type:='CHAdeMO']
beam.m[fix.type=='mixed' & contains.chad==F,fix.type:='SAE-Combo-3']

plug.types <- data.table(read.csv('~/Dropbox/ucb/vto/MATSimPEV/model-inputs/sf-bay/charging-plug-types.csv'))

beam.m <- join.on(beam.m,plug.types,'fix.type','plugTypeName','id','plug.type.')

points <- beam.m[,list(siteID=id,plugTypeID=plug.type.id,numPlugs=rep(1,value),numParkingSpacesPerPoint=(rpois(1,0.2)+1)*2),by=c('id','variable')]
points[,id:=1:nrow(points)]
#write.csv(points[,list(id,siteID,plugTypeID,numPlugs,numParkingSpacesPerPoint)],'~/Dropbox/ucb/vto/MATSimPEV/model-inputs/sf-bay/charging-points.csv',row.names=F)
