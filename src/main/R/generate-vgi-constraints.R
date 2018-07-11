
load.libraries(c('Hmisc','sqldf','GEOquery','stringr'))

source('~/Dropbox/ucb/vto/beam-all/beam-calibration/beam/src/main/R/debug.R')
source('~/Dropbox/ucb/vto/beam-all/beam-calibration/beam/src/main/R/vgi-functions.R')

# Get person attributes and vehicle types and join

peeps <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-core/model-inputs/calibration-v2/person-attributes-from-reg-with-spatial-group.csv'))
vehs <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-core/model-inputs/calibration-v2/vehicle-types-bigger-batteries-1.5x.csv'))
plug.types <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-core/model-inputs/calibration-v2/charging-plug-types.csv'))
peeps <- join.on(peeps,vehs,'vehicleTypeId','id',c('batteryCapacityInKWh','vehicleClassName','fuelEconomyInKwhPerMile'))
peeps[,veh.type:='BEV']
peeps[vehicleClassName=='PHEV',veh.type:='PHEV']
peeps[vehicleClassName=='NEV',veh.type:='NEV']

#out.dirs <- list( 'v1'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-03-03_20-48-12/',0),
                  #'v2.0'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-03-20_10-53-55/',0),
                  #'v2.10'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-03-20_10-53-55/',10))
#out.dirs <- list( 'v3-1k'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-07_14-57-11-newinfra-1k/',120))
#out.dirs <- list( 'v3-10k'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-07_12-34-13-newinfra-10k/',20),
                  #'v3-10k-2'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-07_14-49-15/',149) )
#out.dirs <- list( 'v3-10k-3'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-08_08-55-57-10k-1/',0) )
#out.dirs <- list( 'v1'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-08_09-41-50-10k-paramsV1/',0) )
#out.dirs <- list( 'v1B'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-08_13-08-26-10k-paramsV1B/',0) )
#out.dirs <- list( 'v1B84'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-08_13-53-27-10k-V1B-84hours/',0) )
#out.dirs <- list( 'v2'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-08_09-54-59-10k-paramsV2/',0) )
#out.dirs <- list( 'v3-68k-1'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-07_22-24-33-68k-1/',0))

#out.dirs <- list( 'v1B-68k-0pct'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-08_16-13-53-68k-V1B-0pct-84hrs/',0) )
#out.dirs <- list( 'v1B-68k-50pct'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-08_13-26-49-68k-V1B-50pct-60hours/',0) )
#out.dirs <- list( 'v1B-68k-100pct'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-08_14-51-37-68k-V1B-100pct-84hrs/',0) )
#out.dirs <- list( 'v1B-68k-100pct-sameloc'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-08_19-51-29-68k-V1B-100pct-same-loc/',0) )
#out.dirs <- list( 'v1B-68k-200pct'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-08_17-39-32-68k-V1B-200pct-84hrs/',0) )
#out.dirs <- list( 'batt-2x'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-25_01-13-31-batt2x/',0),
                   #'batt-1.5x' = c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-24_23-36-42-batt1.5x/',0),
                   #'base' = c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-24_21-54-01-batt1x/',0) )
out.dirs <- list(  'batt-1.5x' = c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-24_23-36-42-batt1.5x/',0) )
out.dirs <- list(  'vmt-1k' = c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-26_23-23-56/',0) )
out.dirs <- list(  'vmt-68k' = c('/Users/critter/Documents/beam/beam-output/calibration_2017-07-31_19-29-25-final-base-for-plexos/',0) )
#out.dirs <- list( 'morework-50pct'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-08-05_11-53-30-morework50/',0) )
#out.dirs <- list( 'morework-100pct'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-08-05_11-53-30-morework100/',0) )
#out.dirs <- list( 'morework-100pct-sameloc'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-08-05_11-58-22-morework100sameloc/',0) )
#out.dirs <- list( 'morework-200pct'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-08-05_11-58-25-morework200/',0) )

out.dirs <- list(  'base' = c('/Users/critter/Documents/beam/beam-output/calibration/calibration_2017-07-31_19-29-25-final-base-for-plexos/',0) )
                 #'morework-100pct'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-08-28_00-19-21-morework100/',0),
                  #'morework-100pct-sameloc'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-08-05_11-58-22-morework100sameloc/',0),
                   #'morework-200pct'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-08-05_11-58-25-morework200/',0) 
                 #)

#out.dirs <- list(  #'var-1' = c('/Users/critter/Documents/beam/beam-output/calibration_2017-08-29_10-37-29-variance-1/',0),
                 #'var-2' = c('/Users/critter/Documents/beam/beam-output/calibration_2017-08-29_10-37-34-variance-2/',0)
                 #'var-3' = c('/Users/critter/Documents/beam/beam-output/calibration_2017-08-30_16-38-04-variance-3/',0),
                 #'var-4' = c('/Users/critter/Documents/beam/beam-output/calibration_2017-08-30_16-44-18-variance-4/',0),
                 #'var-5' = c('/Users/critter/Documents/beam/beam-output/calibration_2017-08-29_11-24-53-variance-5/',0),
                 #'var-6' = c('/Users/critter/Documents/beam/beam-output/calibration_2017-08-29_11-24-35-variance-6/',0),
                 #'var-7' = c('/Users/critter/Documents/beam/beam-output/calibration_2017-08-29_11-24-51-variance-7/',0)
                  #'morework-100pct-sameloc'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-08-05_11-58-22-morework100sameloc/',0),
                   #'morework-200pct'=c('/Users/critter/Documents/beam/beam-output/calibration_2017-08-05_11-58-25-morework200/',0) 
                 #)

scens <- names(out.dirs)

scen <- scens[1]
ev <- list()
for(scen in scens){
  the.out.dir <- out.dirs[[scen]][1]
  the.desired.iter <- out.dirs[[scen]][2]
  suppressWarnings(if(exists('df',mode='list'))rm('df'))
  out.dir <- pp(the.out.dir,'ITERS/it.',the.desired.iter,'/')
  file.without.extension <- pp(out.dir,'run0.',the.desired.iter,'.events.')
  if(file.exists(pp(file.without.extension,'Rdata'))){
    my.cat(out.dir)
    load(pp(file.without.extension,'Rdata'),verb=T)
  }else{
    if(file.exists(pp(file.without.extension,'csv.gz'))){
      system(pp('gunzip ',file.without.extension,'csv.gz'))
    }
    if(file.exists(pp(file.without.extension,'csv'))){
      df <- read.data.table.with.filter(pp(file.without.extension,'csv'),c('DepartureChargingDecisionEvent','ArrivalChargingDecisionEvent','BeginChargingSessionEvent','EndChargingSessionEvent','UnplugEvent','actend','travelled'),'time')
      save(df,file=pp(file.without.extension,'Rdata'))
    }
  }
  if(!exists('df',mode='list')){
    my.cat(pp("Warning, no data loaded for scenario ",scen," and iteration ",the.desired.iter))
    next
  }
  df[,scenario:= scen]
  ev[[length(ev)+1]] <- df
  rm('df')
}
ev <- rbindlist(ev,use.names=T,fill=T)
ev[,native.order:=1:nrow(ev)]

# first extract VMT data, then remove the travelled events and stranded folks, note that eVMT is estimated below
ev <- join.on(ev,peeps,'person','personId',c('batteryCapacityInKWh','veh.type','fuelEconomyInKwhPerMile'))
ev[,hr:=as.numeric(time)/3600]
ev[,hour:=floor(hr)]
stranded.peeps <- u(ev[choice=='stranded']$person)
vmt <- ev[scenario=='base' & !person%in%stranded.peeps,list(miles=sum(as.numeric(distance),na.rm=T)*0.000621371,n.veh=length(u(person)),days=round(max(time)/24/3600)-1),by='veh.type']
weekday.to.weekend <- 1.25 # http://onlinepubs.trb.org/onlinepubs/archive/conferences/nhts/Pendyala-Agarwal.pdf   https://www.arb.ca.gov/research/weekendeffect/we-wd_fr_5-7-04.pdf
vmt[,vmt:=miles/(n.veh)/days*(253 + 112/weekday.to.weekend)] # 261 weekdays per year - 8 holidays

vmt[veh.type=='BEV',target:=11e3]
vmt[veh.type=='PHEV',target:=7.6e3]


ev <- ev[!type=='travelled' & !person%in%stranded.peeps]


setkey(ev,native.order)
ev[actType=="",actType:=NA]
ev[,actType:=ifelse(type=='BeginChargingSessionEvent',repeat_last(as.character(actType),forward=F),as.character(NA)),by=c('scenario','person')]
ev[type=='BeginChargingSessionEvent' & is.na(actType),actType:='Home',by=c('scenario','person')]

ev <- ev[!type%in%c('arrival','departure','travelled','actend','PreChargeEvent')]

# categorize each charger into Home, Work, Public
if(length(grep('morework-200',scen))>0){
  sites  <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-core/model-inputs/calibration-v2/charging-sites-cp-more-work-200pct.csv',stringsAsFactors=F))
}else if(length(grep('morework-100',scen))>0){
  sites  <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-core/model-inputs/calibration-v2/charging-sites-cp-more-work-100pct.csv',stringsAsFactors=F))
}else if(length(grep('morework-50',scen))>0){
  sites  <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-core/model-inputs/calibration-v2/charging-sites-cp-more-work-50pct.csv',stringsAsFactors=F))
}else{
  sites  <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-core/model-inputs/calibration-v2/charging-sites-cp-revised-2017-07.csv',stringsAsFactors=F))
}
sites[,siteType:='Public']
sites[policyID==7,siteType:='Work']
#points <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-core/model-inputs/calibration-v2/charging-points-cp-revised-2017-07.csv',stringsAsFactors=F))
#points <- join.on(points,sites,'siteID','id','policyID')

ev[,site:=as.numeric(site)]
ev <- join.on(ev,sites,'site','id','siteType')
ev[site<0,siteType:='Residential']
setkey(ev,scenario,hr,native.order)

# First fill in the SOC gaps, plugType, vehilceBatteryCap, and activityType
ev[,soc:=ifelse(type=='BeginChargingSessionEvent',c(NA,head(soc,-1)),soc),by=c('scenario','person')]
ev[,soc:=as.numeric(soc)]
ev[,time:=as.numeric(time)]
#ev[,kwhNeeded:=as.numeric(kwhNeeded)]
kwhNeeded <- 0
ev[plugType=='',plugType:=NA]
ev[,plugType:=repeat_last(plugType),by=c('scenario','person')]

# ev[person==6114071 & decisionEventId==17]

# Now focus in on just charge session events
ev <- ev[type%in%c('BeginChargingSessionEvent','EndChargingSessionEvent','UnplugEvent')]

# Assign battery cap and charging rates
ev[,kw:=c("j-1772-2"=6.7,"sae-combo-3"=50,"j-1772-1"=1.9,"chademo"=50,"tesla-2"=20,"tesla-3"=120)[plugType]]

# The following adjustments are needed for comparative runs on battery size, we are now assuming 1.5x as our base
# For scenarios with bigger batteries, we need to adjust
#ev[scenario%in%c('base','batt-1.5x'),batteryCapacityInKWh:=batteryCapacityInKWh*1.5]
#ev[scenario=='batt-2x',batteryCapacityInKWh:=batteryCapacityInKWh*2]

ev[,energy.level:=soc*batteryCapacityInKWh]
setkey(ev,scenario,hr,native.order)
 
# Fill in end of charging sessions for those that were cut short
end.of.sim <- ev[,list(end=max(hr)),by='scenario']
end.of.sim.names <- end.of.sim$scenario
end.of.sim <- end.of.sim$end
names(end.of.sim) <- end.of.sim.names
peeps.to.fix.1 <- ev[,list(n=length(type)),by=c('scenario','person','decisionEventId')][n==1]
peeps.to.fix.1[,row:=1:nrow(peeps.to.fix.1)]
peeps.to.fix.1 <- peeps.to.fix.1[,list(person=person,decisionEventId=decisionEventId,scenario=scenario,type=c('EndChargingSessionEvent','UnplugEvent'),hr=end.of.sim[scenario],energy.level=25,soc=1,kw=6.7,native.order=c(max(ev$native.order)+1,Inf)),by='row']
peeps.to.fix.2 <- ev[,list(n=length(type)),by=c('scenario','person','decisionEventId')][n==2]
peeps.to.fix.2[,row:=1:nrow(peeps.to.fix.2)]
peeps.to.fix.2 <- peeps.to.fix.2[,list(person=person,decisionEventId=decisionEventId,scenario=scenario,type='UnplugEvent',hr=end.of.sim[scenario],energy.level=25,soc=1,kw=6.7,native.order=Inf),by='row']

ev <- rbindlist(list(ev,peeps.to.fix.1,peeps.to.fix.2),use.names=T,fill=T)
setkey(ev,scenario,hr,native.order)

bad.peeps <- u(ev[,list(n=length(hr)),c('scenario','person','decisionEventId')][n!=3]$person)
my.cat(pp('Removing ',length(bad.peeps),' peeps'))
ev <- ev[!person%in%bad.peeps]


# Occasionally, the above produces three time stamps that are identical which breaks interpolation below, fix 
peeps.to.fix <- ev[,all(hr==hr[1]),by=c('scenario','person','decisionEventId')][V1==T]$person
ev[person%in%peeps.to.fix,hr:=hr+c(0,0,0.1),by=c('scenario','person','decisionEventId')]

peeps.to.skip <- ev[,type==c('BeginChargingSessionEvent','EndChargingSessionEvent','UnplugEvent'),by=c('scenario','person','decisionEventId')][V1==F]$person
my.cat(pp('Skipping ',length(peeps.to.skip),' peeps'))
ev <- ev[!person%in%peeps.to.skip]
setkey(ev,scenario,hr,native.order)

ev[,siteType:=repeat_last(siteType),by=c('scenario','person','decisionEventId')]
if(any(is.na(ev$siteType))){ stop('wrong sites file was loaded') }
ev[,actType:=repeat_last(actType),by=c('scenario','person','decisionEventId')]

ev[,final.type:=ifelse(siteType=='Public' & actType=='Work','Public',actType)]
ev[,final.type.new:='Public']
ev[final.type%in%c('Work','Home'),final.type.new:=actType]
ev[final.type=='Home',final.type.new:='Residential']
ev[,':='(final.type=final.type.new,final.type.new=NULL)]

# Finally for TOU analysis, identify and shift the start time of the residential sessions to midnight
#if(F){
  # First assumes we shift at midnight
  shift.if.possible <- function(final.type,hr,energy.level,kw,jitter.band=0){
    if(final.type[1]=='Residential'){
      midnights <- seq(0,24*7,by=24)+runif(1,1-jitter.band,1+jitter.band)
      midnight <- midnights[which(hr[1] < midnights & hr[3] > midnights)]
      if(length(midnight)>0){
        #time.to.full <- (energy.level[3]-energy.level[1])/kw[1]
        time.to.full <- diff(hr[1:2])
        plug.time <- hr[3] - hr[1]
        if(plug.time > time.to.full){
          if(hr[3] - midnight > time.to.full){
            return(c(midnight,midnight+time.to.full,hr[3]))
          }else{
            return(c(hr[3]-time.to.full,hr[3],hr[3]))
          }
        }
      }
    }
    return(hr)
  }
  #ev <- join.on(ev,ev[type%in%c('BeginChargingSessionEvent','EndChargingSessionEvent'),.(duration=abs(diff(hr))),by=c('scenario','person','decisionEventId')],c('scenario','person','decisionEventId'),c('scenario','person','decisionEventId'),'duration')
  ev[,hr:=shift.if.possible(final.type,hr,energy.level,kw,2),by=c('scenario','person','decisionEventId')]
  #ev <- join.on(ev,ev[type%in%c('BeginChargingSessionEvent','EndChargingSessionEvent'),.(duration=abs(diff(hr))),by=c('scenario','person','decisionEventId')],c('scenario','person','decisionEventId'),c('scenario','person','decisionEventId'),'duration','after.')
  ev[,hour:=floor(hr)]
#}

ev[,':='(energy.level.min.phev.flex=c(energy.level[1],energy.level[1],ifelse(veh.type[1]=='PHEV',energy.level[1],energy.level[2])),
         #energy.level.min.full.flex=c(energy.level[1],energy.level[1],ifelse(veh.type[1]=='PHEV',energy.level[1],energy.level[1]+kwhNeeded[1])),
         energy.level.min=c(energy.level[1],energy.level[1],energy.level[2]),
         hr.min=c(hr[1],hr[3] - (hr[2] - hr[1]),hr[3])),by=c('scenario','person','decisionEventId')]
#peeps.to.fix <- ev[,all(hr.min==hr.min[1]),by=c('scenario','person','decisionEventId')][V1==T]$person
#ev[person%in%peeps.to.fix,hr.min:=hr.min+c(0,0,0.1),by=c('scenario','person','decisionEventId')]

# Now back out the eVMT from the charge delivered
evmt <- ev[,.(veh.type=veh.type[1],evmt=(energy.level[2]-energy.level[1])/fuelEconomyInKwhPerMile[1]),by=c('scenario','person','decisionEventId')]
evmt <- evmt[,.(evmt=sum(evmt)/length(u(person))),by='veh.type']
vmt <- join.on(vmt,evmt,'veh.type','veh.type')
vmt[,evmt:=evmt*(253 + 112/weekday.to.weekend)/days]
vmt[veh.type=='BEV',evmt:=vmt]
vmt[,scale:=target/evmt]
vmt[veh.type=='BEV',scale:=scale/1.033635]
vmt[veh.type=='PHEV',scale:=scale/1.02362]

##################################################################
# First aggregate for plexos
##################################################################

ts <- seq(0,ceiling(max(ev$hr)),by=1)

   #Debugging
  #ev[,list(hr=sort(c(ts,hr)),
                #hr.min=sort(c(ts,hr.min)),
                #kw=repeat_last(rev(repeat_last(rev(my.approx(hr,kw,xout=sort(c(ts,hr)),method='constant')$y))))
                #),by=c('person','siteType')]

soc.raw <- ev[,list(hr=sort(c(ts,hr)),
                hr.min=sort(c(ts,hr.min)),
                kw=repeat_last(rev(repeat_last(rev(approx(hr,kw,xout=sort(c(ts,hr)),method='constant')$y)))),
                kw.min=repeat_last(rev(repeat_last(rev(approx(hr,kw,xout=sort(c(ts,hr.min)),method='constant')$y)))),
                energy.level=repeat_last(rev(repeat_last(rev(approx(hr,energy.level,xout=sort(c(ts,hr)),method='linear')$y)))),
                energy.level.min=repeat_last(rev(repeat_last(rev(my.approx(hr.min,energy.level.min,xout=sort(c(ts,hr.min)),method='linear')$y)))),
                energy.level.min.phev.flex=repeat_last(rev(repeat_last(rev(my.approx(hr.min,energy.level.min.phev.flex,xout=sort(c(ts,hr.min)),method='linear')$y)))),
                #energy.level.min.full.flex=repeat_last(rev(repeat_last(rev(my.approx(hr.min,energy.level.min.full.flex,xout=sort(c(ts,hr.min)),method='linear')$y)))),
                veh.type=veh.type[1]),
                 by=c('scenario','person','final.type')]
                #,by=c('scenario','person','siteType','actType')]

if(F){
  soc <- rbindlist(list(soc.raw[,list(scenario,person,hr,energy.level,kw,constraint='max',siteType,actType,veh.type)],soc.raw[,list(scenario,person,hr=hr.min,energy.level=energy.level.min,kw=kw.min,constraint='min',siteType,actType,veh.type)]))[hr==floor(hr)]
  soc <- soc[,list(kw=sum(kw),energy.level=sum(energy.level),veh.type=veh.type[1]),by=c('hr','scenario','person','siteType','actType','constraint')]
  soc[,d.energy.level:=c(0,ifelse(diff(energy.level)>0,diff(energy.level),0)),by=c('scenario','person','siteType','actType','constraint')]
  soc[,cumul.energy:=cumsum(d.energy.level),by=c('scenario','person','siteType','actType','constraint')]
  setkey(soc,scenario,hr,person,siteType,actType,constraint)
  soc[,plugged.in.capacity:=ifelse(abs(diff(cumul.energy))>1e-6,kw[1],0),by=c('hr','scenario','person','siteType','actType')]

  ggplot(soc[hr==floor(hr),list(cumul.energy=sum(cumul.energy)),by=c('hr','constraint','siteType')],aes(x=hr,y=cumul.energy,colour=constraint))+geom_line()+facet_wrap(~siteType)
  ggplot(soc[hr==floor(hr) & hr>=27 & hr<51 &constraint=='max',list(d.energy=sum(d.energy.level)),by=c('hr','constraint','actType')],aes(x=hr%%24,y=d.energy))+geom_line(lwd=1.5)+facet_wrap(~actType,scales='free_y')+labs(title="BEAM Average Load",x="Hour",y="Load (kW)")
  soc.sum <- soc[hr==floor(hr) & hr>=27 & hr<51 &constraint=='max',list(d.energy=sum(d.energy.level)),by=c('scenario','hr','constraint','siteType','actType')]
  #soc.sum <- soc[hr==floor(hr) & hr>=51 & hr<75 &constraint=='max',list(d.energy=sum(d.energy.level)),by=c('scenario','hr','constraint','siteType','actType')]
  setkey(soc.sum,scenario,hr,actType)
  ggplot(soc.sum,aes(x=hr%%24,y=d.energy,fill=actType))+geom_bar(stat='identity')+facet_wrap(scenario~siteType,scales='free_y')+labs(title="BEAM Average Load",x="Hour",y="Load (kW)")

  # Deal with over-representation of charging in early morning due to stranded/cut-off sesssions
  #soc[hr>=2 & hr<3,':='(kw=kw/3,energy.level=energy.level/3)]
  #soc[hr>=3 & hr<4,':='(kw=kw/5,energy.level=energy.level/5)]
  #soc[hr>=4 & hr<5,':='(kw=kw/2,energy.level=energy.level/2)]
}

flex.scenario <- 'base'
#flex.scenario <- 'phev'
#flex.scenario <- 'full'
#for(flex.scenario in c('base','phev','full')){
  if(flex.scenario=='base'){
    soc <- rbindlist(list(soc.raw[,list(scenario,person,hr,energy.level,kw,constraint='max',final.type,veh.type)],soc.raw[,list(scenario,person,hr=hr.min,energy.level=energy.level.min,kw=kw.min,constraint='min',final.type,veh.type)]))[hr==floor(hr)]
  }else if(flex.scenario=='phev'){
    soc <- rbindlist(list(soc.raw[,list(scenario,person,hr,energy.level,kw,constraint='max',final.type,veh.type)],soc.raw[,list(scenario,person,hr=hr.min,energy.level=energy.level.min.phev.flex,kw=kw.min,constraint='min',final.type,veh.type)]))[hr==floor(hr)]
  }else{
    soc <- rbindlist(list(soc.raw[,list(scenario,person,hr,energy.level,kw,constraint='max',final.type,veh.type)],soc.raw[,list(scenario,person,hr=hr.min,energy.level=energy.level.min.full.flex,kw=kw.min,constraint='min',final.type,veh.type)]))[hr==floor(hr)]
  }
  soc <- soc[,list(kw=sum(kw),energy.level=sum(energy.level),veh.type=veh.type[1]),by=c('scenario','hr','person','final.type','constraint')]
  soc[,d.energy.level:=c(0,ifelse(diff(energy.level)>0,diff(energy.level),0)),by=c('scenario','person','final.type','constraint')]
  if(flex.scenario!='base'){ soc[constraint=='min' & veh.type=='PHEV',d.energy.level:=0] }
  soc[,cumul.energy:=cumsum(d.energy.level),by=c('scenario','person','final.type','constraint')]
  setkey(soc,scenario,hr,person,final.type,constraint)
  soc[,plugged.in.capacity:=ifelse(abs(diff(cumul.energy))>1e-6,kw[1],0),by=c('hr','scenario','person','final.type')]

  # Final categorization of load
  soc.sum <- soc[ constraint=='max',list(d.energy=sum(d.energy.level)),by=c('scenario','hr','final.type','veh.type')]
  setkey(soc.sum,scenario,hr,final.type,veh.type)
  ggplot(soc.sum,aes(x=hr,y=d.energy,fill=veh.type))+geom_bar(stat='identity')+facet_grid(final.type~scenario,scales='free_y')+labs(title="BEAM Average Load",x="Hour",y="Load (kW)")

  #ggplot(soc[hr==floor(hr) & hr<160,list(cumul.energy=sum(cumul.energy)),by=c('scenario','hr','constraint','final.type','veh.type')],aes(x=hr,y=cumul.energy,colour=constraint))+geom_line()+facet_wrap(scenario~veh.type~final.type)
  #soc.sum <- soc[hr==floor(hr) & hr>=27+24*5 & hr<51+24*5 &constraint=='max',list(d.energy=sum(d.energy.level)),by=c('scenario','hr','constraint','final.type')]
  #setkey(soc.sum,scenario,hr,final.type)
  #ggplot(soc.sum,aes(x=hr%%24,y=d.energy,fill=final.type))+geom_bar(stat='identity')+facet_grid(final.type~scenario,scales='free_y')+labs(title="BEAM Average Load",x="Hour",y="Load (kW)")

  # Load CP data and create scaling factors for turning BEAM workday output into a full week of constraints
  load(pp('/Users/critter/GoogleDriveUCB/beam-core/data/chargepoint/cp.Rdata'))
  cp[category=='Workplace',type:='Work']
  cp[type=='Commercial',type:='Public']
  wdays <- c('Sun'=0,'Mon'=1,'Tus'=2,'Wed'=3,'Thu'=4,'Fri'=5,'Sat'=6,'Sun'=7)
  cp[,start.wday:=factor(names(wdays[start.wday]),levels=names(wdays))]
  cp.hr <- cp[,list(kw=sum(kw)),by=c('start.month','start.mday','start.wday','hour.of.week','type')][,list(kw=mean(kw),wday=start.wday[1]),by=c('hour.of.week','type')]
  #ggplot(cp.hr,aes(x=hour.of.week%%24,y=kw,colour=factor(wday)))+geom_line(lwd=1.5)+facet_wrap(~type,scales='free_y')+labs(title="ChargePoint Average Load",x="Hour",y="Load (kW)",colour="Day of Week")
  cp.day <- cp[,list(kw=sum(kw)),by=c('start.month','start.mday','start.wday','hour.of.week','type')][,list(kw=mean(kw),wday=start.wday[1]),by=c('start.wday','type')]
  cp.day.norm <- cp.day[,list(norm.load= kw/mean(kw[!wday%in%c('Sat','Sun')]),wday=wday),by='type']

  # Turn aggregated constraints into virtual battery cumul energy, scale by day of week, then repeat for a year

  shave.peak <- function(x,y,shave.start,shave.end){
    c(y[x<shave.start],  approx(x[x%in%c(shave.start,shave.end)],y[x%in%c(shave.start,shave.end)],xout=x[x>=shave.start & x<=shave.end])$y, y[x>shave.end])
  }
  soc.weekday <- copy(soc)
  soc.weekend <- copy(soc)
  # Shift the target day to later in the week without re-writing code below (which pulls out day 2)
  soc.weekday[,hr:=hr-24*3]
  soc.weekend[,hr:=hr-24*3]
  shave.start <- 28; shave.end <- 36
  soc.weekend[,':='(d.energy.level=shave.peak(hr,d.energy.level,shave.start,shave.end)),by=c('scenario','person','final.type','veh.type','constraint')]
  soc.weekend[,':='(cumul.energy=cumsum(d.energy.level)),by=c('scenario','person','final.type','veh.type','constraint')]

  gap.weekday <- gap.analysis(soc.weekday[hr>=28 & hr<=52,list(cumul.energy=sum(cumul.energy),plugged.in.capacity=sum(plugged.in.capacity)),by=c('hr','constraint','final.type','veh.type')])
  gap.weekend <- gap.analysis(soc.weekend[hr>=28 & hr<=52,list(cumul.energy=sum(cumul.energy),plugged.in.capacity=sum(plugged.in.capacity)),by=c('hr','constraint','final.type','veh.type')])

  # Note, we pad with a day on both ends, this is designed for 2024 where Jan 1 is a Monday, so the padded day in the
  # front is a Sunday.
  virt <- data.table(expand.grid(list(day=1:9,dhr=1:24,final.type=u(gap.weekday$final.type),veh.type=u(gap.weekday$veh.type))))
  setkey(virt,final.type,veh.type,day,dhr)
  virt[,hr:=(day-1)*24+dhr]
  virt[,':='(max=0,min=0)]
  for(the.day in 1:9){
    the.wday <- (the.day-1)%%7+1
    if(the.wday>0 & the.wday<6){
      gap.to.use <- scale.the.gap(copy(gap.weekday),cp.day.norm,the.wday)
    }else{
      gap.to.use <- scale.the.gap(copy(gap.weekend),cp.day.norm,the.wday)
    }
    if(the.day==1){
      virt[day==1,max:=gap.to.use$max.norm]
      virt[day==1,min:=gap.to.use$min.norm]
      virt[day==1,plugged.in.capacity:=gap.to.use$plugged.in.capacity]
    }else{
      prev.day <- virt[day==the.day-1]
      prev.day[,current.gap:=max-min]
      max.mins <- prev.day[,list(max.max=max(max),max.min=max(min),max.current.gap=max(current.gap)),by=c('final.type','veh.type')]
      max.mins[max.current.gap<0,max.current.gap:=0]
      max.mins <- join.on(max.mins,gap.to.use[,list(max.desired.gap=max(gap)),by=c('final.type','veh.type')],c('final.type','veh.type'))
      max.mins[,gap.adjustment:=ifelse(max.current.gap>max.desired.gap,max.current.gap-max.desired.gap,0)]
      virt <- join.on(virt,max.mins,c('final.type','veh.type'),c('final.type','veh.type'),c('max.max','max.min','gap.adjustment'))
      if(the.wday==1){
        virt[,max.min:=max.min+gap.adjustment]
      }
      setkey(virt,final.type,veh.type,day,dhr)
      virt[day==the.day,max:=max.max+gap.to.use$max.norm]
      virt[day==the.day,min:=max.min+gap.to.use$min.norm.relative.to.min]
      virt[day==the.day,plugged.in.capacity:=gap.to.use$plugged.in.capacity]
      virt[,':='(max.max=NULL,max.min=NULL,gap.adjustment=NULL)]
    }
  }
  setkey(virt,final.type,veh.type,day,hr)
  ggplot(virt[day<10],aes(x=hr,y=max))+geom_line()+geom_line(aes(y=min),colour='red')+facet_wrap(veh.type~final.type)
  #ggplot(virt[day<10],aes(x=hr,y=plugged.in.capacity))+geom_line()+facet_wrap(veh.type~final.type)


  # Put final constraints needed by PLEXOS into a table
  plexos.constraints <- virt[,list(max=sum(max),min=sum(min),plugged.in.capacity=sum(plugged.in.capacity)),by=c('hr','veh.type')][,list(hour=head(hr,-1),pev.inflexible.load.mw=diff(max)/1000,plexos.battery.min.mwh=head(max - min,-1)/1000,plugged.in.charger.capacity=head(plugged.in.capacity/1000,-1)),by=c('veh.type')]
  plexos.constraints[,plexos.battery.max.discharge:=pev.inflexible.load.mw]
  plexos.constraints[,plexos.battery.max.mwh:=max(plexos.battery.min.mwh),by=c('veh.type')]
  plexos.constraints[,plexos.battery.min.mwh:=plexos.battery.max.mwh-plexos.battery.min.mwh,by=c('veh.type')]
  plexos.constraints[,plexos.battery.max.charge:=plugged.in.charger.capacity - pev.inflexible.load.mw]
  plexos.constraints[,hour:=hour-24]
  plexos.constraints[,plugged.in.charger.capacity:=NULL]

  # Join in the VMT based scaling factors
  plexos.constraints <- join.on(plexos.constraints,vmt,'veh.type','veh.type','scale')

  # Finally, scale according to the scenarios developed by Julia
  scenarios <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-collaborators/planning/vgi/Scaling_factors_for_forecast_and_EV_counts_July24_2017.csv'))
  scenarios <- melt(scenarios[,list(Electric.Utility,Vehicle_category,
                                    Low_2025_energy_propCED15_2080,Low_2025_energy_propCED15_5050,Low_2025_energy_propCED15_8020,Low_2025_energy_propCED15_4060,Low_2025_energy_propCED15_6040,
                                    Mid_2025_energy_propCED15_2080,Mid_2025_energy_propCED15_5050,Mid_2025_energy_propCED15_8020,Mid_2025_energy_propCED15_4060,Mid_2025_energy_propCED15_6040,
                                    High_2025_energy_propCED15_2080,High_2025_energy_propCED15_5050,High_2025_energy_propCED15_8020,High_2025_energy_propCED15_4060,High_2025_energy_propCED15_6040
                                    )],id.vars=c('Electric.Utility','Vehicle_category'))
  scenarios[,penetration:=unlist(lapply(str_split(variable,"_"),function(ll){ ll[1] }))]
  scenarios[,veh.type.split:=unlist(lapply(str_split(variable,"_"),function(ll){ ifelse(length(ll)==4,ll[4],ll[5]) }))]
  scenarios[,':='(veh.type=Vehicle_category,Vehicle_category=NULL)]

  flex.scenario <- 'base'
  #flex.scenario <- '50-more-workplace'
  #flex.scenario <- '100-more-workplace'
  #flex.scenario <- '100-more-workplace-sameloc'
  #flex.scenario <- '200-more-workplace'
  results.dir.base <- '/Users/critter/GoogleDriveUCB/beam-collaborators/planning/vgi/vgi-constraints-for-plexos-2025-tou-v3'
  make.dir(results.dir.base)
  make.dir(pp(results.dir.base,'/',flex.scenario))
  the.utility <- scenarios$Electric.Utility[3]
  pen <- scenarios$penetration[1]
  veh.split <- scenarios$veh.type.split[1]
  reservoirs.max <- list()
  save.all <- list()
  for(the.utility in u(scenarios$Electric.Utility)){
    for(pen in u(scenarios$penetration)){
      for(veh.split in u(scenarios$veh.type.split)){
        to.write <- copy(plexos.constraints[hour>=1 & hour<=7*24])
        to.write.all <- list()
        for(the.week in 1:53){
          to.write.all[[the.week]] <- copy(to.write)
          to.write.all[[the.week]][,week:=the.week]
        }
        to.write <- rbindlist(to.write.all)
        to.write[,hour:=hour+(week-1)*168]
        to.write[,week:=NULL]
        to.write <- join.on(to.write[hour<=366*24],scenarios[Electric.Utility==the.utility & penetration==pen & veh.type.split==veh.split],'veh.type','veh.type','value')
        to.write <- to.write[,list(pev.inflexible.load.mw=sum(pev.inflexible.load.mw*value*scale),
                       plexos.battery.max.mwh=sum(plexos.battery.max.mwh*value*scale),
                       plexos.battery.min.mwh=sum(plexos.battery.min.mwh*value*scale),
                       plexos.battery.max.discharge=sum(plexos.battery.max.discharge*value*scale),
                       plexos.battery.max.charge=sum(plexos.battery.max.charge*value*scale)
                       ),by='hour']
        write.csv(to.write,pp(results.dir.base,'/',flex.scenario,'/',pen,'_',veh.split,'_',the.utility,'.csv'),row.names=F)
        to.write[,Year:=2025]
        to.write[,Month:=month(to.posix('2024-01-01',tz='GMT')+(hour-1)*3600)]
        to.write[,Day:=mday(to.posix('2024-01-01',tz='GMT')+(hour-1)*3600)]
        to.write[,Period:=(hour-1)%%24+1]
        write.csv(to.write[,list(Year,Month,Day,Period,Value=plexos.battery.max.charge)],pp(results.dir.base,'/',flex.scenario,'/',pen,'_',veh.split,'_',the.utility,'_max_charge.csv'),row.names=F)
        write.csv(to.write[,list(Year,Month,Day,Period,Value=plexos.battery.max.discharge)],pp(results.dir.base,'/',flex.scenario,'/',pen,'_',veh.split,'_',the.utility,'_max_discharge.csv'),row.names=F)
        write.csv(to.write[,list(Year,Month,Day,Period,Value=plexos.battery.min.mwh/1000)],pp(results.dir.base,'/',flex.scenario,'/',pen,'_',veh.split,'_',the.utility,'_min_volume.csv'),row.names=F)
        reservoirs.max[[length(reservoirs.max)+1]] <- data.table(Utility=the.utility,Year=2025,Scenario=pen,BEV_to_PHEV=veh.split,plexos.battery.max.GWh=to.write[1]$plexos.battery.max.mwh/1e3,plexos.battery.max.mwh=to.write[1]$plexos.battery.max.mwh)
        to.write[,':='(util=the.utility,pen=pen,split=veh.split)]
        save.all[[length(save.all)+1]] <- to.write
      }
    }
  }
  reservoirs.max <- rbindlist(reservoirs.max)
  save.all <- rbindlist(save.all)
  write.csv(reservoirs.max,pp(results.dir.base,'/',flex.scenario,'/Table_of_Max_Volume_of_Smart_Charging_Reservoirs.csv'),row.names=F)
#}



##################################################################
# Next Aggregate for the Distribution Modeling Team
##################################################################

peeps.to.use <- sample(u(ev$person),1000)
ev <- ev[person%in%peeps.to.use]

ts <- seq(0,ceiling(max(ev$hr)),by=1/12)

soc <- ev[,list(hr=sort(c(ts,hr)),
                hr.min=sort(c(ts,hr.min)),
                kw=repeat_last(rev(repeat_last(rev(approx(hr,kw,xout=sort(c(ts,hr)),method='constant')$y)))),
                kw.min=repeat_last(rev(repeat_last(rev(approx(hr,kw,xout=sort(c(ts,hr.min)),method='constant')$y)))),
                energy.level=repeat_last(rev(repeat_last(rev(approx(hr,energy.level,xout=sort(c(ts,hr)),method='linear')$y)))),
                energy.level.min=repeat_last(rev(repeat_last(rev(approx(hr.min,energy.level.min,xout=sort(c(ts,hr.min)),method='linear')$y))))),by='person']

soc.max <- soc[,list(person,hr,energy.level,kw,constraint='max')]
soc.min <- soc[,list(person,hr=hr.min,energy.level=energy.level.min,kw=kw.min,constraint='min')]
soc <- rbindlist(list(soc.min,soc.max))

soc[,d.energy.level:=c(0,ifelse(diff(energy.level)>0,diff(energy.level),0)),by=c('person','constraint')]
soc[,cumul.energy:=cumsum(d.energy.level),by=c('person','constraint')]

round.to.part.hour <- function(dt,part.hour=1/12){
   floor(dt / part.hour)*part.hour
}
soc <- soc[hr==round.to.part.hour(hr)]

ggplot(soc[hr<=33 & hr==floor(hr),list(cumul.energy=sum(cumul.energy)),by=c('hr','constraint')],aes(x=hr,y=cumul.energy,colour=constraint))+geom_line()
ggplot(soc[hr<=33 & hr==floor(hr),list(kw=sum(kw)),by=c('hr','constraint')],aes(x=hr,y=kw,colour=constraint))+geom_line()

setkey(soc,constraint,person,hr)
disag.constraints <- join.on(unique(soc[constraint=='max']), unique(soc[constraint=='min']),c('person','hr'),c('person','hr'),c('cumul.energy','d.energy.level'),'min.')
disag.constraints[,max.power:=0]
disag.constraints[abs(cumul.energy - min.cumul.energy)>1e-4 | d.energy.level>0 ,max.power:=kw]
disag.constraints[,min.power:=0]
disag.constraints[,':='(min.d.energy.level=NULL,d.energy.level=NULL,unmanaged.energy.level=energy.level,constraint=NULL,max.cumul.energy=cumul.energy,cumul.energy=NULL,kw=NULL)]

ggplot(melt(disag.constraints[person%in%sample(person,12)],measure.vars=c('max.cumul.energy','min.cumul.energy','max.power','min.power')),aes(x=hr,y=value,colour=variable))+geom_line()+facet_wrap(~person)

# Aggregate
ag.constraints <- disag.constraints[,list(max.power=sum(max.power),min.power=sum(min.power),max.cumul.energy=sum(max.cumul.energy),min.cumul.energy=sum(min.cumul.energy)),by='hr']
setkey(ag.constraints,hr)
ggplot(melt(ag.constraints,measure.vars=c('max.cumul.energy','min.cumul.energy','max.power','min.power')),aes(x=hr,y=value,colour=variable))+geom_line()

write.csv(streval(pp('disag.constraints[,list(',pp(c(tail(names(disag.constraints),-1),'min.cumul.energy'),collapse=','),')]')),'/Users/critter/GoogleDriveUCB/beam-collaborators/planning/vgi/example-disaggregated-constraints.csv')
write.csv(ag.constraints,'/Users/critter/GoogleDriveUCB/beam-collaborators/planning/vgi/example-aggregated-constraints.csv')

write.csv(plexos.constraints,'/Users/critter/GoogleDriveUCB/beam-collaborators/planning/vgi/example-disaggregated-constraints.csv')
                                 max.cumulative.energy=diff(virtual.battery.energy[constraint=='max']$cumul.energy)/1000,
                                 plexos.battery.min.soc=head(virtual.battery.energy[constraint=='max']$cumul.energy - virtual.battery.energy[constraint=='min']$cumul.energy,-1))
# Put final constraints needed by PLEXOS into a table


# get the # of vehicles plugged in by hour
soc.both <- join.on(soc[hr<=33 & hr==floor(hr) & constraint=='min'],soc[hr<=33 & hr==floor(hr)& constraint=='max'],c('person','hr'),c('person','hr'),c('cumul.energy','kw'),'max.')
soc.both.sum <- soc.both[abs(cumul.energy-max.cumul.energy)>1e-4,list(kw=sum(max.kw)/1e3),by='hr']
soc.both.sum[,hour:=hr]
plexos.constraints <- join.on(plexos.constraints,soc.both.sum,'hour','hour','kw')
plexos.constraints[,':='(plugged.in.charger.capacity=kw,kw=NULL)]
plexos.constraints[is.na(plugged.in.charger.capacity),plugged.in.charger.capacity:=0]

plexos.constraints[,plexos.battery.max.charge:=plugged.in.charger.capacity - pev.inflexible.load.mw]

#TODO figure out how max capacity is less than load, for now cut off to 0
plexos.constraints[plexos.battery.max.charge<0,plexos.battery.max.charge:=0]

write.csv(plexos.constraints,'/Users/critter/GoogleDriveUCB/beam-collaborators/planning/vgi/example-constraints.csv')


##################################################################
# Results for SWITCH Team
##################################################################

soc[scenario=='Base' & final.type=='Residential' & hr==floor(hr) & hr<160,list(cumul.energy=sum(cumul.energy)),by=c('scenario','hr','constraint')]

soc <- soc[scenario=='base']

shave.peak <- function(x,y,shave.start,shave.end){
  c(y[x<shave.start],  approx(x[x%in%c(shave.start,shave.end)],y[x%in%c(shave.start,shave.end)],xout=x[x>=shave.start & x<=shave.end])$y, y[x>shave.end])
}
soc.weekday <- copy(soc)
soc.weekend <- copy(soc)
# Shift the target day to later in the week without re-writing code below (which pulls out day 2)
soc.weekday[,hr:=hr-24*3]
soc.weekend[,hr:=hr-24*3]
shave.start <- 28; shave.end <- 36
soc.weekend[,':='(d.energy.level=shave.peak(hr,d.energy.level,shave.start,shave.end)),by=c('scenario','person','final.type','veh.type','constraint')]
soc.weekend[,':='(cumul.energy=cumsum(d.energy.level)),by=c('scenario','person','final.type','veh.type','constraint')]

gap.weekday <- gap.analysis(soc.weekday[hr>=28 & hr<=52,list(cumul.energy=sum(cumul.energy),plugged.in.capacity=sum(plugged.in.capacity)),by=c('hr','constraint','final.type','veh.type')])
gap.weekend <- gap.analysis(soc.weekend[hr>=28 & hr<=52,list(cumul.energy=sum(cumul.energy),plugged.in.capacity=sum(plugged.in.capacity)),by=c('hr','constraint','final.type','veh.type')])

# Note, we pad with a day on both ends, this is designed for 2024 where Jan 1 is a Monday, so the padded day in the
# front is a Sunday.
virt <- data.table(expand.grid(list(day=1:9,dhr=1:24,final.type=u(gap.weekday$final.type),veh.type=u(gap.weekday$veh.type))))
setkey(virt,final.type,veh.type,day,dhr)
virt[,hr:=(day-1)*24+dhr]
virt[,':='(max=0,min=0)]
for(the.day in 1:9){
  the.wday <- (the.day-1)%%7+1
  if(the.wday>0 & the.wday<6){
    gap.to.use <- scale.the.gap(copy(gap.weekday),cp.day.norm,the.wday)
  }else{
    gap.to.use <- scale.the.gap(copy(gap.weekend),cp.day.norm,the.wday)
  }
  if(the.day==1){
    virt[day==1,max:=gap.to.use$max.norm]
    virt[day==1,min:=gap.to.use$min.norm]
    virt[day==1,plugged.in.capacity:=gap.to.use$plugged.in.capacity]
  }else{
    prev.day <- virt[day==the.day-1]
    prev.day[,current.gap:=max-min]
    max.mins <- prev.day[,list(max.max=max(max),max.min=max(min),max.current.gap=max(current.gap)),by=c('final.type','veh.type')]
    max.mins[max.current.gap<0,max.current.gap:=0]
    max.mins <- join.on(max.mins,gap.to.use[,list(max.desired.gap=max(gap)),by=c('final.type','veh.type')],c('final.type','veh.type'))
    max.mins[,gap.adjustment:=ifelse(max.current.gap>max.desired.gap,max.current.gap-max.desired.gap,0)]
    virt <- join.on(virt,max.mins,c('final.type','veh.type'),c('final.type','veh.type'),c('max.max','max.min','gap.adjustment'))
    if(the.wday==1){
      virt[,max.min:=max.min+gap.adjustment]
    }
    setkey(virt,final.type,veh.type,day,dhr)
    virt[day==the.day,max:=max.max+gap.to.use$max.norm]
    virt[day==the.day,min:=max.min+gap.to.use$min.norm.relative.to.min]
    virt[day==the.day,plugged.in.capacity:=gap.to.use$plugged.in.capacity]
    virt[,':='(max.max=NULL,max.min=NULL,gap.adjustment=NULL)]
  }
}
setkey(virt,final.type,veh.type,day,hr)
ggplot(virt[day<10],aes(x=hr,y=max))+geom_line()+geom_line(aes(y=min,colour='red'))+facet_wrap(veh.type~final.type)

n.bev <- length(u(soc[veh.type=='BEV']$person))
bev.scale <- vmt[veh.type=='BEV']$scale
virt[max<0,max:=0]
virt[min<0,min:=0]
for.switch <- virt[day <=7 & veh.type=='BEV',list(final.type,day,hour.of.day=dhr,hour.of.week=hr,max.cumulative.kwh=max*bev.scale/n.bev,min.cumulative.kwh=min*bev.scale/n.bev,max.power.kw=plugged.in.capacity/n.bev)]
for.switch[,day.of.week:=names(wdays)[match(day,wdays)]]

write.csv(for.switch,file='/Users/critter/GoogleDriveUCB/beam-collaborators/planning/vgi/beam-constraints-for-switch.csv')
