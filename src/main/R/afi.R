rows.to.do <- 81:93
rows.to.do <- 94:105
rows.to.do <- 106:118
rows.to.do <- 119:130
rows.to.do <- 131:143

library(colinmisc)
library(plyr)
library(geosphere)
load.libraries(c('maptools','sp','stringr','ggplot2','rgdal','viridis','RColorBrewer'))
setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')

#res.dir <- '/Users/critter/Dropbox/ucb/vto/smart-mobility/afi/final-results/'
res.dir <- '/Users/critter/Documents/beam/beam-output/afi/'
beam.dir <- '/Users/critter/Dropbox/ucb/vto/beam-all/beam/'
plots.dir <- '/Users/critter/Dropbox/ucb/vto/smart-mobility/afi/final-results/plots/'
pdf.scale <- 1
runs <- data.table(read.csv(pp(res.dir,'runs.csv'),stringsAsFactors=F))
make.dir(pp(res.dir,'runs'))
runs[,local.file:=pp(res.dir,'runs/',infra,'-',range,'mi-',kw,'kw-',scen,'-BEV',bev,'-events.csv.gz')]
runs[,local.streamed.file:=pp(res.dir,'runs/',infra,'-',range,'mi-',kw,'kw-',scen,'-BEV',bev,'-events.streamed.Rdata')]
runs[,local.summary.stats.file:=pp(res.dir,'runs/',infra,'-',range,'mi-',kw,'kw-',scen,'-BEV',bev,'-summaryStats.csv')]
runs[,local.summary.veh.stats.file:=pp(res.dir,'runs/',infra,'-',range,'mi-',kw,'kw-',scen,'-BEV',bev,'-summaryVehicleStats.csv')]
runs[,metrics.file:=pp(res.dir,'runs/',infra,'-',range,'mi-',kw,'kw-',scen,'-BEV',bev,'-metrics.Rdata')]
runs[,url.corrected:=as.character(url)]
runs[grepl('html\\#',url),url.corrected:=unlist(lapply(str_split(runs[grepl('html\\#',url)]$url,'s3.us-east-2.amazonaws.com/beam-outputs/index.html#'),function(ll){ pp('https://beam-outputs.s3.amazonaws.com/',ll[2]) }))]
runs[,key:=pp(infra,'-',range,'mi-',kw,'kw-',scen,'-BEV',bev)]
runs[,local.public.ch.file:=pp(beam.dir,'production/sfbay/parking/afi-parking-',infra,'-',kw,'-',scen,'.csv')]
runs[,local.depot.ch.file:=pp(beam.dir,'production/sfbay/parking/depot-parking-',infra,'-',kw,'-',scen,'.csv')]
if(sum(duplicated(runs$key))>0)stop(pp("duplicate scenario in runs: ",runs$key[duplicated(runs$key)]))
keynames <- c('key','infra','range','kw','scen','bev') 
# this one for sf-light tazs
#df <- data.table(read.csv('~/Dropbox/ucb/vto/beam-all/beam/test/input/sf-light/taz-centers.csv'))
load("/Users/critter/Dropbox/ucb/vto/beam-colin/analysis/activity/taz-centers.Rdata")
df <- xy.dt.to.latlon(df,c('coord.x','coord.y'))
df[,taz:=as.character(taz)]

scen.names <- c('b-lowtech'='B-Tech_Takeover','a-hightech'='A-Sharing_Caring')
infra.names <- c('rich'='Rich-100%','sparse'='Sparse','rich5'='Rich-20%','rich10'='Rich-10%','none'='None')

ch.type.to.kw <- function(chargingType){
  unlist(lapply(str_split(chargingType,"\\("),function(ll){ ifelse(length(ll)==1,NA,as.numeric(str_split(ll[2],"\\|")[[1]][1])) }))
}
make.metrics <- function(ev,the.file){
  res <- list()
  rh <- ev[(isRH)]
  rh[,parkingTaz:=as.character(parkingTaz)]
  rh <- join.on(rh,df,'parkingTaz','taz',c('coord.lon','coord.lat'))

  res[['vmt']] <- rh[type=='PathTraversal',.(n=.N,vmt=sum(length)/1609,pmt=sum(length*numPassengers)/1609,num.trips.with.passengers=sum(numPassengers>0)),by=c(keynames,'isBEV','isCAV')]

  res[['chg']] <- ev[type=='RefuelSessionEvent' & ch.kw>20,.(n=.N,energy.delivered.MWh=sum(fuel)/3.6e9),by=c(keynames,'isRH','chargingType','isCAV')]

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
  res[['numVehicles']] <- ev[!is.na(isBEV),.(n=length(u(vehicle))),by=c('isCAV','isRH','isBEV')]

  # Peak load a bit more complicated
  ev[is.na(ch.kw), ch.kw:=0]
  t <- seq(floor(min(ev$time/3600)),ceiling(max(ev$time/3600)),by=0.5)
  hourly.loads <- ev[type=='RefuelSessionEvent',.(hour=t,isRH=isRH[1],ch.kw=ch.kw[1],load=ch.kw*sapply(t,function(tt){ max(0,(1 - max(0,(time-duration)/3600-tt)) - max(tt+1-(time-duration)/3600-duration/3600,0)) })),by=c('row',keynames)]
  res[['peakLoad']] <- join.on(hourly.loads[(isRH)&ch.kw>20,.(load=sum(load)),by=c('hour',keynames)][,.(peakRideHailLoadMW=max(load)/1e3),by=keynames],hourly.loads[,.(load=sum(load)),by=c('hour',keynames)][,.(peakTotalLoadMW=max(load)/1e3),by=keynames],keynames,keynames)
  save(res,file=the.file)
}
summs <- list()
vehs<- list()
chs <- list()
for(i in 1:nrow(runs)){
#for(i in rows.to.do){
  my.cat(pp(names(runs),":",runs[i],collapse=' , '))
  #if(!file.exists(runs$local.streamed.file[i])){
    #if(exists('ev'))rm('ev')
    #if(!file.exists(runs$local.file[i])){
      #for(it in 0){
        #tryCatch(download.file(pp(runs$url.corrected[i],'ITERS/it.',it,'/',it,'.events.csv.gz'),runs$local.file[i]),error=function(e){})
        #if(file.exists(runs$local.file[i]))break
      #}
    #}
    #ev <- csv2rdata(runs$local.file[i])[type%in%c('PathTraversal','RefuelSessionEvent','ChargingPlugInEvent','ParkEvent','PersonEntersVehicle')]
    #ev[,':='(links=NULL,linkTravelTime=NULL,isRH=substr(vehicle,0,4)=='ride')]
    #ev[,infra:=runs$infra[i]]
    #ev[,range:=runs$range[i]]
    #ev[,kw:=runs$kw[i]]
    #ev[,bev:=runs$bev[i]]
    #ev[,scen:=runs$scen[i]]
    #ev[,run:=i]
    #ev[,row:=1:nrow(ev)]
    #ev[,vehicle:=as.character(vehicle)]
    #ev[substr(vehicleType,1,5)=='BeamV',vehicleType:=unlist(lapply(str_split(vehicleType,"BeamVehicleType\\("),function(ll){ str_split(ll[2],",")[[1]][1] }))]
    #veh.types <- ev[type=='PathTraversal',.(vehicleType=vehicleType[1]),by=c('run','vehicle')]
    #max.fuel.levels <- ev[,.(maxFuelLevel=max(primaryFuelLevel,na.rm=T)),by=c('run','vehicleType')]
    #ev <- join.on(ev,veh.types,c('run','vehicle'),c('run','vehicle'))
    #ev <- join.on(ev,copy(max.fuel.levels),c('run','vehicleType'),c('run','vehicleType'))
    #ev[,soc:=primaryFuelLevel/maxFuelLevel]
    #ev[,isCAV:=grepl("-L5-",vehicleType)]
    #ev[,':='(hour=time/3600,dep=departureTime/3600,arr=arrivalTime/3600)]
    #ev[,isBEV:=substr(vehicleType,1,3)=='ev-']
    #ev[,key:=pp(infra,'-',range,'mi-',kw,'kw-',scen,'-BEV',bev)]
    #ev[!chargingType=='',ch.kw:=ch.type.to.kw(chargingType)]
    #save(ev,file=runs$local.streamed.file[i])
  #}else{
    #load(runs$local.streamed.file[i])
  #}
  #make.metrics(ev,runs$metrics.file[i])
  if(!file.exists(runs$local.summary.stats.file[i])){
    tryCatch(download.file(pp(runs$url.corrected[i],'summaryStats.csv'),runs$local.summary.stats.file[i]),error=function(e){})
  }
  summ <- csv2rdata(runs$local.summary.stats.file[i])
  summ[,infra:=runs$infra[i]]
  summ[,range:=runs$range[i]]
  summ[,kw:=runs$kw[i]]
  summ[,scen:=runs$scen[i]]
  summ[,bev:=runs$bev[i]]
  summ[,run:=i]
  summs[[length(summs)+1]] <- summ
  if(!file.exists(runs$local.summary.veh.stats.file[i])){
    tryCatch(download.file(pp(runs$url.corrected[i],'summaryVehicleStats.csv'),runs$local.summary.veh.stats.file[i]),error=function(e){})
  }
  veh <- csv2rdata(runs$local.summary.veh.stats.file[i])
  veh[,infra:=runs$infra[i]]
  veh[,range:=runs$range[i]]
  veh[,kw:=runs$kw[i]]
  veh[,scen:=runs$scen[i]]
  veh[,bev:=runs$bev[i]]
  veh[,run:=i]
  vehs[[length(summs)+1]] <- veh
  ch <- data.table(read.csv(runs$local.public.ch.file[i]))
  ch[,infra:=runs$infra[i]]
  ch[,range:=runs$range[i]]
  ch[,kw:=runs$kw[i]]
  ch[,scen:=runs$scen[i]]
  ch[,bev:=runs$bev[i]]
  ch[,run:=i]
  ch[,type:='public']
  ch2 <- data.table(read.csv(runs$local.depot.ch.file[i]))
  ch2[,infra:=runs$infra[i]]
  ch2[,range:=runs$range[i]]
  ch2[,kw:=runs$kw[i]]
  ch2[,scen:=runs$scen[i]]
  ch2[,bev:=runs$bev[i]]
  ch2[,run:=i]
  ch2[,type:='depot']
  chs[[length(chs)+1]] <- rbindlist(list(ch,ch2),fill=T)
}
summs <- rbindlist(summs,use.names=T,fill=T)
summs[,infra:=factor(infra,c('none','sparse','rich10','rich5','rich'))]
summs[,kw:=factor(kw,c('50','100','150'))]
summs[,range:=factor(range,c('100','200','300'))]
vehs <- rbindlist(vehs,use.names=T,fill=T)
vehs[,infra:=factor(infra,c('none','sparse','rich10','rich5','rich'))]
vehs[,kw:=factor(kw,c('50','100','150'))]
vehs[,range:=factor(range,c('100','200','300'))]
vehs[,isBEV:=substr(vehicleType,1,3)=='ev-']
vehs[,isCAV:=substr(vehicleType,1,5)=='ev-L5']
vehs[,key:=pp(infra,'-',range,'mi-',kw,'kw-',scen,'-BEV',bev)]
vehs <- vehs[iteration==0 & (isBEV),.(vehicleMilesTraveled=sum(vehicleMilesTraveled),vehicleHoursTraveled=sum(vehicleHoursTraveled),numberOfVehicles=sum(numberOfVehicles)),by=c(keynames,'isCAV')]
chs <- rbindlist(chs,use.names=T,fill=T)
chs[,infra:=factor(infra,c('none','sparse','rich10','rich5','rich'))]
chs[,kw:=factor(kw,c('50','100','150'))]
chs[,power.str:=factor(pp(kw,' kW'),c('50 kW','100 kW','150 kW'))]
chs[,range:=factor(range,c('100','200','300'))]
chs[,range.str:=pp(range,' mi range')]
chs[,ch.kw:=ch.type.to.kw(chargingType)]
chs[,taz:=as.character(taz)]
chs[,key:=pp(infra,'-',range,'mi-',kw,'kw-',scen,'-BEV',bev)]
chs <- join.on(chs,df,'taz','taz',c('coord.lat','coord.lon'))
chs[,numStalls:=numStalls/10]
summs <- join.on(summs,chs[ch.kw>=50,.(numPublicChargers=sum(numStalls[type=='public']),numDepotChargers=sum(numStalls[type=='depot'])),by='run'],'run','run')

metrics <- list()
for(i in 1:nrow(runs)){
  tryCatch(load(runs$metrics.file[i]),error=function(e){})
  if(exists('res')){
    for(met.name in names(res)){
      res[[met.name]][,infra:=runs$infra[i]]
      res[[met.name]][,range:=runs$range[i]]
      res[[met.name]][,scen:=runs$scen[i]]
      res[[met.name]][,bev:=runs$bev[i]]
      res[[met.name]][,kw:=runs$kw[i]]
      res[[met.name]][,key:=pp(infra,'-',range,'mi-',kw,'kw-',scen,'-BEV',bev)]
    }
    metrics[[length(metrics)+1]] <- res
    rm('res')
  }
}
mets <- list()
for(met.name in names(metrics[[1]])){
  mets[[met.name]] <- rbindlist(lapply(metrics,function(ll){ ll[[met.name]] }),fill=T)
  mets[[met.name]][,infra:=factor(infra,c('none','sparse','rich10','rich5','rich'))]
  mets[[met.name]][,kw:=factor(kw,c('50','100','150'))]
  mets[[met.name]][,power.str:=factor(pp(kw,' kW'),c('50 kW','100 kW','150 kW'))]
  mets[[met.name]][,range:=factor(range,c('100','200','300'))]
  mets[[met.name]][,range.str:=pp(range,' mi range')]
  mets[[met.name]][scen=='b',scen:='b-lowtech']
  mets[[met.name]][,scen:=revalue(scen,scen.names)]
  mets[[met.name]][,infra:=revalue(infra,infra.names)]
}
mets[['vmt']][,type:=factor(ifelse(isBEV,ifelse(isCAV,'AEV','BEV Human'),ifelse(isCAV,'Non-EV AV','Non-EV Human')),c('Non-EV AV','Non-EV Human','AEV','BEV Human'))]
mets[['chg']][!chargingType=='',ch.kw:=ch.type.to.kw(chargingType)]
mets[['chg']][,type:=ifelse(isRH,ifelse(isCAV,'Ride Hail AEV','Ride Hail Human'),'Personal EV')]

#sf.shapefile <- '/Users/critter/Dropbox/ucb/vto/beam-colin/sf-bay-area/tnc_od_typwkday_hourly/sf-bay-area-counties/s7hs4j'
#shape <- readOGR(pp(sf.shapefile,".shp"),tail(strsplit(sf.shapefile,"/")[[1]],1))
#bay.coords <- rbindlist(lapply(1:nrow(shape),function(i){ data.table(county=shape@data$COUNTY[i],shape[i,]@polygons[[1]]@Polygons[[1]]@coords)}))[,':='(x=V1,y=V2)]
#to.plot<-chs[run%in%c(1,10,19,28) & ch.kw>=50,.(numChargers=sum(numStalls),x=coord.lon[1],y=coord.lat[1]),by=c('infra','taz','type')]
#to.plot[,infra:=revalue(infra,infra.names)]
#p <- ggplot(to.plot,aes(x=x,y=y,colour=type,size=numChargers))+geom_polygon(data=bay.coords,aes(x=x,y=y,group=county),fill=NA,colour='black',size=.25)+geom_point(alpha=0.5)+facet_wrap(~infra)+labs(colour="Network Type",size="# of Chargers",x='',y='')+theme_bw()+theme(panel.grid.major = element_blank(), panel.grid.minor= element_blank())
#ggsave(pp(plots.dir,'charging-infrastructure.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

# PMT Plots
#ggplot(mets[['vmt']][,.(pmt=sum(pmt)),by=keynames],aes(x=infra,y=pmt,colour=kw,shape=range))+geom_point()+facet_wrap(~scen,scales='free_y')+labs(x="Infrastructure Scenario",y="Passenger Miles Served",title="Passenger Miles Served by Whole Ride Hail Fleet")
p <- ggplot(mets[['vmt']][,.(pmt=sum(pmt)),by=c('isBEV',keynames)],aes(x=infra,y=pmt,fill=isBEV))+geom_bar(stat='identity')+facet_grid(kw~range)+labs(x="Infrastructure Scenario",y="Passenger Miles Served",title="Passenger Miles Served by Whole Ride Hail Fleet")+theme_bw()
ggsave(pp(plots.dir,'pmt.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')
p <- ggplot(mets[['vmt']][(isBEV),.(pmt=sum(pmt)),by=c(keynames,'range.str','power.str')],aes(x=infra,y=pmt,colour=power.str,group=power.str))+geom_point()+geom_line()+facet_wrap(~range.str)+labs(x="Infrastructure Scenario",y="Passenger Miles Served",title="Passenger Miles Served by BEVs in Ride Hail Fleet",colour='Charger Power')+theme_bw()
ggsave(pp(plots.dir,'bev-pmt.pdf'),p,width=10*pdf.scale,height=5*pdf.scale,units='in')
p <- ggplot(mets[['vmt']][,.(pmt=sum(pmt)),by=c('type',keynames,'range.str','power.str')],aes(x=infra,y=pmt,fill=type))+geom_bar(stat='identity')+facet_grid(power.str~range.str)+labs(x="Infrastructure Scenario",y="Passenger Miles Served",title="Passenger Miles Served by Vehicle Type in Ride Hail Fleet",fill='Vehicle Type')+theme_bw()+scale_fill_manual(values=rev(colorRampPalette(brewer.pal(5, "Blues"))(5)))+theme(axis.text.x = element_text(angle = 30, hjust = 1))
ggsave(pp(plots.dir,'pmt-by-type.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

# Charging plots
#ggplot(mets[['chg']],aes(x=infra,y=n,colour=kw,shape=isRH))+geom_point()+facet_wrap(~scen,scales='free_y')
#ggplot(mets[['chg']],aes(x=infra,y=n,colour=kw,shape=isRH,size=energy.delivered.MWh))+geom_point()+facet_wrap(~scen,scales='free_y')
p <- ggplot(mets[['chg']],aes(x=infra,y=n,colour=kw,shape=type,size=energy.delivered.MWh))+geom_point()+facet_wrap(~range.str)+labs(x='Infrastructure Scenarios',y='Number of Charging Events',colour='Fast Charger Power (kW)',shape='Vehicle Type',size='Energy Delivered (MWh)')+theme_bw()+theme(axis.text.x = element_text(angle = 30, hjust = 1))
ggsave(pp(plots.dir,'charging.pdf'),p,width=10*pdf.scale,height=5*pdf.scale,units='in')
#ggplot(mets[['distToStall']],aes(x=infra,y=meanDist,colour=range,shape=kw))+geom_point()+facet_wrap(~scen,scales='free_y')
#ggplot(mets[['uniqueTaz']],aes(x=infra,y=nUniqueTaz,colour=range,shape=kw))+geom_point()+facet_wrap(~scen,scales='free_y')

# Number of Vehicles
to.plot <- mets$numVehicles[key=='rich-100mi-50kw-b-lowtech' & (isCAV | isRH | isBEV) & !is.na(isBEV)]
to.plot[,type:=ifelse(isRH,ifelse(isCAV,'Ride Hail AEV','Ride Hail Human'),'Personal EVs')]
to.plot[,powertrain:=ifelse(isBEV,'BEV','Non-BEV')]
p <- ggplot(to.plot,aes(x=type,y=n,fill=powertrain))+geom_bar(stat='identity')+labs(x="Vehicle Type",y="Number of Vehicles",fill="Powertrain",title="Number of Vehicles in Scenario")+theme_bw()
ggsave(pp(plots.dir,'num-vehicles.pdf'),p,width=8*pdf.scale,height=5*pdf.scale,units='in')

# Number of Chargers
to.plot<-chs[run%in%c(1,10,19,28) & ch.kw>=50,.(numChargers=sum(numStalls)),by=c('infra','type')]
to.plot[,infra:=revalue(infra,infra.names)]
to.plot[,type:=revalue(type,c('public'='Public','depot'='Depot'))]
p <- ggplot(to.plot,aes(x=infra,y=numChargers,fill=type))+geom_bar(stat='identity')+labs(x='Infrastructure Scenarios',y='Number of Chargers',fill="Network Type",title="Number of Fast Chargers in Scenario")+theme_bw()
ggsave(pp(plots.dir,'num-chargers.pdf'),p,width=8*pdf.scale,height=5*pdf.scale,units='in')

#for(sum.met in c('RHSummary_unmatchedPerRideHailRequests','RHSummary_multiPassengerTripsPerRideHailTrips','RHSummary_multiPassengerTripsPerPoolTrips','RHSummary_deadheadingPerRideHailTrips','averageOnDemandRideWaitTimeInMin')){
  #dev.new()
  #streval(pp('p <- ggplot(summs,aes(x=infra,y=',sum.met,',colour=kw,shape=range))+geom_point()+facet_wrap(~scen,scales="free_y")+labs(title="',sum.met,'")'))
  #print(p)
  #system('sleep 0.5')
#}

# Costs

keynames.plot <- c(keynames,'range.str','power.str')
var.costs <- copy(mets[['vmt']])
var.costs[,kwh:=vmt*0.3] # .3 kwh/mi
var.costs[,energy.cost:=kwh*0.2*365] # .2 $/kwh
demand.ch.costs <- mets[['peakLoad']][,.(demand.charges=peakTotalLoadMW*1000/0.92*(13.74/30)*365),by=keynames.plot] # .92 efficiency, 13.74 $/kW-mo
demand.ch.costs[,variable:='All Charging']
var.costs <- join.on(var.costs,demand.ch.costs,'key','key','demand.charges')
var.costs[,total.cost:=energy.cost+demand.charges]
var.costs[grepl('CAV',type),type:='AEV']
var.costs[grepl('Human',type),type:='Human']
ch.costs <- copy(chs[ch.kw>=50,.(numChargers=sum(numStalls),ch.kw=ch.kw[1]),by=c('type',keynames.plot)])
ch.costs[,infra.cost:=numChargers*0.149029489*(624+298*(150-ch.kw)/100)*ch.kw] # .149 is Charger amort ratio @ 8%/10years, costs from Nicholas 2019
ch.costs[,variable:=revalue(type,c('public'='Public','depot'='Depot'))]
ch.costs[,infra:=revalue(infra,infra.names)]
veh.costs <- copy(mets$numVehicles[(isCAV | isRH | isBEV)& !is.na(isBEV)])
veh.costs[,range:=as.numeric(as.character(range))]
veh.costs[,per.veh.cost:=ifelse(isBEV,(34500 + 9000*(range-100)/200),30e3)*ifelse((isCAV),1.2,1.0)]
veh.costs[,cap.cost:=per.veh.cost*n*.339] # .339 amort ratio for EVs @ 8%/3.5year
veh.costs[,variable:=ifelse(isRH,ifelse(isCAV,'AEV','Human'),'Personal EVs')]

costs <- rbindlist(list(var.costs[(isBEV),.(cost.type=pp('Energy-',type),value=total.cost,infra,range,kw,scen,key,range.str,power.str)],
                        ch.costs[,.(cost.type=pp('Infrastructure-',variable),value=infra.cost,infra,range,kw,scen,key,range.str,power.str)],
                        veh.costs[(isBEV) & variable!='Personal EVs',.(cost.type=pp('Fleet-',variable),value=cap.cost,infra,range,kw,scen,key,range.str,power.str)]),fill=T)
costs <- join.on(costs,var.costs[,.(pmt=sum(pmt)*365),by='key'],'key','key')

#ggplot(costs,aes(x=infra,y=value/1e6,fill=cost.type))+geom_bar(stat='identity')+facet_grid(power.str~range.str)+theme_bw()+scale_fill_manual(values=rev(colorRampPalette(brewer.pal(4, "Blues"))(4)))+theme(axis.text.x = element_text(angle = 30, hjust = 1))+labs(x="Infrastructure Scenario",y="Cost ($M per Year)",title="Annual Cost of Ride Hail Fleet and Public + Depot Charging Infrastructure",fill='Cost Type')

p <- ggplot(costs,aes(x=infra,y=value/pmt,fill=cost.type))+geom_bar(stat='identity')+facet_grid(power.str~range.str)+theme_bw()+scale_fill_manual(values=c(rev(colorRampPalette(brewer.pal(4, "Blues"))(4)),colorRampPalette(brewer.pal(3, "Reds"))(3)))+theme(axis.text.x = element_text(angle = 30, hjust = 1))+labs(x="Infrastructure Scenario",y="Cost ($/passenger-mile)",title="Cost per Passenger-Mile of Ride Hail Fleet and Public + Depot Charging Infrastructure",fill='Cost Type')
ggsave(pp(plots.dir,'costs.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')


##########################################
# Plots with BEV % as factor
##########################################
if(nrow(mets[['vmt']][infra=='None' & kw==100])==0){
  none.100 <- copy(mets[['vmt']][infra=='None'])
  none.150 <- copy(mets[['vmt']][infra=='None'])
  none.100[,kw:=mets[['vmt']][kw==100]$kw[1]]
  none.150[,kw:=mets[['vmt']][kw==150]$kw[1]]
  mets[['vmt']] <- rbindlist(list(mets[['vmt']],none.100,none.150))
  mets[['vmt']][,key:=pp(infra,'-',range,'mi-',kw,'kw-',scen,'-BEV',bev)] 
  mets[['vmt']][,power.str:=factor(pp(kw,' kW'),c('50 kW','100 kW','150 kW'))]
}
mets[['vmt']][,bev.str:=factor(pp(bev,'% BEV'),pp(c(100,80,60,40,20),'% BEV'))]

keynames.plot <- c(keynames,'range.str','power.str')
p <- ggplot(mets[['vmt']][,.(pmt=sum(pmt)),by=c('isBEV',keynames.plot)],aes(x=infra,y=pmt,colour=factor(bev),shape=isBEV))+geom_point()+facet_grid(kw~range)+labs(x="Infrastructure Scenario",y="Passenger Miles Served",title="Passenger Miles Served by Whole Ride Hail Fleet")+theme_bw()
ggsave(pp(plots.dir,'pmt.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

p <- ggplot(mets[['vmt']][(isBEV)&bev>20,.(pmt=sum(pmt)/1e3),by=c(keynames,'range.str','power.str','bev.str')],aes(x=infra,y=pmt,colour=power.str,group=power.str))+geom_point()+geom_line()+facet_grid(bev.str~range.str,scales='free_y')+labs(x="Infrastructure Scenario",y="Passenger Miles Served (1000)",title="Passenger Miles Served by BEVs in Ride Hail Fleet",colour='Charger Power')+theme_bw()+theme(axis.text.x = element_text(angle = 30, hjust = 1))
ggsave(pp(plots.dir,'bev-pmt.pdf'),p,width=7*pdf.scale,height=5*pdf.scale,units='in')

p <- ggplot(mets[['vmt']][,.(pmt=sum(pmt)),by=c('type',keynames,'range.str','power.str')],aes(x=infra,y=pmt,fill=type))+geom_bar(stat='identity')+facet_grid(power.str~range.str)+labs(x="Infrastructure Scenario",y="Passenger Miles Served",title="Passenger Miles Served by Vehicle Type in Ride Hail Fleet",fill='Vehicle Type')+theme_bw()+scale_fill_manual(values=rev(colorRampPalette(brewer.pal(5, "Blues"))(5)))+theme(axis.text.x = element_text(angle = 30, hjust = 1))
ggsave(pp(plots.dir,'pmt-by-type.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

# Charging plots
#ggplot(mets[['chg']],aes(x=infra,y=n,colour=kw,shape=isRH))+geom_point()+facet_wrap(~scen,scales='free_y')
#ggplot(mets[['chg']],aes(x=infra,y=n,colour=kw,shape=isRH,size=energy.delivered.MWh))+geom_point()+facet_wrap(~scen,scales='free_y')
mets[['chg']][,bev.str:=factor(pp(bev,'% BEV'),pp(c(100,80,60,40,20),'% BEV'))]
p <- ggplot(mets[['chg']],aes(x=infra,y=n,colour=kw,shape=type,size=energy.delivered.MWh))+geom_point()+facet_grid(bev.str~range.str)+labs(x='Infrastructure Scenarios',y='Number of Charging Events',colour='Fast Charger Power (kW)',shape='Vehicle Type',size='Energy Delivered (MWh)')+theme_bw()+theme(axis.text.x = element_text(angle = 30, hjust = 1))
ggsave(pp(plots.dir,'charging.pdf'),p,width=10*pdf.scale,height=5*pdf.scale,units='in')

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
