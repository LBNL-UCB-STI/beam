
load.libraries(c('fields','sp','maptools','XML','R.utils','stringr','rgdal','sp'))

########################################
# Thought Experiments
########################################
out.dirs <- list('always-real'='/Users/critter/Documents/beam/pev/0-always-on-arrival-sf-bay_2016-09-15_11-22-12/ITERS/it.0/',
                 'always-inf'='/Users/critter/Dropbox/ucb/vto/beam-developers/outputs/0-abundant-sf-bay_2016-09-15_22-18-42/ITERS/it.0/',
  'uniform-random'='/Users/critter/Documents/beam/pev/0-uniform-random-sf-bay_2016-09-15_13-46-11/ITERS/it.0/',
  'nested-logit'='/Users/critter/Documents/beam/pev/0-nested-logit-sf-bay_2016-09-15_14-17-03/ITERS/it.0/')
  #'nested-logit-full'='/Users/critter/Documents/beam/pev/0-nested-logit-sf-bay_2016-09-15_14-17-03/ITERS/it.0/')
  #'nested-logit-hetero'='/Users/critter/Documents/beam/pev/0-nested-logit-sf-bay_2016-09-11_23-48-21/ITERS/it.0/')

########################################
# NL Calibration
########################################
out.dirs <- list(
                 'nl1'='/Users/critter/Documents/beam/pev/sf-bay_2016-09-20_09-32-54/ITERS/it.0/',
                 'nl2'='/Users/critter/Documents/beam/pev/sf-bay_2016-09-20_11-54-57/ITERS/it.0/',
                 'nl3'='/Users/critter/Documents/beam/pev/sf-bay_2016-09-20_13-58-50/ITERS/it.0/',
                 'nl4'='/Users/critter/Documents/beam/pev/sf-bay_2016-09-20_16-32-58/ITERS/it.0/',
                 'nl5'='/Users/critter/Documents/beam/pev/sf-bay_2016-09-20_18-02-51/ITERS/it.0/',
                 'nl6'='/Users/critter/Documents/beam/pev/sf-bay_2016-09-20_19-45-23/ITERS/it.0/',
                 'nl7'='/Users/critter/Documents/beam/pev/sf-bay_2016-09-20_21-09-41/ITERS/it.0/',
                 'nl8'='/Users/critter/Documents/beam/pev/sf-bay-nl8_2016-09-20_22-26-55/ITERS/it.0/',
                 'nl9'='/Users/critter/Documents/beam/pev/sf-bay-nl9_2016-09-20_23-09-27/ITERS/it.0/',
                 'nl10'='/Users/critter/Documents/beam/pev/sf-bay-nl10_2016-09-20_23-56-48/ITERS/it.0/',
                 'nl11'='/Users/critter/Documents/beam/pev/sf-bay-nl11_2016-09-21_00-45-30/ITERS/it.0/',
                 'nl12'='/Users/critter/Documents/beam/pev/sf-bay-nl12_2016-09-21_01-34-48/ITERS/it.0/',
                 'nl13'='/Users/critter/Documents/beam/pev/sf-bay-nl13_2016-09-21_02-21-08/ITERS/it.0/'
                 )
                 #'nl1'='/Users/critter/Documents/beam/pev/sf-bay_2016-09-20_09-32-54/ITERS/it.0/',
# for report
out.dirs <- list(
                 #'Iteration 2'='/Users/critter/Documents/beam/pev/sf-bay_2016-09-20_13-58-50/ITERS/it.0/',
                 #'Iteration 3'='/Users/critter/Documents/beam/pev/sf-bay_2016-09-20_19-45-23/ITERS/it.0/',
                 'Iteration 1'='/Users/critter/Documents/beam/pev/old-calibration/sf-bay-nl13_2016-09-21_02-21-08/ITERS/it.0/',
                 'Iteration Final'='/Users/critter/Documents/beam/pev/old-calibration/sf-bay-nl10_2016-09-20_23-56-48/ITERS/it.0/'
                 )

#nl4 intercept 1.8, cost -2.75
#nl5 intercept 2.0, cost -2.9
#nl6 intercept 2.75, cost -3.1
#nl7 intercept 4.0, cost -3.5

#out.dir <- 'sf-bay_2016-09-20_11-54-57'
#out.dirs <- list('nlX'=pp('/Users/critter/Documents/beam/pev/',out.dir,'/ITERS/it.0/'))
scens <- names(out.dirs)

scen <- scens[1]
ev <- list()
for(scen in scens){
  out.dir <- out.dirs[[scen]]
  if(file.exists(pp(out.dir,'run0.0.events.Rdata'))){
    my.cat(out.dir)
    if(exists('df'))rm('df')
    load(pp(out.dir,'run0.0.events.Rdata'),verb=T)
    df$scenario <- scen
    ev[[length(ev)+1]] <- data.table(df)
    rm('df')
  }
}
ev <- rbindlist(ev,use.names=T,fill=T)
ev[,hr:=as.numeric(time)/3600]
ev[,hour:=floor(hr)]

#parse.config <- function(out.dir,the.file){
  #if(substr(the.file,str_length(the.file)-2, str_length(the.file)) == '.gz'){
    #gunzip(the.file)
    #the.file <- substr(the.file,1,str_length(the.file)-3)
  #}
  #return(xmlParse(the.file))
#}
#config <- parse.config(out.dir,pp(out.dir,'../../run0.output_config.xml'))
#module.names <- xpathSApply(config,'/config/module', xmlGetAttr,'name')

person.attr <- data.table(read.csv('~/Dropbox/ucb/vto/beam-developers/model-inputs/sf-bay/person-attributes.csv'))
veh.types <- data.table(read.csv('~/Dropbox/ucb/vto/beam-developers/model-inputs/sf-bay/vehicle-types.csv'))
ch.types <- data.table(read.csv('~/Dropbox/ucb/vto/beam-developers/model-inputs/sf-bay/charging-plug-types.csv'))
sites <- data.table(read.csv('~/Dropbox/ucb/vto/beam-developers/model-inputs/sf-bay/charging-sites.csv'))
pols <- data.table(read.csv('~/Dropbox/ucb/vto/beam-developers/model-inputs/sf-bay/charging-policies.csv'))
ch.types[,plugTypeName:=tolower(plugTypeName)]
points <- data.table(read.csv('~/Dropbox/ucb/vto/beam-developers/model-inputs/sf-bay/charging-points.csv'))
points <- join.on(points,ch.types,'plugTypeID','id',c('plugTypeName','chargingPowerInKW'))
points[,dc.fast:=chargingPowerInKW>20]
counties <- readShapePoly('~/Dropbox/ucb/vto/beam-developers/spatial-data/ca-counties/ca-counties.shp',proj4string=CRS("+proj=longlat +datum=WGS84"))
zips <- readShapePoly('~/Dropbox/ucb/vto/beam-developers/spatial-data/ca-zips/sf-bay-area-zips.shp',proj4string=CRS("+proj=longlat +datum=WGS84"))
sf.county.inds <- counties$NAME %in% c('Alameda','San Mateo','Sonoma','Marin','Contra Costa','Napa','Solano','Santa Clara','San Francisco')
zips.in.sf <- which(!is.na(over(zips,counties[sf.county.inds,])$NAME))
sf.zips <- spTransform(zips[zips.in.sf,],CRS("+proj=longlat +datum=WGS84"))
sf.counties <- spTransform(counties[sf.county.inds,],CRS("+proj=longlat +datum=WGS84"))
sites[,zip:=over(SpatialPoints(sites[,list(longitude,latitude)],proj4string=CRS(proj4string(sf.zips))),sf.zips)$ZCTA5CE10]
sites[,county:=over(SpatialPoints(sites[,list(longitude,latitude)],proj4string=CRS(proj4string(sf.counties))),sf.counties)$NAME]

persons <- join.on(person.attr,veh.types,'vehicleTypeId','id',c('vehicleTypeName','vehicleClassName','batteryCapacityInKWh','epaRange','epaFuelEcon','maxLevel2ChargingPowerInKW','maxLevel3ChargingPowerInKW'))


#########################################
# Directly compare scraped to modeled
#########################################
dump.code <- 'backup_11_01_2016'
dump.code <- 'backup-2017-01-24'

do.or.load(pp('~/Documents/beam/scraper/',dump.code,'/charging.Rdata'),function(){
  station <- data.table(read.csv(pp('~/Documents/beam/scraper/',dump.code,'/station.csv')))
  status <- data.table(read.csv(pp('~/Documents/beam/scraper/',dump.code,'/status.csv')))
  update <- data.table(read.csv(pp('~/Documents/beam/scraper/',dump.code,'/update.csv')))

  status[,dt:=as.POSIXct(timestamp,origin='1970-01-01')]
  station[,id.long:=factor(id)]
  station[,id:=as.numeric(id.long)]
  station[tolower(state)=='california',state:='CA']
  setkey(station,id.long)
  station[,level:= unlist(lapply(str_split(id.long,"-"),function(ll){ tail(ll,1) }))]
  station[level=="DC" | source=='avinc',level:='DCFAST']
  station[,county:=NULL]
  station[,county:=over(SpatialPoints(station[,list(longitude,latitude)],proj4string=CRS(proj4string(sf.counties))),sf.counties)$NAME]
  station <- station[!is.na(county)]

  status[,id:=station[J(station_id)]$id]
  status <- join.on(status,station,'id','id',c('zip_code','county','level'))
  status <- status[!is.na(county)]
  status[,year.doy.hr:=pp(year(dt),'-',yday(dt),'-',hour(dt))]
  status[,':='(month=factor(month(dt),levels=as.character(1:12),labels=c('Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec')),hour=hour(dt))]
  status.sum <- status[,list(in.use=mean(in_use,na.rm=T),zip_code=zip_code[1],county=county[1],month=month[1],hour=hour[1],level=level[1]),by=c('id','year.doy.hr')]
  status.sum <- status.sum[,list(in.use=mean(in.use,na.rm=T),zip_code=zip_code[1],county=county[1],level=level[1]),by=c('id','hour')]

  # save(station,status,update,status.sum,file=pp('~/Documents/beam/scraper/',dump.code,'/charging.Rdata'))
  return(list(station=station,status=status,update=update,status.sum=status.sum))
})

setkey(status.sum,level,hour)
#ggplot(status.sum[,list(num.in.use=sum(in.use)),by=c('level','county','hour')],aes(x=hour,y=num.in.use,fill=level))+geom_bar(stat='identity')+facet_wrap(~county)+labs(x="Hour",y="# Plugs in Use",fill="Level")

end.event.type <- 'EndChargingSessionEvent'
end.event.type <- 'UnplugEvent' 
ch.1 <- ev[choice=='charge']
ch.2 <- ev[decisionEventId %in% ch.1$decisionEventId & type %in% c('BeginChargingSessionEvent',end.event.type)]

ch <- join.on(ch.2,ch.1,c('scenario','decisionEventId'),c('scenario','decisionEventId'),c('plugType','soc','site'),'dec.')[,list(person=as.numeric(person[1]),begin.time=as.numeric(time[1])/3600,end.time=as.numeric(time[2])/3600,plugType=dec.plugType[1],site=as.numeric(dec.site[1]),begin.soc=as.numeric(dec.soc[1]),end.soc=as.numeric(soc[2]),plug=as.numeric(plug[1])),by=c('scenario','decisionEventId')]
ch[,charger.sector:=ifelse(plug<0,'Residential','Non-Residential')]
ch <- join.on(ch,persons,'person','personId',c('vehicleTypeName','vehicleClassName','batteryCapacityInKWh','epaRange','epaFuelEcon','maxLevel2ChargingPowerInKW','maxLevel3ChargingPowerInKW'))
ch[,veh.class:=ifelse(vehicleClassName=='PHEV','PHEV','BEV')]
ch <- join.on(ch,ch.types,'plugType','plugTypeName','chargingPowerInKW')
ch <- na.omit(ch)
ch[,kw:=ifelse(chargingPowerInKW>20,min(chargingPowerInKW,maxLevel3ChargingPowerInKW),min(chargingPowerInKW,maxLevel2ChargingPowerInKW)),by=c('scenario','decisionEventId')]
round.to <- function(num,nearest){
  floor(num) + round(round((num-floor(num))*100)/(nearest*100))* nearest
}
ch[,begin.hour:=floor(begin.time)]
ch[,dc.fast:=chargingPowerInKW>20]
ch <- join.on(ch,sites,'site','id',c('latitude','longitude','zip','county'))

## SUBSET TO SF BAY AREA
pts.per.site <- points[,list(pts.per.site=length(id)),by='siteID']
if(!'pts.per.site'%in%names(sites)){
  sites <- join.on(sites,pts.per.site,'id','siteID')
  sites <- sites[!is.na(pts.per.site)]
}
dist.mat <- rdist.earth(sites[,list(longitude,latitude)],station[,list(longitude,latitude)],miles=F)
# Now order by distance and reduce the sites we use in our actual comparative analysis to same number as the stations
sites.to.ignore <- sites$id[!sites$id %in% u(ch$site[ch$site>0])]
points[,level:=ifelse(chargingPowerInKW>20,'DCFAST',ifelse(chargingPowerInKW>2,'LEVEL2','LEVEL1'))]
points[,allocated:=F]
setkey(points,siteID)
for(station.i in 1:nrow(station)){
  if(station.i%%100==0)cat(station.i)
  cand.ind <- which.min(dist.mat[,station.i])
  cand.id <- sites$id[cand.ind]
  past.cands <- cand.ind
  while(nrow(points[!siteID%in%sites.to.ignore & siteID==cand.id & level==station[station.i]$level & allocated==F])==0){
    the.dists <- dist.mat[,station.i]
    the.dists[past.cands] <- NA
    cand.ind <- which.min(the.dists)
    cand.id <- sites$id[cand.ind]
    past.cands <- c(past.cands,cand.ind)
  }
  points[siteID==cand.id & level==station[station.i]$level & allocated==F,allocated:=c(T,rep(F,length(id)-1))]
}
sites.to.keep <- u(points[allocated==T]$siteID)

# Verify distribution of L2/L3 is right
# points[allocated==T,list(length(id)),by='level']
# station[,list(length(id)),by='level']

fin.plugs <- points[allocated==T]$id
# Finally, assign home chargers to counties
load('/Users/critter/Documents/beam/input/run0-201-plans-all.Rdata')
setkey(plans,id,type)
homes <- unique(plans[type=='Home' & id%in%u(ch$person)])
homes[,home.id:=-id]
homes.wgs <- copy(homes)
coordinates(homes.wgs) <- c("x", "y")
proj4string(homes.wgs) <- CRS("+init=epsg:26910") 
homes.wgs <- data.table(coordinates(spTransform(homes.wgs,CRS("+init=epsg:4326"))))
homes[,':='(longitude=homes.wgs$x,latitude=homes.wgs$y)]
fin.ch <- copy(ch)
fin.ch <- join.on(fin.ch,homes,'site','home.id',c('longitude','latitude'),'home.')
fin.ch[,':='(latitude=ifelse(is.na(latitude),home.latitude,latitude),longitude=ifelse(is.na(longitude),home.longitude,longitude))]
fin.ch <- fin.ch[plug%in%u(fin.plugs) | site<0]
fin.ch[,county:=over(SpatialPoints(fin.ch[,list(longitude,latitude)],proj4string=CRS(proj4string(sf.counties))),sf.counties)$NAME]
ch.load <- fin.ch[,list(t=seq(round.to(begin.time,.25),round.to(end.time,.25),by=.25),kw=kw,dc.fast=kw>20,site=site,zip=zip,county=county,charger.sector=charger.sector),by=c('scenario','decisionEventId')]
ch.load[dc.fast==T,charger.sector:='DC Fast']
ch.load[dc.fast==F & charger.sector=='Non-Residential',charger.sector:='Level 2']
ch.load[,hour:=floor(t)]

#setkey(ch.load,scenario,charger.sector,t)
#ggplot(ch.load[,list(kw=sum(kw),num.in.use=length(kw),hour=hour[1]),by=c('scenario','t','county','charger.sector')][,list(num.in.use=mean(num.in.use)),by=c('scenario','hour','county','charger.sector')],aes(x=hour,y=num.in.use,fill=charger.sector))+geom_bar(stat='identity')+facet_wrap(~county)+labs(x="Hour",y="# Plugs in Use",fill="Level")

#ggplot(ch.load[scenario%in%c('uniform-random','always-real','nested-logit-full'),list(kw=sum(kw),num.in.use=length(kw),hour=hour[1]),by=c('scenario','t','county','charger.sector')][,list(num.in.use=mean(num.in.use)),by=c('scenario','hour','county','charger.sector')],aes(x=hour,y=num.in.use,fill=charger.sector))+geom_bar(stat='identity')+facet_grid(scenario~county)+labs(x="Hour",y="# Plugs in Use",fill="Type")

status.sum[,scenario:='observed']
status.sum[,charger.sector:=ifelse(level=='LEVEL1','Level 1',ifelse(level=='LEVEL2','Level 2','DC Fast'))]
compare <- rbindlist(list(ch.load[hour>5,list(kw=sum(kw),num.in.use=length(kw),hour=hour[1]),by=c('scenario','t','county','charger.sector')][,list(num.in.use=mean(num.in.use)),by=c('scenario','hour','county','charger.sector')],status.sum[,list(num.in.use=sum(in.use)),by=c('scenario','hour','county','charger.sector')]))

compare <- join.on(compare,compare[scenario=='observed'],c('county','hour','charger.sector'),c('county','hour','charger.sector'),'num.in.use','obs.')

# By County
#ggplot(na.omit(cast(compare[scenario%in%c('observed','logit')],hour  +  county + charger.sector ~ scenario,value='num.in.use')),aes(y=logit,x=observed,colour=factor(hour),shape=charger.sector))+geom_point()+facet_wrap(~county)+geom_abline(a=0,b=1)
# Aggreg
#ggplot(na.omit(compare[scenario!='observed'])[,list(num.in.use=sum(num.in.use),obs.num.in.use=sum(obs.num.in.use)),by=c('hour','charger.sector','scenario')],aes(y=num.in.use,x=obs.num.in.use,colour=factor(hour),shape=charger.sector))+geom_point()+facet_grid(charger.sector~scenario,scales='free')+geom_abline(a=0,b=1)

#ggplot(na.omit(compare[scenario!='observed'])[,list(num.in.use=sum(num.in.use),obs.num.in.use=sum(obs.num.in.use)),by=c('hour','charger.sector','scenario')],aes(y=num.in.use,x=obs.num.in.use,colour=factor(hour),shape=charger.sector))+geom_point()+facet_wrap(~scenario)+geom_abline(a=0,b=1)


























# Back to the original analysis

if(F){
  end.event.type <- 'UnplugEvent' 
  end.event.type <- 'EndChargingSessionEvent'
  for(end.event.type in c('EndChargingSessionEvent','UnplugEvent')){

    plot.kw <- end.event.type=='EndChargingSessionEvent'

    ch.1 <- ev[choice=='charge']
    ch.2 <- ev[decisionEventId %in% ch.1$decisionEventId & type %in% c('BeginChargingSessionEvent',end.event.type)]

    ch <- join.on(ch.2,ch.1,c('scenario','decisionEventId'),c('scenario','decisionEventId'),c('plugType','soc','site'),'dec.')[,list(person=as.numeric(person[1]),begin.time=as.numeric(time[1])/3600,end.time=as.numeric(time[2])/3600,plugType=dec.plugType[1],site=as.numeric(dec.site[1]),begin.soc=as.numeric(dec.soc[1]),end.soc=as.numeric(soc[2]),plug=as.numeric(plug[1])),by=c('scenario','decisionEventId')]
    ch[,charger.sector:=ifelse(plug<0,'Residential','Non-Residential')]
    ch <- join.on(ch,persons,'person','personId',c('vehicleTypeName','vehicleClassName','batteryCapacityInKWh','epaRange','epaFuelEcon','maxLevel2ChargingPowerInKW','maxLevel3ChargingPowerInKW'))
    ch[,veh.class:=ifelse(vehicleClassName=='PHEV','PHEV','BEV')]
    ch <- join.on(ch,ch.types,'plugType','plugTypeName','chargingPowerInKW')
    ch <- na.omit(ch)
    ch[,kw:=ifelse(chargingPowerInKW>20,min(chargingPowerInKW,maxLevel3ChargingPowerInKW),min(chargingPowerInKW,maxLevel2ChargingPowerInKW)),by=c('scenario','decisionEventId')]
    round.to <- function(num,nearest){
      floor(num) + round(round((num-floor(num))*100)/(nearest*100))* nearest
    }
    ch[,begin.hour:=floor(begin.time)]
    ch[,dc.fast:=chargingPowerInKW>20]
    ch <- join.on(ch,sites,'site','id',c('latitude','longitude','zip','county'))

    ###############################################
    # Look at aggregate profile with all chargers
    ###############################################
    ch.load <- ch[,list(t=seq(round.to(begin.time,.25),round.to(end.time,.25),by=.25),kw=kw,dc.fast=kw>20,site=site,zip=zip,county=county,charger.sector=charger.sector),by=c('scenario','decisionEventId')]
    ch.load[dc.fast==T,charger.sector:='DC Fast']
    ch.load[dc.fast==F & charger.sector=='Non-Residential',charger.sector:='Level 2']
    ch.load[,hour:=floor(t)]

    # What does aggregate profile look like
    #ggplot(ch.load[,list(kw=sum(kw),num.in.use=length(kw)),by=c('scenario','t','charger.sector')],aes(x=t,y=num.in.use,colour=charger.sector))+geom_line()+facet_wrap(~scenario)+labs(x="Hour",y=ifelse(end.event.type=='UnplugEvent','# Occupied Plugs',"kW"),colour="Sector")

    # Infrastructure Matters!
    setkey(ch.load,scenario,t,charger.sector)
    p<-ggplot(ch.load[scenario%in%c('always-inf','always-real'),list(kw=sum(kw),num.in.use=length(kw),hour=hour[1]),by=c('scenario','t','charger.sector')][,list(kw=mean(kw),num.in.use=mean(num.in.use)),by=c('scenario','hour','charger.sector')],aes(x=hour,y=streval(ifelse(plot.kw,'kw/1000','num.in.use')),fill=charger.sector))+geom_bar(stat='identity')+facet_wrap(~scenario)+labs(x="Hour",y=ifelse(plot.kw,'MW','# Occupied Plugs'),fill="Type")
    ggsave(p,file=pp('~/Dropbox/ucb/vto/beam-developers/analysis/deliverable-2016-09/infrastructre-matters-',ifelse(plot.kw,'kw','num'),'.pdf'),width=13,height=6)


    # Space Matters!
    tots <- points[,list(n=length(id)),by=dc.fast]
    ch.load <- join.on(ch.load,tots,'dc.fast','dc.fast','n')
    p <- ggplot(ch.load[scenario%in%c('always-real') & charger.sector!='Residential',list(kw=sum(kw),num.in.use=length(kw),hour=hour[1],n=n[1]),by=c('scenario','t','charger.sector')][,list(kw=mean(kw),num.in.use=mean(num.in.use),n=n[1]),by=c('scenario','hour','charger.sector')],aes(x=hour,y=n-num.in.use,fill=charger.sector))+geom_bar(stat='identity')+facet_wrap(~charger.sector,scales='free_y')+labs(x="Hour",y="# Plugs Available",fill="Type")
    ggsave(p,file=pp('~/Dropbox/ucb/vto/beam-developers/analysis/deliverable-2016-09/space-matters-',ifelse(plot.kw,'kw','num'),'.pdf'),width=13,height=6)

    # Behavior Matters!
    setkey(ch.load,scenario,t,charger.sector)
    p <- ggplot(ch.load[scenario%in%c('always-real','nested-logit','uniform-random'),list(kw=sum(kw),num.in.use=length(kw),hour=hour[1]),by=c('scenario','t','charger.sector')][,list(kw=mean(kw),num.in.use=mean(num.in.use)),by=c('scenario','hour','charger.sector')],aes(x=hour,y=streval(ifelse(plot.kw,'kw/1000','num.in.use')),fill=charger.sector))+geom_bar(stat='identity')+facet_wrap(~scenario)+labs(x="Hour",y=ifelse(plot.kw,'MW','# Occupied Plugs'),fill="Type")
    ggsave(p,file=pp('~/Dropbox/ucb/vto/beam-developers/analysis/deliverable-2016-09/behavior-matters-',ifelse(plot.kw,'kw','num'),'.pdf'),width=13,height=6)
  }
}









ggplot(ch.load[!is.na(county),list(kw=sum(kw),num.in.use=length(kw)),by=c('scenario','t','county')],aes(x=t,y=kw,colour=county))+geom_line()+facet_wrap(~scenario)

ggplot(ch.load[!is.na(zip),list(kw=sum(kw),num.in.use=length(kw),county=county[1]),by=c('t','zip')],aes(x=t,y=kw,colour=zip))+geom_line()+facet_wrap(~county)
ggplot(ch.load[!is.na(county),list(kw=sum(kw),num.in.use=length(kw)),by=c('t','county')],aes(x=t,y=num.in.use,colour=county))+geom_line()
ggplot(ch.load[!is.na(county),list(kw=sum(kw),num.in.use=length(kw)),by=c('t','county')],aes(x=num.in.use,y=kw))+geom_point()+facet_wrap(~county)
lm(kw~num.in.use:county-1,ch.load[!is.na(county),list(kw=sum(kw),num.in.use=length(kw)),by=c('t','county')])

ggplot(ch.load[zip=='94128',list(kw=sum(kw),num.in.use=length(kw),county=county[1]),by=c('t','charger.sector')],aes(x=t,y=kw,colour=charger.sector))+geom_line()
ggplot(ch.load[zip=='94128',list(kw=sum(kw),num.in.use=length(kw),county=county[1]),by=c('t','charger.sector')],aes(x=t,y=num.in.use,colour=charger.sector))+geom_line()

#ggplot(ch,aes(x=begin.time,xend=end.time,y=begin.soc,yend=end.soc))+geom_segment()+facet_grid(plugType~charger.sector)


ggplot(ch.load[!is.na(county),list(kw=sum(kw),num.in.use=length(kw)),by=c('t','county','dc.fast')],aes(x=t,y=kw,colour=dc.fast))+geom_line()+facet_wrap(~county)
ggplot(ch.load[!is.na(county),list(kw=sum(kw),num.in.use=length(kw)),by=c('t','county','dc.fast')],aes(x=t,y=num.in.use,colour=dc.fast))+geom_line()+facet_wrap(~county)

ggplot(ev[type=='departure',list(n=length(time)),by=c('scenario','hour')],aes(x=hour,y=n,colour=scenario))+geom_line()+geom_line(data=ev[type=='stuckAndAbort',list(n=length(time)),by=c('scenario','hour')],colour='red')

ch.1 <- ev[choice=='charge']
ch.2 <- ev[decisionEventId %in% ch.1$decisionEventId & type %in% c('PreChargeEvent','BeginChargingSessionEvent','EndChargingSessionEvent')]

ch <- join.on(ch.2,ch.1,c('scenario','decisionEventId'),c('scenario','decisionEventId'),c('plugType','soc','site'),'dec.')[,list(person=as.numeric(person[1]),begin.pre.charge=as.numeric(time[1])/3600,begin.charge=as.numeric(time[2])/3600,end.charge=as.numeric(time[3])/3600,plugType=dec.plugType[1],site=as.numeric(dec.site[1]),begin.soc=as.numeric(dec.soc[1]),end.soc=as.numeric(soc[2]),plug=as.numeric(plug[1])),by=c('scenario','decisionEventId')]
ch[,charger.sector:=ifelse(plug<0,'Residential','Non-Residential')]
ch <- join.on(ch,ch.types,'plugType','plugTypeName','chargingPowerInKW')
ch[,dc.fast:=chargingPowerInKW>20]

ch[,wait.time:=begin.charge - begin.pre.charge]
ggplot(ch[wait.time>0],aes(x=begin.pre.charge,y=wait.time))+geom_point()+facet_wrap(~scenario)
ch[wait.time>0,length(plug),by='scenario']
ch[wait.time>0,length(plug),by=c('scenario','dc.fast')]
ch[charger.sector=='Non-Residential',length(plug),by=c('scenario','dc.fast')]

# NL 22.7% as many charge events

## Order of magnitude more waiting in naive case
#scenario   # who wait out of all non-residential charging
#1: always-on-arrival 1230 / 19522 = 6.3% (6% L2 and 0.3% DC Fast)
#2:      nested-logit  192 / 4441 = 4.3%

ch <- join.on(ch,persons,'person','personId',c('vehicleTypeName','vehicleClassname','batteryCapacityInKWh','epaRange','epaFuelEcon','maxLevel2ChargingPowerInKW','maxLevel3ChargingPowerInKW'))
ch[,veh.class:=ifelse(vehicleClassname=='PHEV','PHEV','BEV')]
ch <- na.omit(ch)
ch[,kw:=ifelse(chargingPowerInKW>20,min(chargingPowerInKW,maxLevel3ChargingPowerInKW),min(chargingPowerInKW,maxLevel2ChargingPowerInKW)),by=c('scenario','decisionEventId')]



afdc <- data.table(read.csv('~/Dropbox/ucb/vto/beam-developers/misc/chargings-stations/alt-fuel-data-center-2016-08-11.csv'))
afdc[,county:=NULL]
afdc[,county:=over(SpatialPoints(afdc[,list(Longitude,Latitude)],proj4string=CRS(proj4string(sf.counties))),sf.counties)$NAME]
afdc <- afdc[!is.na(county)]

table(station$source)
#avinc       blink chargepoint   opconnect 
   #65        1778        5124         140

#dist.mat <- rdist.earth(station[1:10,list(longitude,latitude)],afdc[1:10,list(Longitude,Latitude)],miles=F)
dist.mat <- rdist.earth(station[,list(longitude,latitude)],afdc[,list(Longitude,Latitude)],miles=F)

nn.i <- apply(dist.mat,1,which.min)
nn.dist <- sapply(1:nrow(dist.mat),function(i){ dist.mat[i,nn.i[i]] })

station[,nn:=nn.i]
station[,nn.dist:=nn.dist]

station[nn.dist<0.5]

# 6612 / 7107 stations are within 0.5 km of a station in afdc, conversely 6498 / 16,692 from afdc are withing 0.5 km

nn.i <- apply(dist.mat,2,which.min)
nn.dist <- sapply(1:ncol(dist.mat),function(i){ dist.mat[nn.i[i],i] })

afdc[,':='(nn.i=nn.i,nn.dist=nn.dist)]

# Within CA, 1447 / 1494 scraper are within 0.5 of AFDC
# Within CA, 1569 / 4027 AFDC are within 0.5 of scraper and

station[state=='CA' & nn.dist<0.5]
station[state=='CA'] 
afdc[State=='CA' & nn.dist<0.5]
afdc[State=='CA']

# Missing by zip
rev(sort(table(afdc[State=='CA' & nn.dist>0.5]$ZIP)))

# Constraint to bay area zips
zips.sf <- readShapePoly('~/Dropbox/ucb/vto/beam-developers/spatial-data/ca-zips/sf-bay-area-zips.shp')
afdc.in.sf <- over(SpatialPoints(afdc[,list(Longitude,Latitude)]),zips.sf)
afdc.in.sf <- which(!is.na(afdc.in.sf[,1]))

afdc[,in.sf:=F]
afdc[afdc.in.sf,in.sf:=T]

afdc[in.sf==T & nn.dist<0.5]
afdc[in.sf==T]

# Within SF Bay Area, 756 / 1637 AFDC are within 0.5 of scraper
rev(sort(table(afdc[in.sf==T & nn.dist>0.5]$ZIP)))

plot(afdc[in.sf==T  & nn.dist<0.5]$Longitude,afdc[in.sf==T & nn.dist<0.5]$Latitude,col='black') 
points(afdc[in.sf==T & nn.dist>0.5]$Longitude,afdc[in.sf==T & nn.dist>0.5]$Latitude,col='red',pch=3) 

rev(sort(table(afdc[in.sf==T & nn.dist>0.5]$EV.Connector.Types)))

# Connector type of missing chargers
                         #J1772                  NEMA520 J1772                          TESLA                     J1772COMBO                        CHADEMO             CHADEMO J1772COMBO                                
                           #614                             78                             77                             23                             19                             14                             10 
                   #TESLA J1772       CHADEMO J1772COMBO J1772                        NEMA520                    J1772 TESLA       J1772 CHADEMO J1772COMBO                  NEMA515 J1772             J1772COMBO CHADEMO 
                             #7                              6                              4                              4                              4                              3                              3 
                 #J1772 NEMA520               J1772 J1772COMBO                  J1772 CHADEMO            NEMA520 J1772 TESLA     NEMA520 CHADEMO J1772COMBO                       NEMA1450               J1772COMBO J1772 
                             #3                              3                              3                              1                              1                              1                              1 
      #J1772 J1772COMBO CHADEMO    J1772 CHADEMO NEMA520 TESLA                  TESLA NEMA520       TESLA CHADEMO J1772COMBO          NEMA520 J1772 CHADEMO          NEMA520 CHADEMO J1772                  NEMA515 TESLA 
                             #1                              1                              0                              0                              0                              0                              0 
                       #NEMA515               NEMA1450 NEMA520                 NEMA1450 J1772       J1772COMBO CHADEMO J1772 J1772 TESLA CHADEMO J1772COMBO          J1772 NEMA515 CHADEMO                  J1772 NEMA515 
                             #0                              0                              0                              0                              0                              0                              0 
        #J1772 NEMA1450 CHADEMO                 J1772 NEMA1450            J1772 CHADEMO TESLA                  CHADEMO TESLA       CHADEMO J1772COMBO TESLA CHADEMO J1772COMBO J1772 TESLA       CHADEMO J1772 J1772COMBO 
                             #0                              0                              0                              0                              0                              0                              0 
                 #CHADEMO J1772 
                             #0 

stat.in.sf <- over(SpatialPoints(station[,list(longitude,latitude)]),zips.sf)
station[,zip:=stat.in.sf$ZCTA5CE10]
stat.in.sf <- which(!is.na(stat.in.sf[,1]))

station[,in.sf:=F]
station[stat.in.sf,in.sf:=T]

status <- join.on(status,station,'id','id',c('in.sf','source','level','zip'))
status[level=="DC",level:="DCFAST"]

sf.stat <- status[in.sf==T]

sf.stat[,':='(doy=yday(dt),hour=hour(dt),
              dow=factor(weekdays(dt),c('Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday')),
              month=factor(month(dt),levels=as.character(1:12),labels=c('Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'))
             )]

in.use <- sf.stat[,list(num.in.use=mean(in_use)),by=c('month','dow','hour','source','level','id','zip')]
avail <- sf.stat[,list(num.avail=mean(available)-mean(in_use)),by=c('month','dow','hour','source','level','id','zip')]

ggplot(in.use[,list(num.in.use=sum(num.in.use)),by=c('dow','hour','source','level')],aes(x=hour,y=num.in.use,fill=level)) + geom_bar(stat='identity',position='dodge') + facet_grid(dow~source)

ggplot(in.use[level=='LEVEL2',list(num.in.use=sum(num.in.use)),by=c('dow','hour')],aes(x=hour,y=num.in.use)) + geom_bar(stat='identity',position='dodge') + facet_wrap(~zip)

in.use <- sf.stat[dow%in%c('Monday','Tuesday','Wednesday','Thursday','Friday'),
                  list(num.avail=mean(available),
                       num.total=mean(total),
                       num.in.use=mean(in_use)),
                  by=c('month','hour','source','level','id','zip')]

ggplot(in.use[,list(num.in.use=sum(num.in.use)),by=c('month','hour','level')],aes(x=hour,y=num.in.use,fill=level)) + geom_bar(stat='identity',position='stack') + facet_wrap(~month) + labs(x="Hour of Day",y="# Chargers in Use")
ggplot(in.use[,list(num.avail=sum(num.avail)),by=c('month','hour','level')],aes(x=hour,y=num.avail,fill=level)) + geom_bar(stat='identity',position='stack') + facet_wrap(~month)+ labs(x="Hour of Day",y="# Unoccupied Chargers")
ggplot(in.use[,list(num.mystery=sum(num.total)-sum(num.in.use)-sum(num.avail)),by=c('month','hour','level')],aes(x=hour,y=num.mystery,fill=level)) + geom_bar(stat='identity',position='stack') + facet_wrap(~month)+ labs(x="Hour of Day",y="# Offline Chargers")

ggplot(melt(in.use[,list(num.total2=sum(num.in.use)+sum(num.avail),num.in.use=sum(num.in.use),num.total=sum(num.total),num.avail=sum(num.avail)),by=c('month','hour','level')],measure.vars=c('num.in.use','num.avail','num.total','num.total2')),aes(x=hour,y=value,fill=level)) + geom_bar(stat='identity',position='stack') + facet_grid(variable~month)+ labs(x="Hour of Day",y="# Chargers")

ggplot(melt(in.use[,list(num.in.use=sum(num.in.use),num.avail=sum(num.avail)),by=c('month','hour','level')],measure.vars=c('num.in.use','num.avail')),aes(x=hour,y=value,fill=level)) + geom_bar(stat='identity',position='stack') + facet_grid(variable~month)+ labs(x="Hour of Day",y="# Chargers")


