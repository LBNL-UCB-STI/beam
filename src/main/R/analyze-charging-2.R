
library(stringr)
library(colinmisc)
setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')

the.dir <- '/Users/critter/Documents/beam/beam-output/urbansim-1k__2019-08-15_17-11-49'
iter.dir <- pp(the.dir,'/ITERS/')
evs <- list()
iter <- list.dirs(iter.dir,full.names=F,rec=F)
for(iter in list.dirs(iter.dir,full.names=F,rec=F)){
  my.cat(iter)
  iter.i <- as.numeric(tail(str_split(iter,'\\.')[[1]],1))
  events.csv <- pp(iter.dir,iter,'/',iter.i,'.events.csv')
  ev <- csv2rdata(events.csv)
  ev[,iter:=iter.i]
  ev[,':='(links=NULL,linkTravelTime=NULL,isRH=substr(vehicle,0,4)=='ride')]
  evs[[length(evs)+1]] <- ev
}
evs <- rbindlist(evs)

veh.types <- evs[type=='PathTraversal',.(vehicleType=vehicleType[1]),by='vehicle']
max.fuel.levels <- evs[,.(maxFuelLevel=max(primaryFuelLevel)),by='vehicleType']
evs <- join.on(evs,veh.types,'vehicle','vehicle')
evs <- join.on(evs,max.fuel.levels,'vehicleType','vehicleType')
evs[,soc:=primaryFuelLevel/maxFuelLevel]
evs[,isCAV:=grepl("-L5-",vehicleType)]
ch <- evs[type%in%c('ChargingPlugInEvent','RefuelSessionEvent','ChargingPlugOutEvent')]
ch[,soc:=primaryFuelLevel/maxFuelLevel]
ch[,hour:=round(time/3600,0)]
ch[,kw:=as.numeric(unlist(lapply(str_split(chargingPointType,"\\("),function(l){ str_split(l[2],"\\|")[[1]][1] })))]

dev.new()
ggplot(ch[type=='RefuelSessionEvent',.(kw=sum(kw*duration/3600)),by=c('hour','vehicleType','chargingPointType','isRH')],aes(x=hour,y=kw,fill=chargingPointType))+geom_bar(stat='identity')+facet_wrap(isRH~vehicleType)
dev.new()
ggplot(ch[type=='RefuelSessionEvent'][(isRH)],aes(x=time/3600,y=duration/3600,colour=parkingType,shape=factor(kw)))+geom_point()
dev.new()
ggplot(ch[type=='ChargingPlugInEvent'][(isRH)],aes(x=time/3600,y=soc,colour=vehicleType))+geom_point()

pt <- evs[type=='PathTraversal'][(isRH)]                                  
dev.new()
ggplot(pt,aes(x=time/3600,y=soc,colour=vehicleType))+geom_point()
