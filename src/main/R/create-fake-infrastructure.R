load.libraries(c('XML','rgdal'))

sites.orig <- data.table(read.csv(pp(matsim.shared,"model-inputs/development/charging-sites.csv")))
sites <- copy(sites.orig)
coordinates(sites) <- c("longitude", "latitude")
proj4string(sites) <- CRS("+init=epsg:4326") 
sites <- data.table(coordinates(spTransform(sites,CRS("+init=epsg:26910"))))
sites.orig[,':='(longitude=sites$longitude,latitude=sites$latitude)]


##############################################################
# THE FOLLOWING CREATES QUASI-INFINITE PORTFOLIO, ONE FOR
# EVERY LINK IN NETWORK FOR EACH PLUG TYPE
##############################################################
load(file=pp(matsim.shared,"model-inputs/development/network_SF_Bay_detailed.Rdata"))
link.nodes <- join.on(links,nodes,'from','id',c('x','y'),'from.')
link.nodes <- join.on(link.nodes,nodes,'to','id',c('x','y'),'to.')
plug.types <- data.table(read.csv(pp(matsim.shared,"model-inputs/sf-bay/charging-plug-types.csv")))[!plugTypeName%in%c('J-1772-1','SAE-Combo-1','Tesla-1')]
the.sites <- link.nodes[,list(id=1:nrow(link.nodes),x=(to.x+from.x)/2,y=(to.y+from.y)/2,policyID=6,networkOperatorID=1)]
coordinates(the.sites) <- c("x", "y")
proj4string(the.sites) <- CRS("+init=epsg:26910") 
the.sites.latlon <- data.table(coordinates(spTransform(the.sites,CRS("+init=epsg:4326"))))
the.sites <- link.nodes[,list(id=1:nrow(link.nodes),policyID=6,networkOperatorID=1)]
the.sites[,':='(latitude=the.sites.latlon$y,longitude=the.sites.latlon$x)]
# Downsample to 100k
the.sites <- the.sites[sample(nrow(the.sites),100e3)]
write.csv(the.sites,pp(matsim.shared,"model-inputs/sf-bay/charging-sites-abundant.csv"),row.names=F,quote=F)
the.points <- the.sites[,list(plugTypeID=plug.types$id),by='id']
the.points[,':='(siteID=id,id=1:nrow(the.points),numPlugs=1,numParkingSpacesPerPoint=10)]
write.csv(the.points,pp(matsim.shared,"model-inputs/sf-bay/charging-points-abundant.csv"),row.names=F,quote=F)

##############################################################
# THE FOLLOWING ADD JUST TO WHERE ACTIVITIES ARE
##############################################################
plug.types <- data.table(read.csv(pp(matsim.shared,"model-inputs/development/charging-plug-types.csv")))
plug.types[plugTypeName=='CHAdeMO',frac:=0.2]
plug.types[plugTypeName=='J-1772-2',frac:=0.5]
plug.types[plugTypeName=='SAE-Combo-1',frac:=0.2]
plug.types[plugTypeName=='J-1772-1',frac:=0.1]
points.orig <- data.table(read.csv(pp(matsim.shared,"model-inputs/development/charging-points.csv")))
sample.type <- '3_agents'
#for(sample.type in c('1_agent','3_agents','10_agents')){ #,'10k','100k')){
for(sample.type in c('1k','10k')){
  data <- xmlParse(pp(matsim.shared,"model-inputs/development/mtc_plans_sample_",sample.type,".xml"))
  plans <- xmlToList(data)
  #if(length(plans)>nrow(sites)){
    #plans <- plans[1:floor(nrow(sites.orig)/1)]
  #}

  activity.locs <- data.table(as.data.frame(matrix(unlist(lapply(plans,function(ll){ lapply(ll$plan,function(lll){ if('x' %in% names(lll)){ as.numeric(c(lll['x'],lll['y'])) }else{ c()} }) })),ncol=2,byrow=T)))
  activity.locs[,':='(longitude=V1,latitude=V2,V1=NULL,V2=NULL)]
  #coordinates(activity.locs) <- c("lon", "lat")
  #proj4string(activity.locs) <- CRS("+init=epsg:26910") 
  #activity.locs <- data.table(coordinates(spTransform(activity.locs,CRS("+init=epsg:4326"))))

  sites <- rbindlist(list(copy(sites.orig)[NA],data.table(id=1:floor(nrow(activity.locs)/2),latitude=0,longitude=0,policyID=1,networkOperatorID=1)),fill=T)[!is.na(id)]
  activ.inds <- sample(1:nrow(activity.locs),nrow(sites))
  sites[,longitude:=activity.locs[activ.inds,longitude]+runif(1,-500,500)]
  sites[,latitude:=activity.locs[activ.inds,latitude]+runif(1,-500,500)]
  sites.wgs <- copy(sites)
  coordinates(sites.wgs) <- c("longitude", "latitude")
  proj4string(sites.wgs) <- CRS("+init=epsg:26910") 
  sites.wgs <- data.table(coordinates(spTransform(sites.wgs,CRS("+init=epsg:4326"))))
  sites[,':='(longitude=sites.wgs$longitude,latitude=sites.wgs$latitude)]
  write.csv(sites,pp(matsim.shared,"model-inputs/development/charging-sites-",sample.type,".csv"),row.names=F,quote=F)

  points <- rbindlist(list(copy(points.orig)[NA],data.table(id=1:nrow(sites),siteID=1:nrow(sites),
                plugTypeID=sample(na.omit(plug.types)$id,nrow(sites),replace=T,prob=na.omit(plug.types)$frac),
                numPlugs=rpois(nrow(sites),0.5)+1,numParkingSpacesPerPoint=(rpois(nrow(sites),0.25)+1)*2)),fill=T)[!is.na(id)]
  write.csv(points,pp(matsim.shared,"model-inputs/development/charging-points-",sample.type,".csv"),row.names=F,quote=F)
}


# Checking
#ggplot(activity.locs,aes(x=lon,y=lat))+geom_point()+geom_point(aes(x=longitude,y=latitude,colour='red'),data=sites)
ggplot(sites,aes(x=longitude,y=latitude))+geom_point()+geom_point(colour='red',data=activity.locs)

# For testing we need everything in EPSG:26910
