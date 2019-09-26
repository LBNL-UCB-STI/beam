
library(stringr)
library(colinmisc)
library(plyr)
library(geosphere)
library(sp)
setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')

#res.dir <- '/Users/critter/Dropbox/ucb/vto/smart-mobility/afi/final-results/'
res.dir <- '/Users/critter/Documents/beam/beam-output/afi/'
runs <- data.table(read.csv(pp(res.dir,'runs.csv'),stringsAsFactors=F))
make.dir(pp(res.dir,'runs'))
runs[,local.file:=pp(res.dir,'runs/',infra,'-',range,'mi-',kw,'kw-',scen,'-events.csv.gz')]
runs[,local.summary.stats.file:=pp(res.dir,'runs/',infra,'-',range,'mi-',kw,'kw-',scen,'-summaryStats.csv')]
runs[,metrics.file:=pp(res.dir,'runs/',infra,'-',range,'mi-',kw,'kw-',scen,'-metrics.Rdata')]
runs[,url.corrected:=as.character(url)]
runs[grepl('html\\#',url),url.corrected:=unlist(lapply(str_split(runs[grepl('html\\#',url)]$url,'s3.us-east-2.amazonaws.com/beam-outputs/index.html#'),function(ll){ pp('https://beam-outputs.s3.amazonaws.com/',ll[2]) }))]
runs[,key:=pp(infra,'-',range,'mi-',kw,'kw-',scen)]
keynames <- c('key','infra','range','kw','scen') 

make.metrics <- function(ev,the.file){
  res <- list()
  ev[,':='(links=NULL,linkTravelTime=NULL,isRH=substr(vehicle,0,4)=='ride')]
  ev[substr(vehicleType,1,5)=='BeamV',vehicleType:=unlist(lapply(str_split(vehicleType,"BeamVehicleType\\("),function(ll){ str_split(ll[2],",")[[1]][1] }))]
  ev[,row:=1:nrow(ev)]
  ev[,vehicle:=as.character(vehicle)]

  veh.types <- ev[type=='PathTraversal',.(vehicleType=vehicleType[1]),by=c('run','vehicle')]
  max.fuel.levels <- ev[,.(maxFuelLevel=max(primaryFuelLevel,na.rm=T)),by=c('run','vehicleType')]
  ev <- join.on(ev,veh.types,c('run','vehicle'),c('run','vehicle'))
  ev <- join.on(ev,copy(max.fuel.levels),c('run','vehicleType'),c('run','vehicleType'))
  ev[,soc:=primaryFuelLevel/maxFuelLevel]
  ev[,isCAV:=grepl("-L5-",vehicleType)]
  ev[,':='(hour=time/3600,dep=departureTime/3600,arr=arrivalTime/3600)]
  ev[,isBEV:=substr(vehicleType,1,3)=='ev-']
  ev[,key:=pp(infra,'-',range,'mi-',kw,'kw-',scen)]
  ev[!chargingType=='',ch.kw:=unlist(lapply(str_split(chargingType,"\\("),function(ll){ ifelse(length(ll)==1,NA,as.numeric(str_split(ll[2],"\\|")[[1]][1])) }))]

  rh <- ev[(isRH)]
  # this one for sf-light tazs
  #df <- data.table(read.csv('~/Dropbox/ucb/vto/beam-all/beam/test/input/sf-light/taz-centers.csv'))
  load("/Users/critter/Dropbox/ucb/vto/beam-colin/analysis/activity/taz-centers.Rdata")
  df <- xy.dt.to.latlon(df,c('coord.x','coord.y'))
  df[,taz:=as.character(taz)]
  rh[,parkingTaz:=as.character(parkingTaz)]
  rh <- join.on(rh,df,'parkingTaz','taz',c('coord.lon','coord.lat'))

  res[['vmt']] <- rh[type=='PathTraversal',.(n=.N,vmt=sum(length)/1609,pmt=sum(length*numPassengers)/1609,num.trips.with.passengers=sum(numPassengers>0)),by=c(keynames,'isBEV','isCAV')]

  res[['chg']] <- ev[type=='RefuelSessionEvent' & ch.kw>20,.(n=.N,energy.delivered.MWh=sum(fuel)/3.6e9),by=c(keynames,'isRH','chargingType')]

  res[['uniqueTaz']] <- rh[type=='RefuelSessionEvent',.(nUniqueTaz=length(u(parkingTaz))),by=keynames]

  setkey(rh,row)
  rh[,arr:=ifelse(type=='ChargingPlugInEvent',c(-1,-1,head(arr,-2)),arr),by=c('run','vehicle')]
  rh[,arr:=ifelse(type=='RefuelSessionEvent',c(-1,-1,-1,head(arr,-3)),arr),by=c('run','vehicle')]
  rh[,arr:=ifelse(type=='RefuelSessionEvent',c(-1,-1,-1,head(arr,-3)),arr),by=c('run','vehicle')]

  rh[,':='(parkChoiceX=ifelse(type=='ParkEvent',c(0,head(startX,-1)),-Inf),parkChoiceY=ifelse(type=='ParkEvent',c(0,head(startY,-1)),-Inf)),by=c('run','vehicle')]
  rh[parkChoiceX== -Inf,parkChoiceX:=NA]
  rh[parkChoiceY== -Inf,parkChoiceY:=NA]
  rh[!is.na(parkChoiceX),distToStall:=apply(cbind(locationX,locationY,parkChoiceX,parkChoiceY),1,function(x){ distm(x[1:2],x[3:4], fun = distHaversine) })]
  res[['distToStall']] <- rh[,.(n=sum(!is.na(distToStall)),meanDist=mean(distToStall,na.rm=T),medianDist=median(distToStall,na.rm=T),maxDist=max(distToStall,na.rm=T)),by=keynames]
  save(res,file=the.file)
}

evs <- list()
summs <- list()
#for(i in 1:nrow(runs)){
for(i in 1:nrow(runs)){
  my.cat(pp(names(runs),":",runs[i],collapse=' , '))
  if(!file.exists(runs$local.file[i])){
    for(it in 0){
      tryCatch(download.file(pp(runs$url.corrected[i],'ITERS/it.',it,'/',it,'.events.csv.gz'),runs$local.file[i]),error=function(e){})
      if(file.exists(runs$local.file[i]))break
    }
  }
  ev <- csv2rdata(runs$local.file[i])

  ev[,infra:=runs$infra[i]]
  ev[,range:=runs$range[i]]
  ev[,kw:=runs$kw[i]]
  ev[,scen:=runs$scen[i]]
  ev[,run:=i]
  ev[substr(vehicleType,1,5)=='BeamV',vehicleType:=unlist(lapply(str_split(vehicleType,"BeamVehicleType\\("),function(ll){ str_split(ll[2],",")[[1]][1] }))]
  ev[,':='(links=NULL,linkTravelTime=NULL,isRH=substr(vehicle,0,4)=='ride')]
  #make.metrics(ev,runs$metrics.file[i])
  if(!file.exists(runs$local.summary.stats.file[i])){
    tryCatch(download.file(pp(runs$url.corrected[i],'summaryStats.csv'),runs$local.summary.stats.file[i]),error=function(e){})
  }
  summ <- csv2rdata(runs$local.summary.stats.file[i])
  summ[,infra:=runs$infra[i]]
  summ[,range:=runs$range[i]]
  summ[,kw:=runs$kw[i]]
  summ[,scen:=runs$scen[i]]
  summ[,run:=i]
  summs[[length(summs)+1]] <- summ
}
summs <- rbindlist(summs,use.names=T,fill=T)
summs[,infra:=factor(infra,c('sparse','rich10','rich5','rich'))]
summs[,kw:=factor(kw,c('50','100','150'))]
summs[,range:=factor(range,c('100','200','300'))]
evs <- rbindlist(evs,use.names=T,fill=T)

metrics <- list()
for(i in 1:nrow(runs)){
  tryCatch(load(runs$metrics.file[i]),error=function(e){})
  if(exists('res')){
    for(met.name in names(res)){
      res[[met.name]][,infra:=runs$infra[i]]
      res[[met.name]][,range:=runs$range[i]]
      res[[met.name]][,scen:=runs$scen[i]]
      res[[met.name]][,kw:=runs$kw[i]]
      res[[met.name]][,key:=pp(infra,'-',range,'mi-',kw,'kw-',scen)]
    }
    metrics[[length(metrics)+1]] <- res
    rm('res')
  }
}
mets <- list()
for(met.name in names(metrics[[1]])){
  mets[[met.name]] <- rbindlist(lapply(metrics,function(ll){ ll[[met.name]] }),fill=T)
  mets[[met.name]][,infra:=factor(infra,c('sparse','rich10','rich5','rich'))]
  mets[[met.name]][,kw:=factor(kw,c('50','100','150'))]
  mets[[met.name]][,range:=factor(range,c('100','200','300'))]
  mets[[met.name]][scen=='b',scen:='b-lowtech']
  mets[[met.name]][,scen:=revalue(scen,c('b-lowtech'='B-Tech_Takeover','a-hightech'='A-Sharing_Caring'))]
  mets[[met.name]][,infra:=revalue(infra,c('rich'='Rich-100%','sparse'='Sparse','rich5'='Rich-20%','rich10'='Rich-10%'))]
}

ggplot(mets[['vmt']][,.(pmt=sum(pmt)),by=keynames],aes(x=infra,y=pmt,colour=kw,shape=range))+geom_point()+facet_wrap(~scen,scales='free_y')+labs(x="Infrastructure Scenario",y="Passenger Miles Served",title="Passenger Miles Served by Whole Ride Hail Fleet")
ggplot(mets[['vmt']][(isBEV),.(pmt=sum(pmt)),by=keynames],aes(x=infra,y=pmt,colour=kw,shape=range))+geom_point()+facet_wrap(~scen,scales='free_y')+labs(x="Infrastructure Scenario",y="Passenger Miles Served",title="Passenger Miles Served by BEVs in Ride Hail Fleet")
mets[['chg']][!chargingType=='',ch.kw:=unlist(lapply(str_split(chargingType,"\\("),function(ll){ ifelse(length(ll)==1,NA,as.numeric(str_split(ll[2],"\\|")[[1]][1])) }))]
ggplot(mets[['chg']],aes(x=infra,y=n,colour=kw,shape=isRH))+geom_point()+facet_wrap(~scen,scales='free_y')
ggplot(mets[['chg']],aes(x=infra,y=energy.delivered.MWh,colour=kw,shape=isRH))+geom_point()+facet_wrap(~scen,scales='free_y')
ggplot(mets[['distToStall']],aes(x=infra,y=meanDist,colour=range,shape=kw))+geom_point()+facet_wrap(~scen,scales='free_y')
ggplot(mets[['uniqueTaz']],aes(x=infra,y=nUniqueTaz,colour=range,shape=kw))+geom_point()+facet_wrap(~scen,scales='free_y')

for(sum.met in c('RHSummary_unmatchedPerRideHailRequests','RHSummary_multiPassengerTripsPerRideHailTrips','RHSummary_multiPassengerTripsPerPoolTrips','RHSummary_deadheadingPerRideHailTrips','averageOnDemandRideWaitTimeInMin')){
  dev.new()
  streval(pp('p <- ggplot(summs,aes(x=infra,y=',sum.met,',colour=kw,shape=range))+geom_point()+facet_wrap(~scen,scales="free_y")+labs(title="',sum.met,'")'))
  print(p)
  system('sleep 0.5')
}


# RH utilization

util <- melt(data.table(read.csv('/Users/critter/Documents/beam/beam-output/afi/ridehail_utilization_stats-1.csv')),id.vars='X')
names(util) <- c('key','variable','value')

util[,':='(scen=unlist(lapply(str_split(key,"-"),function(ll){ pp(ll[1],'-',ll[2]) })),infra=unlist(lapply(str_split(key,"-"),function(ll){ ll[3] })),range=unlist(lapply(str_split(key,"-"),function(ll){ ll[4] })),power=unlist(lapply(str_split(key,"-"),function(ll){ ll[5] })))]
veh.type.extract <- function(ll){
  has.rh.ev <- any(ll=='EV') & any(ll=='RH')
  pp(ll[2:(3+has.rh.ev)],collapse='_')
}
metric.extract <- function(ll){
  has.rh.ev <- any(ll=='EV') & any(ll=='RH')
  pp(ll[(4+has.rh.ev):length(ll)],collapse='_')
}
util[,':='(metric.type=unlist(lapply(str_split(variable,"\\."),function(ll){ ll[1] })),veh.type=unlist(lapply(str_split(variable,"\\."),veh.type.extract)),metric=unlist(lapply(str_split(variable,"\\."),metric.extract )))]
ggplot(util[metric.type=='time'],aes(x=veh.type,y=value,fill=metric))+geom_bar(stat='identity')+facet_wrap(~key)



evs[,row:=1:nrow(evs)]
evs[,vehicle:=as.character(vehicle)]

veh.types <- evs[type=='PathTraversal',.(vehicleType=vehicleType[1]),by=c('run','vehicle')]
max.fuel.levels <- evs[,.(maxFuelLevel=max(primaryFuelLevel,na.rm=T)),by=c('run','vehicleType')]
evs <- join.on(evs,veh.types,c('run','vehicle'),c('run','vehicle'))
evs <- join.on(evs,copy(max.fuel.levels),c('run','vehicleType'),c('run','vehicleType'))
evs[,soc:=primaryFuelLevel/maxFuelLevel]
evs[,isCAV:=grepl("-L5-",vehicleType)]
evs[,':='(hour=time/3600,dep=departureTime/3600,arr=arrivalTime/3600)]
evs[,isBEV:=substr(vehicleType,1,3)=='ev-']
evs[,key:=pp(infra,'-',range,'mi-',kw,'kw-',scen)]
evs[!chargingType=='',ch.kw:=unlist(lapply(str_split(chargingType,"\\("),function(ll){ ifelse(length(ll)==1,NA,as.numeric(str_split(ll[2],"\\|")[[1]][1])) }))]

# Queueing
pr <- function(df){ df[,.(run,infra,range,kw,scen,type,hour,dep,arr,numPassengers,length,primaryFuel,primaryFuelLevel,startX,startY,endX,endY,person,parkingTaz,chargingType,parkingType,soc,row)] }

#rh <- evs[(isRH)]
#q <- rh[(isBEV) & (isCAV)] # where is q'ing likely to happen
#setkey(q,row)
#q[,arr:=ifelse(type=='ChargingPlugInEvent',c(-1,-1,head(arr,-2)),arr),by=c('run','vehicle')]
#q[,arr:=ifelse(type=='RefuelSessionEvent',c(-1,-1,-1,head(arr,-3)),arr),by=c('run','vehicle')]
#pr(q[type=='ChargingPlugInEvent' & hour!=arr])

#q[,hr:=round(hour,0)]
#setkey(q,key,hr)

#q[type=='ChargingPlugInEvent' & hour!=arr,.(n=.N,duration=mean(hour-arr,na.rm=T)),by=c('infra','range','kw','scen')]

# Why don't human driven have more impact with rich infra

rh <- evs[(isRH)]
# this one for sf-light tazs
#df <- data.table(read.csv('~/Dropbox/ucb/vto/beam-all/beam/test/input/sf-light/taz-centers.csv'))
load("/Users/critter/Dropbox/ucb/vto/beam-colin/analysis/activity/taz-centers.Rdata")
df <- xy.dt.to.latlon(df,c('coord.x','coord.y'))
df[,taz:=as.character(taz)]
rh[,parkingTaz:=as.character(parkingTaz)]
rh <- join.on(rh,df,'parkingTaz','taz',c('coord.lon','coord.lat'))

rh[type=='PathTraversal',.(n=.N,miles=sum(length)/1609,pmt=sum(length*numPassengers)/1609),by=c('key','isBEV')]

join.on(rh[type=='PathTraversal' & infra=='rich',.(n=.N,miles=sum(length)/1609,pmt=sum(length*numPassengers)/1609),by=c('scen','isBEV','isCAV')],rh[type=='PathTraversal' & infra=='sparse',.(n=.N,miles=sum(length)/1609,pmt=sum(length*numPassengers)/1609),by=c('scen','isBEV','isCAV')],c('scen','isBEV','isCAV'),c('scen','isBEV','isCAV'),NULL,'sparse.')[,.(scen,isBEV,isCAV,sparse.n,rich.n=n,sparse.miles,rich.miles=miles,sparse.pmt,rich.pmt=pmt)]
   #scen isBEV isCAV sparse.n rich.n sparse.miles rich.miles sparse.pmt   rich.pmt
#1:    a FALSE FALSE    73988  72306    716442.36  701402.35  513254.65  507076.85
#2:    a  TRUE FALSE     2938   2913     22746.46   22876.59   15919.96   15947.41
#3:    b FALSE FALSE   132882 125492   1068335.89 1020359.29  989640.71  910850.50
#4:    b FALSE  TRUE   167955 154812   1154389.94 1061471.98 1147599.51 1002884.94
#5:    b  TRUE FALSE    20288  19623    133987.60  129324.98  120840.57  116038.79
#6:    b  TRUE  TRUE    28279 108844    214482.67  685816.70  128608.20  570465.36

join.on(rh[type=='PathTraversal' & infra=='rich',.(n=.N,miles=sum(length)/1609,pmt=sum(length*numPassengers)/1609),by=c('scen')],rh[type=='PathTraversal' & infra=='sparse',.(n=.N,miles=sum(length)/1609,pmt=sum(length*numPassengers)/1609),by=c('scen')],c('scen'),c('scen'),NULL,'sparse.')[,.(scen,sparse.n,rich.n=n,sparse.miles,rich.miles=miles,sparse.pmt,rich.pmt=pmt,sparse.occupancy=sparse.pmt/sparse.miles,rich.occupancy=pmt/miles)]
   #scen sparse.n rich.n sparse.miles rich.miles sparse.pmt  rich.pmt sparse.occupancy rich.occupancy
#1:    a    76926  75219     739188.8   724278.9   529174.6  523024.3        0.7158856      0.7221310
#2:    b   349404 408771    2571196.1  2896973.0  2386689.0 2600239.6        0.9282407      0.8975712

evs[type=='RefuelSessionEvent' & ch.kw>20,.(n=.N,energy.delivered.MWh=sum(fuel)/3.6e9),by=c('key','isRH','chargingType')]
                   #key  isRH               chargingType     n energy.delivered.MWh
#1:   rich-100mi-50kw-a FALSE evi_public_dcfast(50.0|DC)  1376             4.769792
#2:   rich-100mi-50kw-a  TRUE evi_public_dcfast(50.0|DC)   488             5.004889
#3:   rich-100mi-50kw-b FALSE evi_public_dcfast(50.0|DC)   494             4.504847
#4:   rich-100mi-50kw-b  TRUE evi_public_dcfast(50.0|DC)  3125            34.493736
#5:   rich-100mi-50kw-b  TRUE          fcs_fast(50.0|DC) 11286           195.564056
#6: sparse-100mi-50kw-a FALSE            custom(50.0|DC)   483             1.901222
#7: sparse-100mi-50kw-a  TRUE            custom(50.0|DC)   491             5.086111
#8: sparse-100mi-50kw-b FALSE            custom(50.0|DC)   238             2.026903
#9: sparse-100mi-50kw-b  TRUE            custom(50.0|DC)  6492           100.495250

rh[type=='RefuelSessionEvent',.(nUniqueTaz=length(u(parkingTaz))),by='key']
    #infra nUniqueTaz
#1:   rich        170
#2: sparse        112

setkey(rh,row)
rh[,arr:=ifelse(type=='ChargingPlugInEvent',c(-1,-1,head(arr,-2)),arr),by=c('run','vehicle')]
rh[,arr:=ifelse(type=='RefuelSessionEvent',c(-1,-1,-1,head(arr,-3)),arr),by=c('run','vehicle')]
rh[,arr:=ifelse(type=='RefuelSessionEvent',c(-1,-1,-1,head(arr,-3)),arr),by=c('run','vehicle')]

rh[vehicle==rh[type=='RefuelSessionEvent']$vehicle[1],.(key,range,kw,scen,type,hour,dep,arr,numPassengers,length,distToStall,primaryFuel,primaryFuelLevel,startX,startY,endX,endY,parkChoiceX,parkChoiceY,locationX,locationY,person,parkingTaz,chargingType,parkingType,soc,row)]
rh[,':='(parkChoiceX=ifelse(type=='ParkEvent',c(0,head(startX,-1)),-Inf),parkChoiceY=ifelse(type=='ParkEvent',c(0,head(startY,-1)),-Inf)),by=c('run','vehicle')]
rh[parkChoiceX== -Inf,parkChoiceX:=NA]
rh[parkChoiceY== -Inf,parkChoiceY:=NA]
rh[!is.na(parkChoiceX),distToStall:=apply(cbind(locationX,locationY,parkChoiceX,parkChoiceY),1,function(x){ distm(x[1:2],x[3:4], fun = distHaversine) })]
rh[,.(n=sum(!is.na(distToStall)),meanDist=mean(distToStall,na.rm=T),medianDist=median(distToStall,na.rm=T),maxDist=max(distToStall,na.rm=T)),by='key']
    #infra   n meanDist medianDist  maxDist
#1:   rich 488 364.5045   188.0099 7070.401
#2: sparse 495 625.6845   309.5007 8643.180

rh[type=='RefuelSessionEvent',distTazToCharger:=apply(cbind(locationX,locationY,coord.lon,coord.lat),1,function(x){ distm(x[1:2],x[3:4], fun = distHaversine) })]
rh[type=='RefuelSessionEvent',.(n=.N,distTazToCharger=mean(distTazToCharger,na.rm=T)),by='key']

ggplot(rh[type=='RefuelSessionEvent'],aes(x=locationX,y=locationY))+geom_point()
ggplot(rh[!is.na(parkChoiceX)],aes(x=locationX,y=locationY,xend=parkChoiceX,yend=parkChoiceY))+geom_segment()+facet_wrap(~key)

rh[type=='PathTraversal' & numPassengers>0 & (isBEV),.(pmt=sum(length*numPassengers)),by='key']

#ggplot(rh[type=='


pt <- rh[type=='PathTraversal' & (isBEV)]
dev.new()
ggplot(pt,aes(x=time/3600,y=soc,colour=vehicleType))+geom_point()+facet_wrap(~key)

# where are the sessions in relation to TAZ centroids:
inds<-70:74
ggplot(rh[type=='RefuelSessionEvent' & parkingTaz %in% u(rh[type=='RefuelSessionEvent']$parkingTaz)[inds]],aes(x=locationX,y=locationY,colour=as.character(parkingTaz)))+geom_label(data=df[taz%in%u(rh[type=='RefuelSessionEvent']$parkingTaz)[inds]],aes(x=coord.lon,y=coord.lat,label=taz),colour='red',size=3)+geom_point()+facet_wrap(~key)


# Old looking at soc, etc.
ch <- evs[type%in%c('ChargingPlugInEvent','RefuelSessionEvent','ChargingPlugOutEvent')]
ch[,soc:=primaryFuelLevel/maxFuelLevel]
ch[,hour:=round(time/3600,0)]
ch[,kw:=as.numeric(unlist(lapply(str_split(chargingType,"\\("),function(l){ str_split(l[2],"\\|")[[1]][1] })))]

dev.new()
ggplot(ch[type=='RefuelSessionEvent',.(kw=sum(kw*duration/3600)),by=c('hour','vehicleType','chargingType','isRH')],aes(x=hour,y=kw,fill=chargingType))+geom_bar(stat='identity')+facet_wrap(isRH~vehicleType)
dev.new()
ggplot(ch[type=='RefuelSessionEvent'][(isRH)],aes(x=time/3600,y=duration/3600,colour=parkingType,shape=factor(kw)))+geom_point()
dev.new()
ggplot(ch[type=='ChargingPlugInEvent'][(isRH)],aes(x=time/3600,y=soc,colour=vehicleType))+geom_point()

pt <- evs[type=='PathTraversal'][(isRH)]                                  
dev.new()
ggplot(pt,aes(x=time/3600,y=soc,colour=vehicleType))+geom_point()

# Verifying fix works

df[,':='(parkChoiceX=ifelse(type=='ParkEvent',c(0,head(startX,-1)),-Inf),parkChoiceY=ifelse(type=='ParkEvent',c(0,head(startY,-1)),-Inf)),by='vehicle']
df[person==df[type=='ModeChoice' & mode=='car']$person[1] | driver==df[type=='ModeChoice' & mode=='car']$person[1] | vehicle==df[type=='ModeChoice' & mode=='car']$person[1] ][,.(time,type,mode,parkingTaz,length,departTime,startX,startY,endX,endY,locationX,locationY,parkChoiceX,parkChoiceY)]
df[parkChoiceX== -Inf,parkChoiceX:=NA]
df[parkChoiceY== -Inf,parkChoiceY:=NA]

df[,distToStall:=apply(cbind(locationX,locationY,parkChoiceX,parkChoiceY),1,function(x){ distm(x[1:2],x[3:4], fun = distHaversine) })]
