
library(colinmisc)
load.libraries(c('Hmisc','sqldf','GEOquery','stringr','plyr'))

source('~/Dropbox/ucb/vto/beam-all/beam-calibration/beam/src/main/R/debug.R')
source('~/Dropbox/ucb/vto/beam-all/beam-calibration/beam/src/main/R/vgi-functions.R')

# Get person attributes and vehicle types and join

peeps <- data.table(read.csv('/Users/critter/odrive/GoogleDriveUCB/beam-core/model-inputs/calibration-v2-old/person-attributes-from-reg-with-spatial-group.csv'))
vehs <- data.table(read.csv('/Users/critter/odrive/GoogleDriveUCB/beam-core/model-inputs/calibration-v2-old/vehicle-types-bigger-batteries-1.5x.csv'))
plug.types <- data.table(read.csv('/Users/critter/odrive/GoogleDriveUCB/beam-core/model-inputs/calibration-v2-old/charging-plug-types.csv'))
peeps <- join.on(peeps,vehs,'vehicleTypeId','id',c('batteryCapacityInKWh','vehicleClassName','fuelEconomyInKwhPerMile','maxLevel2ChargingPowerInKW','maxLevel3ChargingPowerInKW'))
peeps[,veh.type:='BEV']
peeps[vehicleClassName=='PHEV',veh.type:='PHEV']
peeps[vehicleClassName=='NEV',veh.type:='NEV']

out.dirs <- list()

# Base
out.dirs[['base']]            <- c('/Users/critter/Documents/beam/beam-output/calibration/calibration_2018-03-12_13-05-59-base/',0)
#out.dirs[['base-tou-night']]  <- c('/Users/critter/Documents/beam/beam-output/calibration/calibration_2018-03-12_13-05-59-base/',0)
#out.dirs[['base-tou-both']]   <- c('/Users/critter/Documents/beam/beam-output/calibration/calibration_2018-03-12_13-05-59-base/',0)
#out.dirs[['morework-8x']] <- c('/Users/critter/Documents/beam/beam-output/calibration/calibration_2018-03-09_22-27-18-morework-8x/',0)
#out.dirs[['morework-8x-tou-night']] <- c('/Users/critter/Documents/beam/beam-output/calibration/calibration_2018-03-09_22-27-18-morework-8x/',0)
#out.dirs[['morework-8x-tou-both']] <- c('/Users/critter/Documents/beam/beam-output/calibration/calibration_2018-03-09_22-27-18-morework-8x/',0)
#out.dirs[['morework-4x']] <- c('/Users/critter/Documents/beam/beam-output/calibration/calibration_2018-03-10_15-12-08-morework-4x/',0)
#out.dirs[['morework-4x-tou-night']] <- c('/Users/critter/Documents/beam/beam-output/calibration/calibration_2018-03-10_15-12-08-morework-4x/',0)
#out.dirs[['morework-4x-tou-both']] <- c('/Users/critter/Documents/beam/beam-output/calibration/calibration_2018-03-10_15-12-08-morework-4x/',0)
#out.dirs[['morework-2x']] <- c('/Users/critter/Documents/beam/beam-output/calibration/calibration_2018-03-21_16-50-05-morework-2x/',0)
#out.dirs[['morework-2x-tou-night']] <- c('/Users/critter/Documents/beam/beam-output/calibration/calibration_2018-03-21_16-50-05-morework-2x/',0)
#out.dirs[['morework-2x-tou-both']] <- c('/Users/critter/Documents/beam/beam-output/calibration/calibration_2018-03-21_16-50-05-morework-2x/',0)

scens <- names(out.dirs)
all.soc.sums <- list()
sessions.alls <- list()

scen <- scens[1]
for(scen in scens){
  plots.dir <- pp(out.dirs[[scen]][1],'/plots/')
  make.dir(plots.dir)
  the.out.dir <- out.dirs[[scen]][1]
  the.desired.iter <- out.dirs[[scen]][2]
  out.dir <- pp(the.out.dir,'ITERS/it.',the.desired.iter,'/')

  do.tou <- grepl('tou',scen)
  tou.str <- ifelse(do.tou,pp('-tou',str_split(scen,'-tou')[[1]][2]),'')

  if(file.exists(pp(out.dir,'ev',tou.str,'.Rdata'))){
    load(pp(out.dir,'ev',tou.str,'.Rdata'))
  }else{
    suppressWarnings(if(exists('df',mode='list'))rm('df'))
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
    ev <- df
    rm('df')

    ev[,native.order:=1:nrow(ev)]

    # first extract VMT data, then remove the travelled events and stranded folks, note that eVMT is estimated below
    ev <- join.on(ev,peeps,'person','personId',c('batteryCapacityInKWh','veh.type','fuelEconomyInKwhPerMile','maxLevel2ChargingPowerInKW','maxLevel3ChargingPowerInKW'))
    ev[,hr:=as.numeric(time)/3600]
    ev[,hour:=floor(hr)]
    stranded.peeps <- u(ev[choice=='stranded']$person)

    vmt <- ev[!person%in%stranded.peeps,list(miles=sum(as.numeric(distance),na.rm=T)*0.000621371,n.veh=length(u(person)),days=round(max(time)/24/3600)-1),by='veh.type']
    weekday.to.weekend <- 1.25 # http://onlinepubs.trb.org/onlinepubs/archive/conferences/nhts/Pendyala-Agarwal.pdf   https://www.arb.ca.gov/research/weekendeffect/we-wd_fr_5-7-04.pdf
    vmt[,vmt:=miles/(n.veh)/days*(253 + 112/weekday.to.weekend)] # 261 weekdays per year - 8 holidays

    vmt[veh.type=='BEV',target:=11e3]
    vmt[veh.type=='PHEV',target:=7.6e3]

    ev <- ev[!type=='travelled' & !person%in%stranded.peeps]

    setkey(ev,native.order)
    ev[,actType:=as.character(actType)]
    ev[actType=="",actType:=NA]
    ev[,actType:=ifelse(type=='BeginChargingSessionEvent',repeat_last(as.character(actType),forward=F),as.character(NA)),by=c('scenario','person')]
    ev[type=='BeginChargingSessionEvent' & is.na(actType),actType:='Home',by=c('scenario','person')]

    ev <- ev[!type%in%c('arrival','departure','travelled','actend','PreChargeEvent')]

    # categorize each charger into Home, Work, Public
    if(length(grep('morework-200',scen))>0){
      sites  <- data.table(read.csv('/Users/critter/odrive/GoogleDriveUCB/beam-core/model-inputs/calibration-v2-old/charging-sites-cp-more-work-200pct.csv',stringsAsFactors=F))
    }else if(length(grep('morework-2x',scen))>0){
      sites  <- data.table(read.csv('/Users/critter/odrive/GoogleDriveUCB/beam-core/model-inputs/calibration-v2-old/charging-sites-cp-more-work-100pct.csv',stringsAsFactors=F))
    }else if(length(grep('morework-50',scen))>0){
      sites  <- data.table(read.csv('/Users/critter/odrive/GoogleDriveUCB/beam-core/model-inputs/calibration-v2-old/charging-sites-cp-more-work-50pct.csv',stringsAsFactors=F))
    }else if(length(grep('morework-4x',scen))>0){
      sites  <- data.table(read.csv('/Users/critter/Documents/beam/input/nersc/calibration-v2/charging-sites-cp-more-work-300pct.csv',stringsAsFactors=F))
    }else if(length(grep('morework-8x',scen))>0){
      sites  <- data.table(read.csv('/Users/critter/Documents/beam/input/nersc/calibration-v2/charging-sites-cp-more-work-700pct.csv',stringsAsFactors=F))
    }else{
      sites  <- data.table(read.csv('/Users/critter/odrive/GoogleDriveUCB/beam-core/model-inputs/calibration-v2-old/charging-sites-cp-revised-2017-07.csv',stringsAsFactors=F))
    }
    sites[,siteType:='Public']
    sites[policyID==7,siteType:='Work']
    #points <- data.table(read.csv('/Users/critter/odrive/GoogleDriveUCB/beam-core/model-inputs/calibration-v2-old/charging-points-cp-revised-2017-07.csv',stringsAsFactors=F))
    #points <- join.on(points,sites,'siteID','id','policyID')

    ev[,site:=as.numeric(site)]
    ev <- join.on(ev,sites,'site','id','siteType')
    ev[site<0,siteType:='Residential']
    setkey(ev,scenario,hr,native.order)

    # First fill in the SOC gaps, plugType, vehilceBatteryCap, and activityType
    ev[,soc:=as.numeric(repeat_last(soc)),by=c('scenario','person')]
    ev[,time:=as.numeric(time)]
    #ev[,kwhNeeded:=as.numeric(kwhNeeded)]
    kwhNeeded <- 0
    ev[,plugType:=as.character(plugType)]
    ev[plugType=='',plugType:=NA]
    ev[,plugType:=repeat_last(plugType),by=c('scenario','person')]

    # ev[person==6114071 & decisionEventId==17]

    # Now focus in on just charge session events
    ev <- ev[type%in%c('BeginChargingSessionEvent','EndChargingSessionEvent','UnplugEvent')]

    # Assign battery cap and charging rates
    #ev[,kw:=c("j-1772-2"=6.7,"sae-combo-3"=50,"j-1772-1"=1.92,"chademo"=50,"tesla-2"=20,"tesla-3"=120)[plugType]]
    ev[,maxPlugKw:=c("j-1772-2"=20,"sae-combo-3"=240,"j-1772-1"=1.92,"chademo"=50,"tesla-2"=20,"tesla-3"=120)[plugType]]
    #ev[,kw.new:=ifelse(maxPlugKw<=2, maxPlugKw,ifelse(maxPlugKw<=20,maxLevel2ChargingPowerInKW, maxLevel3ChargingPowerInKW))]
    ev[,kw:=ifelse(maxPlugKw<=2, maxPlugKw,ifelse(maxPlugKw<=20,maxLevel2ChargingPowerInKW, maxLevel3ChargingPowerInKW))]
    
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
    if(do.tou){
      shift.if.possible <- function(final.type,hr,energy.level,kw,jitter.band=0,shift.to.hour=0){
        midnights <- seq(shift.to.hour,shift.to.hour+24*7,by=24)+runif(1,1-jitter.band,1+jitter.band)
        midnight <- midnights[which(hr[1] < midnights & hr[3] > midnights)]
        if(length(midnight)>0){
          midnight <- midnight[1] #in case a long session spans 2 breaks
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
        return(hr)
      }
      if(grepl('night',scen) | grepl('both',scen)){
        ev[,hr:=shift.if.possible(final.type,hr,energy.level,kw,2,0),by=c('scenario','person','decisionEventId')]
      }
      if(grepl('day',scen) | grepl('both',scen)){
        ev[,hr:=shift.if.possible(final.type,hr,energy.level,kw,1,11),by=c('scenario','person','decisionEventId')]
      }
      ev[,hour:=floor(hr)]
    }

    # 4191098

    ev[,':='(energy.level.min=c(energy.level[1],energy.level[1],energy.level[2]),
             hr.min=c(hr[1],hr[3] - (hr[2] - hr[1]),hr[3])),by=c('scenario','person','decisionEventId')]
    #peeps.to.fix <- ev[,all(hr.min==hr.min[1]),by=c('scenario','person','decisionEventId')][V1==T]$person
    #ev[person%in%peeps.to.fix,hr.min:=hr.min+c(0,0,0.1),by=c('scenario','person','decisionEventId')]

    bad.peeps <- u(ev[is.na(kw)]$person)
    my.cat(pp('Removing ',length(bad.peeps),' peeps'))
    ev <- ev[!person%in%bad.peeps]

    # Make our power be average power derived from energy and time of session instead of charger power which is easy to mix up what assumptions were used
    ev[,kw:=(energy.level[2]-energy.level[1])/(hr[2]-hr[1]),by=c('scenario','person','decisionEventId')]

    # Now back out the eVMT from the charge delivered
    evmt <- ev[,.(veh.type=veh.type[1],evmt=(energy.level[2]-energy.level[1])/fuelEconomyInKwhPerMile[1]),by=c('scenario','person','decisionEventId')]
    evmt <- evmt[,.(evmt=sum(evmt)/length(u(person))),by='veh.type']
    vmt <- join.on(vmt,evmt,'veh.type','veh.type')
    vmt[,evmt:=evmt*(253 + 112/weekday.to.weekend)/days]
    vmt[veh.type=='BEV',evmt:=vmt]
    vmt[,scale:=target/evmt]
    vmt[veh.type=='BEV',scale:=scale/1.033635]
    vmt[veh.type=='PHEV',scale:=scale/1.02362]
    save(ev,vmt,file=pp(out.dir,'ev',tou.str,'.Rdata'))
  }

  ### Making an optional plot of sessions and their flexibility
  sessions.all <- ev[,.(start = hr[1], plugTime = tail(hr,1) - head(hr,1),chargeTime = hr[2]-hr[1],energy = energy.level[3] - energy.level[1], power=kw[1], final.type=final.type[1]),by=c('scenario','person','decisionEventId')]
  sessions.all[,scen:=scen]
  sessions.alls[[length(sessions.alls)+1]] <- sessions.all
}

  ##################################################################
  # First aggregate for plexos
  ##################################################################
  if(file.exists(pp(out.dir,'soc',tou.str,'.Rdata'))){
       load(pp(out.dir,'soc',tou.str,'.Rdata'))
  }else{
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

    soc <- rbindlist(list(soc.raw[,list(scenario,person,hr,energy.level,kw,constraint='max',final.type,veh.type)],soc.raw[,list(scenario,person,hr=hr.min,energy.level=energy.level.min,kw=kw.min,constraint='min',final.type,veh.type)]))
    soc[,d.energy.level:=c(0,ifelse(diff(energy.level)>0,diff(energy.level),0)),by=c('scenario','person','final.type','constraint','veh.type')]
    soc[,hr.int:=floor(hr)]
    soc <- soc[,.(kw=max(kw),d.energy.level=sum(d.energy.level)),by=c('scenario','hr.int','person','final.type','constraint','veh.type')]
    soc[,hr:=hr.int]
    soc[,cumul.energy:=cumsum(d.energy.level),by=c('scenario','person','final.type','constraint')]
    setkey(soc,scenario,hr,person,final.type,constraint)
    soc[,plugged.in.capacity:=ifelse(abs(diff(cumul.energy))>1e-6,kw[1],0),by=c('hr','scenario','person','final.type')]
    rm('soc.raw')
    save(soc,file=pp(out.dir,'soc',tou.str,'.Rdata'))
  }

  # Final categorization of load
  soc.sum <- soc[ constraint=='max',list(d.energy=sum(d.energy.level)),by=c('scenario','hr','final.type','veh.type')]
  setkey(soc.sum,scenario,hr,final.type,veh.type)
  p <- ggplot(soc.sum,aes(x=hr,y=d.energy,fill=veh.type))+geom_bar(stat='identity')+facet_grid(final.type~scenario,scales='free_y')+labs(title="BEAM Average Load",x="Hour",y="Load (kW)")
  pdf.scale <- .6
  ggsave(pp(plots.dir,'load-by-type-',scen,'.pdf'),p,width=15*pdf.scale,height=9*pdf.scale,units='in')
  all.soc.sums[[length(all.soc.sums)+1]] <- soc.sum

  #ggplot(soc[hr==floor(hr) & hr<160,list(cumul.energy=sum(cumul.energy)),by=c('scenario','hr','constraint','final.type','veh.type')],aes(x=hr,y=cumul.energy,colour=constraint))+geom_line()+facet_wrap(scenario~veh.type~final.type)
  #soc.sum <- soc[hr==floor(hr) & hr>=27+24*5 & hr<51+24*5 &constraint=='max',list(d.energy=sum(d.energy.level)),by=c('scenario','hr','constraint','final.type')]
  #setkey(soc.sum,scenario,hr,final.type)
  #ggplot(soc.sum,aes(x=hr%%24,y=d.energy,fill=final.type))+geom_bar(stat='identity')+facet_grid(final.type~scenario,scales='free_y')+labs(title="BEAM Average Load",x="Hour",y="Load (kW)")

  # Load CP data and create scaling factors for turning BEAM workday output into a full week of constraints
  load(pp('/Users/critter/odrive/GoogleDriveUCB/beam-core/data/chargepoint/cp.Rdata'))
  cp[category=='Workplace',type:='Work']
  cp[type=='Commercial',type:='Public']
  wdays <- c('Sun'=1,'Mon'=2,'Tus'=3,'Wed'=4,'Thu'=5,'Fri'=6,'Sat'=7)
  cp[,start.wday:=factor(names(wdays[start.wday]),levels=names(wdays))]
  cp.hr <- cp[,list(kw=sum(kw),n.sessions=length(kw)),by=c('start.month','start.mday','start.wday','hour.of.week','type')][,list(kw=mean(kw),n.sessions=mean(n.sessions),wday=start.wday[1]),by=c('hour.of.week','type')]
  ggplot(cp.hr,aes(x=hour.of.week%%24,y=kw,colour=factor(wday)))+geom_line(lwd=1.5)+facet_wrap(~type,scales='free_y')+labs(title="ChargePoint Average Load",x="Hour",y="Load (kW)",colour="Day of Week")
  cp.day <- cp[,list(kw=sum(kw)),by=c('start.month','start.mday','start.wday','hour.of.week','type')][,list(kw=mean(kw)),by=c('start.wday','type')]
  cp.day.norm <- cp.day[,list(norm.load= kw/mean(kw[!start.wday%in%c('Sat','Sun')]),wday=start.wday),by='type']

  # Turn aggregated constraints into virtual battery cumul energy, scale by day of week, then repeat for a year

  shave.peak <- function(x,y,shave.start,shave.end){
    c(y[x<shave.start],  approx(x[x%in%c(shave.start,shave.end)],y[x%in%c(shave.start,shave.end)],xout=x[x>=shave.start & x<=shave.end])$y, y[x>shave.end])
  }
  soc.weekday <- copy(soc)
  soc.weekend <- copy(soc)
  # Shift the target day to later in the week without re-writing code below (which pulls out day 2)
  soc.weekday[,hr:=hr-24*3]
  soc.weekend[,hr:=hr-24*3]
  if(do.tou & !grepl('night',scen)){
    shave.start <- 33; shave.end <- 39
  }else{
    shave.start <- 28; shave.end <- 36
  }
  soc.weekend[final.type=='Work',':='(d.energy.level=shave.peak(hr,d.energy.level,shave.start,shave.end)),by=c('scenario','person','final.type','veh.type','constraint')]
  soc.weekend[,':='(cumul.energy=cumsum(d.energy.level)),by=c('scenario','person','final.type','veh.type','constraint')]

  gap.weekday <- gap.analysis(soc.weekday[hr>=30 & hr<=54,list(cumul.energy=sum(cumul.energy),plugged.in.capacity=sum(plugged.in.capacity)),by=c('hr','constraint','final.type','veh.type')])
  gap.weekend <- gap.analysis(soc.weekend[hr>=30 & hr<=54,list(cumul.energy=sum(cumul.energy),plugged.in.capacity=sum(plugged.in.capacity)),by=c('hr','constraint','final.type','veh.type')])

  # Note, we pad with a day on both ends, this is designed for 2024 where Jan 1 is a Monday, so the padded day in the
  # front is a Sunday.
  # Also, we proceed on a 4am today to 3am in next day schedule and then fill the first 3 hours in the first day at the end
  virt <- data.table(expand.grid(list(day=1:9,dhr=6:29,final.type=u(gap.weekday$final.type),veh.type=u(gap.weekday$veh.type))))
  setkey(virt,final.type,veh.type,day,dhr)
  virt[,hr:=(day-1)*24+dhr]
  virt[,':='(max=0,min=0)]
  for(the.day in 1:9){
    the.wday <- (the.day-1)%%7+1
    if(the.wday>1 & the.wday<7){
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
      #max.mins[,gap.adjustment:=ifelse(max.current.gap>max.desired.gap,max.current.gap-max.desired.gap,0)]
      max.mins[,gap.adjustment:=max.current.gap-max.desired.gap]
      virt <- join.on(virt,max.mins,c('final.type','veh.type'),c('final.type','veh.type'),c('max.max','max.min','gap.adjustment'))
      #if(the.wday==1){
        virt[,max.min:=max.min+gap.adjustment]
        

      #}
      setkey(virt,final.type,veh.type,day,dhr)
      virt[day==the.day,max:=max.max+gap.to.use$max.norm]
      virt[day==the.day,min:=max.min+gap.to.use$min.norm.relative.to.min]
      if(the.day>1){
        only.increasing <- function(x){
          while(any(diff(x)<0)){
            i <- which(diff(x)<0)[1]
            x[i+1] <- x[i]
          }
          x
        }
        virt[day%in%c(the.day-1,the.day),min:=only.increasing(min),by=c('final.type','veh.type')]
      }
      virt[min>max,min:=max]
      virt[day==the.day,plugged.in.capacity:=gap.to.use$plugged.in.capacity]
      virt[,':='(max.max=NULL,max.min=NULL,gap.adjustment=NULL)]
    }
  }
  virt[,gap:=max-min]
  start.diffs <- virt[dhr%in%23:24 & day==9,.(dmax=diff(max)),by=c('final.type','veh.type')]
  virt[day==9 & dhr>24,':='(hr=hr-9*24,day=1)]
  setkey(virt,final.type,veh.type,day,hr)
  virt[hr<6,max:=c(-rev(cumsum(rev(diff(virt$max[1:5])))),0),by=c('final.type','veh.type')]
  virt[hr<6,min:=c(-rev(cumsum(rev(diff(virt$min[1:5])))),0),by=c('final.type','veh.type')]
  virt <- join.on(virt,start.diffs,c('final.type','veh.type'),c('final.type','veh.type'),'dmax')
  virt[,max:=dmax+max-min(max),by=c('final.type','veh.type')]
  virt[,min:=max-gap,by=c('final.type','veh.type')]
  virt[,day:=floor((hr+23)/24)]
  p <- ggplot(virt,aes(x=hr,y=max/1e6))+geom_line()+geom_line(aes(y=min/1e6),colour='red')+facet_wrap(veh.type~final.type)
  pdf.scale <- .6
  ggsave(pp(plots.dir,'constraint-curves-',scen,'.pdf'),p,width=15*pdf.scale,height=9*pdf.scale,units='in')
  #ggplot(virt[day<10],aes(x=hr,y=plugged.in.capacity))+geom_line()+facet_wrap(veh.type~final.type)

  # Put final constraints needed by PLEXOS into a table
  plexos.constraints <- virt[day > 1,list(max=sum(max),min=sum(min),plugged.in.capacity=sum(plugged.in.capacity)),by=c('hr','veh.type')][,list(hour=head(hr,-1),pev.inflexible.load.mw=diff(max)/1000,plexos.battery.min.mwh=head(max - min,-1)/1000,plugged.in.charger.capacity=head(plugged.in.capacity/1000,-1)),by=c('veh.type')]
  plexos.constraints[,hour:=hour-24]
  plexos.constraints[,plexos.battery.max.discharge:=pev.inflexible.load.mw]
  plexos.constraints[,plexos.battery.max.mwh:=max(plexos.battery.min.mwh),by=c('veh.type')]
  plexos.constraints[,plexos.battery.min.mwh:=plexos.battery.max.mwh-plexos.battery.min.mwh,by=c('veh.type')]
  plexos.constraints[,plexos.battery.max.charge:=plugged.in.charger.capacity - pev.inflexible.load.mw]
  plexos.constraints[,plugged.in.charger.capacity:=NULL]

  # Join in the VMT based scaling factors
  plexos.constraints <- join.on(plexos.constraints,vmt,'veh.type','veh.type','scale')

  if(scen=='base'){
    plexos.constraints.base <- copy(plexos.constraints[hour<6])
  }

  # Finally, scale according to the scenarios developed by Julia
  scenarios <- load.scenarios()
  scenarios <- scenarios[veh.type.split=='6040']

  results.dir.base <- '/Users/critter/odrive/GoogleDriveUCB/beam-collaborators/planning/vgi/vgi-constraints-for-plexos-2024-tou-v13'
  make.dir(results.dir.base)
  make.dir(pp(results.dir.base,'/',scen))
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
        if(scen=='base'){
          scale.to.base <- 1
          energy.for.scale.to.base <- sum(to.write$value * to.write$pev.inflexible.load.mw * to.write$scale)
          make.dir(pp(results.dir.base,'/base-annual-energy-for-scaling'))
          save(energy.for.scale.to.base,file=pp(results.dir.base,'/base-annual-energy-for-scaling/',the.utility,'-',pen,'-',veh.split,'.Rdata'))
        }else{
          if(do.tou){
            to.write[,Day:=mday(to.posix('2024-01-01',tz='GMT')+(hour-1)*3600)]
            to.write[,Period:=(hour-1)%%24+1]
            to.write <- join.on(to.write,plexos.constraints.base,c('veh.type','Period'),c('veh.type','hour'),'pev.inflexible.load.mw','base.')
            to.write[Day==1 & Period<6,pev.inflexible.load.mw:=base.pev.inflexible.load.mw]
            to.write[,base.pev.inflexible.load.mw:=NULL]
            to.write[,Period:=NULL]
            to.write[,Day:=NULL]
            setkey(to.write,hour)
          }
          rm('energy.for.scale.to.base')
          load(file=pp(results.dir.base,'/base-annual-energy-for-scaling/',the.utility,'-',pen,'-',veh.split,'.Rdata'))
          scale.to.base <- energy.for.scale.to.base / sum(to.write$value * to.write$pev.inflexible.load.mw * to.write$scale)
        }
        to.write <- to.write[,list(pev.inflexible.load.mw=sum(pev.inflexible.load.mw*value*scale*scale.to.base),
                       plexos.battery.max.mwh=sum(plexos.battery.max.mwh*value*scale*scale.to.base),
                       plexos.battery.min.mwh=sum(plexos.battery.min.mwh*value*scale*scale.to.base),
                       plexos.battery.max.discharge=sum(plexos.battery.max.discharge*value*scale*scale.to.base),
                       plexos.battery.max.charge=sum(plexos.battery.max.charge*value*scale*scale.to.base)
                       ),by='hour']
        write.csv(to.write,pp(results.dir.base,'/',scen,'/',pen,'_',veh.split,'_',the.utility,'.csv'),row.names=F)
        to.write[,Year:=2024]
        to.write[,Month:=month(to.posix('2024-01-01',tz='GMT')+(hour-1)*3600)]
        to.write[,Day:=mday(to.posix('2024-01-01',tz='GMT')+(hour-1)*3600)]
        to.write[,Period:=(hour-1)%%24+1]
        write.csv(to.write[,list(Year,Month,Day,Period,Value=plexos.battery.max.charge)],pp(results.dir.base,'/',scen,'/',pen,'_',veh.split,'_',the.utility,'_max_charge.csv'),row.names=F)
        write.csv(to.write[,list(Year,Month,Day,Period,Value=plexos.battery.max.discharge)],pp(results.dir.base,'/',scen,'/',pen,'_',veh.split,'_',the.utility,'_max_discharge.csv'),row.names=F)
        write.csv(to.write[,list(Year,Month,Day,Period,Value=plexos.battery.min.mwh/1000)],pp(results.dir.base,'/',scen,'/',pen,'_',veh.split,'_',the.utility,'_min_volume.csv'),row.names=F)
        reservoirs.max[[length(reservoirs.max)+1]] <- data.table(Utility=the.utility,Year=2024,Scenario=pen,BEV_to_PHEV=veh.split,plexos.battery.max.GWh=to.write[1]$plexos.battery.max.mwh/1e3,plexos.battery.max.mwh=to.write[1]$plexos.battery.max.mwh)
        to.write[,':='(util=the.utility,pen=pen,split=veh.split)]
        save.all[[length(save.all)+1]] <- to.write
      }
    }
  }
  reservoirs.max <- rbindlist(reservoirs.max)
  save.all <- rbindlist(save.all)
  save.all[,scenario:=scen]
  save(save.all,file=pp(results.dir.base,'/',scen,'/all-plexos-inputs.Rdata'))
  write.csv(reservoirs.max,pp(results.dir.base,'/',scen,'/Table_of_Max_Volume_of_Smart_Charging_Reservoirs.csv'),row.names=F)
}

# Plotting flexibility in a simple way
if(F){
  sessions.all <- rbindlist(sessions.alls)
  sessions <- sessions.all[start>49 & start<=73]
  sessions[,hour:=floor(start-48)]
  sessions[,flex:=plugTime-chargeTime]
  flex.bins <- c('beg'=0,'0-2'=2,'2-4'=4,'4-8'=8,'8-12'=12,'12+'=1000)
  sessions[,flex.bin:=names(flex.bins)[findInterval(flex,flex.bins)+1]]
  sessions[,flex.bin:=factor(flex.bin,levels=names(flex.bins))]
  sessions[,scen:=revalue(factor(scen),c('base'='Base','morework-4x'='4X Workplace Chargers','morework-8x'='8X Workplace Chargers'))]
  setkey(sessions,hour,flex.bin,final.type,scen)
  #ggplot(sessions[,.(n=.N),by=c('hour','flex.bin','final.type')],aes(x=hour,y=n,fill=flex.bin))+geom_bar(stat='identity')+facet_grid(scen~final.type)+labs(x="Charging Start Hour",y="# of Sessions Initiated",fill="Hours of flexibility")
  #dev.new();
  ggplot(sessions[,.(n=.N,energy=sum(energy)),by=c('scen','hour','flex.bin','final.type')],aes(x=hour,y=energy/1e3,fill=flex.bin))+geom_bar(stat='identity')+
    facet_grid(scen~final.type)+labs(x="Charging Session Start Hour",y="Energy Demanded in Charging Session (MWh)",fill="Hours of flexibility") + 
    theme_bw() + theme(panel.grid.major = element_blank(), panel.grid.minor = element_blank(), axis.line = element_line(colour = "black"), strip.text.x = element_text(size = 10, face = "bold"), strip.text.y = element_text(size = 10, face = "bold"), axis.text.x = element_text(angle=0, size=10), axis.title.x = element_text(size=12) , axis.text.y = element_text(angle=0, size=10), axis.title.y = element_text(size=12),legend.position="bottom", legend.title = element_text( size = 12), legend.text = element_text( size = 12)  , plot.title = element_text(size=14, hjust=0.5))  + 
    scale_x_continuous(breaks=seq(0,24,6))

  # Make another plot where we over-represent fast charging and we emulate 100kW and 350kW fast chargers
  sessions[,fast:=power>25]
  sessions[,i:=1:(.N)]
  sessions.more.fast <- copy(sessions)
  sessions.more.fast.ultra <- copy(sessions)
  sessions.more.fast.ultra.ultra <- copy(sessions)
  do.not.replace <- c()
  for(n in 1:20){
    for(fast.i in 1:nrow(sessions[fast==T])){
      to.replace <- sessions.more.fast[fast==T][fast.i]
      replace.target <- sessions.more.fast[fast==F & !i%in%do.not.replace][abs(energy - to.replace$energy) < 0.5 & abs(start - to.replace$start) < 1 & final.type == to.replace$final.type]
      if(nrow(replace.target)==0){
        replace.target <- sessions.more.fast[fast==F & !i%in%do.not.replace][abs(energy - to.replace$energy) < 1 & abs(start - to.replace$start) < 2 & final.type == to.replace$final.type]
        if(nrow(replace.target)==0){
          replace.target <- sessions.more.fast[fast==F & !i%in%do.not.replace][abs(energy - to.replace$energy) < 2 & abs(start - to.replace$start) < 4 & final.type == to.replace$final.type]
          if(nrow(replace.target)==0){
            replace.target <- sessions.more.fast[fast==F & !i%in%do.not.replace][abs(energy - to.replace$energy) < 2 & abs(start - to.replace$start) < 4]
            if(nrow(replace.target)==0){
              replace.target <- sessions.more.fast[fast==F & !i%in%do.not.replace][abs(energy - to.replace$energy) < 2 & abs(start - to.replace$start) < 8]
              if(nrow(replace.target)==0){
                replace.target <- sessions.more.fast[fast==F & !i%in%do.not.replace][abs(energy - to.replace$energy) < 2]
                if(nrow(replace.target)==0){
                  replace.target <- sessions.more.fast[fast==F & !i%in%do.not.replace][abs(energy - to.replace$energy) < 4]
                  if(nrow(replace.target)==0){
                    replace.target <- sessions.more.fast[fast==F & !i%in%do.not.replace][abs(energy - to.replace$energy) < 8]
                    if(nrow(replace.target)==0){
                      replace.target <- sessions.more.fast[fast==F & !i%in%do.not.replace]
                    }
                  }
                }
              }
            }
          }
        }
      }
      if(nrow(replace.target)==0){
        stop('hey')
      }
      replace.target.i <- replace.target[sample(nrow(replace.target),1)]$i
      sessions.more.fast[i==replace.target.i,':='(energy=to.replace$energy,plugTime=to.replace$plugTime,chargeTime=to.replace$chargeTime,power=to.replace$power,person='resampled',decisionEventId='resampled',hour=to.replace$hour)]
      sessions.more.fast.ultra[i==replace.target.i,':='(energy=to.replace$energy,plugTime=to.replace$plugTime/2,chargeTime=to.replace$chargeTime/2,power=100,person='resampled',decisionEventId='resampled',hour=to.replace$hour)]
      sessions.more.fast.ultra.ultra[i==replace.target.i,':='(energy=to.replace$energy,plugTime=to.replace$plugTime/7,chargeTime=to.replace$chargeTime/7,power=350,person='resampled',decisionEventId='resampled',hour=to.replace$hour)]
      do.not.replace <- c(do.not.replace,replace.target.i)
    }
  }
  sessions.more.fast[,fast:=power>25]
  sessions.more.fast[,scen:='6% Sessions Use 50kW Fast Chargers']
  sessions.more.fast[,flex:=plugTime-chargeTime]
  sessions.more.fast[,flex.bin:=names(flex.bins)[findInterval(flex,flex.bins)+1]]
  sessions.more.fast[,flex.bin:=factor(flex.bin,levels=names(flex.bins))]
  sessions.more.fast.ultra[,fast:=power>25]
  sessions.more.fast.ultra[,scen:='6% Sessions Use 100kW Fast Chargers']
  sessions.more.fast.ultra[,flex:=plugTime-chargeTime]
  sessions.more.fast.ultra[,flex.bin:=names(flex.bins)[findInterval(flex,flex.bins)+1]]
  sessions.more.fast.ultra[,flex.bin:=factor(flex.bin,levels=names(flex.bins))]
  sessions.more.fast.ultra.ultra[,fast:=power>25]
  sessions.more.fast.ultra.ultra[,scen:='6% Sessions Use 350kW Fast Chargers']
  sessions.more.fast.ultra.ultra[,flex:=plugTime-chargeTime]
  sessions.more.fast.ultra.ultra[,flex.bin:=names(flex.bins)[findInterval(flex,flex.bins)+1]]
  sessions.more.fast.ultra.ultra[,flex.bin:=factor(flex.bin,levels=names(flex.bins))]
  ggplot(rbindlist(list(sessions,sessions.more.fast,sessions.more.fast.ultra,sessions.more.fast.ultra.ultra))[,.(n=.N,energy=sum(energy)),by=c('scen','hour','flex.bin')],aes(x=hour,y=energy/1e3,fill=flex.bin))+geom_bar(stat='identity')+
    facet_wrap(~scen)+labs(x="Charging Session Start Hour",y="Energy Demanded in Charging Session (MWh)",fill="Hours of flexibility") + 
    theme_bw() + theme(panel.grid.major = element_blank(), panel.grid.minor = element_blank(), axis.line = element_line(colour = "black"), strip.text.x = element_text(size = 10, face = "bold"), strip.text.y = element_text(size = 10, face = "bold"), axis.text.x = element_text(angle=0, size=10), axis.title.x = element_text(size=12) , axis.text.y = element_text(angle=0, size=10), axis.title.y = element_text(size=12),legend.position="bottom", legend.title = element_text( size = 12), legend.text = element_text( size = 12)  , plot.title = element_text(size=14, hjust=0.5))  + 
    scale_x_continuous(breaks=seq(0,24,6))
  ggplot(rbindlist(list(sessions,sessions.more.fast,sessions.more.fast.ultra,sessions.more.fast.ultra.ultra))[,.(n=.N,power=sum(power)),by=c('scen','hour','flex.bin')],aes(x=hour,y=power/1e3,fill=flex.bin))+geom_bar(stat='identity')+
    facet_wrap(~scen)+labs(x="Charging Session Start Hour",y="Power Demanded in Charging Session (MW)",fill="Hours of flexibility") + 
    theme_bw() + theme(panel.grid.major = element_blank(), panel.grid.minor = element_blank(), axis.line = element_line(colour = "black"), strip.text.x = element_text(size = 10, face = "bold"), strip.text.y = element_text(size = 10, face = "bold"), axis.text.x = element_text(angle=0, size=10), axis.title.x = element_text(size=12) , axis.text.y = element_text(angle=0, size=10), axis.title.y = element_text(size=12),legend.position="bottom", legend.title = element_text( size = 12), legend.text = element_text( size = 12)  , plot.title = element_text(size=14, hjust=0.5))  + 
    scale_x_continuous(breaks=seq(0,24,6))
}



# Compare the scenarios against each other
all <- list()
for(scen in scens){
  load(pp(results.dir.base,'/',scen,'/all-plexos-inputs.Rdata'))
  all[[length(all)+1]] <- save.all
}
all <- rbindlist(all)
all.agg <- melt(all,measure.vars=c('pev.inflexible.load.mw','plexos.battery.max.mwh','plexos.battery.min.mwh','plexos.battery.max.discharge','plexos.battery.max.charge'))[,.(value=sum(value)),by=c('hour','variable','Year','Month','Day','Period','pen','split','scenario')]
all.soc.sums <- rbindlist(all.soc.sums)

p <- ggplot(all.agg[Month==4 & Day < 8 & pen == 'High' & split=='6040'],aes(x=hour,y=value,colour=scenario))+geom_line()+facet_wrap(~variable,scales='free_y')
ggsave(pp(results.dir.base,'/plexos-inputs-by-scenario.pdf'),p,width=15*pdf.scale,height=9*pdf.scale,units='in')
for(vari in u(all.agg$variable)){
  dev.new()
  p <- ggplot(all.agg[Month==4 & Day < 8 & pen == 'High' & split=='6040' & variable==vari],aes(x=hour,y=value,colour=scenario))+geom_line()+labs(title=vari)
  ggsave(pp(results.dir.base,'/plexos-inputs-by-scenario-',vari,'.pdf'),p,width=15*pdf.scale,height=9*pdf.scale,units='in')
}


if(F){
  ## Go through and splice together data sets from differen TOU scenarios
  #scenarios <- load.scenarios()

  #results.dir.base <- '/Users/critter/odrive/GoogleDriveUCB/beam-collaborators/planning/vgi/vgi-constraints-for-plexos-2025-tou-v4'

  #scen.base <- 'morework-100pct'
  #for(scen.base in c('morework-2x')){
  for(scen.base in c('base','morework-2x','morework-4x','morework-8x')){
    the.utility <- scenarios$Electric.Utility[3]
    pen <- scenarios$penetration[1]
    veh.split <- scenarios$veh.type.split[1]
    reservoirs.max <- list()
    save.all <- list()
    for(the.utility in u(scenarios$Electric.Utility)){
      for(pen in u(scenarios$penetration)){
        for(veh.split in u(scenarios$veh.type.split)){
          # For months Jan-Feb, May-Dec, we use tou-night as is
          scen <- pp(scen.base,'-tou-night')
          tou.night <- data.table(read.csv(pp(results.dir.base,'/',scen,'/',pen,'_',veh.split,'_',the.utility,'.csv')))

          tou.night[,Year:=2024]
          tou.night[,Month:=month(to.posix('2024-01-01',tz='GMT')+(hour-1)*3600)]
          tou.night[,Day:=mday(to.posix('2024-01-01',tz='GMT')+(hour-1)*3600)]
          tou.night[,Period:=(hour-1)%%24+1]
          tou.night[,wday:=wday(to.posix('2024-01-01',tz='GMT')+(hour-1)*3600)]

          # For months Mar-Apr we use tou-both for weekdays and tou-night for weekends
          scen <- pp(scen.base,'-tou-both')
          tou.both <- data.table(read.csv(pp(results.dir.base,'/',scen,'/',pen,'_',veh.split,'_',the.utility,'.csv')))

          tou.both[,Year:=2024]
          tou.both[,Month:=month(to.posix('2024-01-01',tz='GMT')+(hour-1)*3600)]
          tou.both[,Day:=mday(to.posix('2024-01-01',tz='GMT')+(hour-1)*3600)]
          tou.both[,Period:=(hour-1)%%24+1]
          tou.both[,wday:=wday(to.posix('2024-01-01',tz='GMT')+(hour-1)*3600)]

          tou.final <- rbindlist(list(tou.night[!Month%in%3:4],tou.night[Month%in%3:4 & wday %in% 6:7],tou.both[Month%in%3:4 & wday <= 5]))
          setkey(tou.final,hour)

          scen <- pp(scen.base,'-tou-final')
          make.dir(pp(results.dir.base,'/',scen))

          write.csv(tou.final[,list(hour,pev.inflexible.load.mw,plexos.battery.max.mwh,plexos.battery.min.mwh,plexos.battery.max.discharge,plexos.battery.max.charge)],file=pp(results.dir.base,'/',scen,'/',pen,'_',veh.split,'_',the.utility,'.csv'),row.names=F)
          write.csv(tou.final[,list(Year,Month,Day,Period,Value=plexos.battery.max.charge)],pp(results.dir.base,'/',scen,'/',pen,'_',veh.split,'_',the.utility,'_max_charge.csv'),row.names=F)
          write.csv(tou.final[,list(Year,Month,Day,Period,Value=plexos.battery.max.discharge)],pp(results.dir.base,'/',scen,'/',pen,'_',veh.split,'_',the.utility,'_max_discharge.csv'),row.names=F)
          write.csv(tou.final[,list(Year,Month,Day,Period,Value=plexos.battery.min.mwh/1000)],pp(results.dir.base,'/',scen,'/',pen,'_',veh.split,'_',the.utility,'_min_volume.csv'),row.names=F)

        }
      }
    }
  }

  # Export for collaborators (Baptist&Reshma and SERC-SEIN)

  setkey(virt,final.type,veh.type,hr)
  virt[,load:=c(NA,diff(max)),by=c('final.type','veh.type')]
  to.write <- join.on(virt,vmt,'veh.type','veh.type','n.veh')[day>1 & day<9]
  to.write[,load:=load/n.veh]
  to.write[,wday:=names(wdays[(1+(day-1)%%7)])]

  write.csv(na.omit(to.write[,.(charger.location=final.type,vehicle.type=veh.type,day=wday,hour=dhr,load)]),file=pp(results.dir.base,'/',scen,'/beam-sf-bay-normalized-load.csv'))
  write.csv(vmt[,.(VMT=vmt,eVMT=evmt,target.eVMT=target)],file=pp(results.dir.base,'/',scen,'/beam-sf-bay-simulated-annual-vmt.csv'))

  to.write <- ev[,.(vid=person[1],pev_type=veh.type[1],battery_capacity=batteryCapacityInKWh[1],start_time=time[1],end_time_chg=time[2],end_time_prk=time[3],dest_type=final.type[1],dest_chg_level=plugType[1],avg_kw=kw[1],kwh=energy.level[2]-energy.level[1],veh.range=batteryCapacityInKWh[1]/fuelEconomyInKwhPerMile[1]),by=c('person','decisionEventId')]
  to.write[,':='(person=NULL,sessionId=decisionEventId,decisionEventId=NULL)]
  write.csv(to.write,file=pp(results.dir.base,'/',scen,'/beam-charging-sessions-evi-pro-format.csv'),quote=F,row.names=F)
}
