


library(stringr)
library(colinmisc)
setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')

res.dir <- '/Users/critter/Dropbox/ucb/vto/smart-mobility/final-results/'
runs <- data.table(read.csv(pp(res.dir,'runs.csv'),stringsAsFactors=F))
make.dir(pp(res.dir,'runs'))
runs[,local.file:=pp(res.dir,'runs/',scen,'-events.csv.gz')]
runs[,local.stats:=pp(res.dir,'runs/',scen,'-linkstats.csv.gz')]
runs[,url.corrected:=as.character(url)]
runs[grepl('html\\#',url),url.corrected:=unlist(lapply(str_split(runs[grepl('html\\#',url)]$url,'s3.us-east-2.amazonaws.com/beam-outputs/index.html#'),function(ll){ pp('https://beam-outputs.s3.amazonaws.com/',ll[2]) }))]

evs <- list()
for(i in 1:nrow(runs)){
  my.cat(pp(names(runs),":",runs[i],collapse=' , '))
  if(!file.exists(runs$local.file[i])){
    for(it in 15:0){
      tryCatch(download.file(pp(runs$url.corrected[i],'ITERS/it.',it,'/',it,'.events.csv.gz'),runs$local.file[i]),error=function(e){})
      tryCatch(download.file(pp(runs$url.corrected[i],'ITERS/it.',it,'/',it,'.linkstats.csv.gz'),runs$local.stats[i]),error=function(e){})
      if(file.exists(runs$local.file[i]))break
    }
  }
  ev <- csv2rdata(runs$local.file[i])
  ev[,infra:=runs$infra[i]]
  ev[,range:=runs$range[i]]
  ev[,kw:=runs$kw[i]]
  ev[,scen:=runs$scen[i]]
  ev[,run:=i]
  ev[,':='(links=NULL,linkTravelTime=NULL,isRH=substr(vehicle,0,4)=='ride')]
  ev[substr(vehicleType,1,5)=='BeamV',vehicleType:=unlist(lapply(str_split(vehicleType,"BeamVehicleType\\("),function(ll){ str_split(ll[2],",")[[1]][1] }))]
  evs[[length(evs)+1]] <- ev
}
evs <- rbindlist(evs,use.names=T,fill=T)

evs[,row:=1:nrow(evs)]
evs[,vehicle:=as.character(vehicle)]

veh.types <- evs[type=='PathTraversal',.(vehicleType=vehicleType[1]),by='vehicle']
max.fuel.levels <- evs[,.(maxFuelLevel=max(primaryFuelLevel,na.rm=T)),by='vehicleType']
evs <- join.on(evs,veh.types,'vehicle','vehicle')
evs <- join.on(evs,copy(max.fuel.levels),'vehicleType','vehicleType')
evs[,soc:=primaryFuelLevel/maxFuelLevel]
evs[,isCAV:=grepl("-L5-",vehicleType)]
evs[,':='(hour=time/3600,dep=departureTime/3600,arr=arrivalTime/3600)]
evs[,isBEV:=substr(vehicleType,1,3)=='ev-']
evs[,key:=pp(infra,'-',range,'mi-',kw,'kw-',scen)]

# Queueing
pr <- function(df){ df[,.(run,infra,range,kw,scen,type,hour,dep,arr,numPassengers,length,primaryFuel,primaryFuelLevel,startX,startY,endX,endY,person,parkingTaz,chargingType,parkingType,soc)] }

rh <- evs[(isRH)]
q <- rh[run%in%c(10,12) & (isBEV) & (isCAV)] # where is q'ing likely to happen
setkey(q,row)
q[,arr:=ifelse(type=='ChargingPlugInEvent',c(-1,-1,head(arr,-2)),arr),by='vehicle']
q[,arr:=ifelse(type=='RefuelSessionEvent',c(-1,-1,-1,head(arr,-3)),arr),by='vehicle']
pr(q[type=='ChargingPlugInEvent' & hour!=arr])

q[,hr:=round(hour,0)]
setkey(q,key,hr)

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

