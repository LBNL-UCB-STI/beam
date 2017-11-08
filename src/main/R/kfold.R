
load.libraries(c('Hmisc','sqldf','GEOquery'))

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

parent.out.dir <- '/Users/critter/Documents/beam/beam-output/kfold'
load(pp(parent.out.dir,'/all-events.Rdata'))

if(F){
  scen <- 'base'

  ev <- list()
  the.out.dir <- list.dirs(parent.out.dir,recursive=F)[10]
  for(the.out.dir in list.dirs(parent.out.dir,recursive=F)){
    plans.file <- str_split(str_split(grep("inputPlansFile",readLines(pp(the.out.dir,'/run0.logfile.log')),value=T),'beam\\/\\/')[[1]][2],"\\\"")[[1]][1]
    parts.temp <- str_split(str_split(plans.file,"-kfold")[[1]][2],"-samp")[[1]]
    n <- as.numeric(parts.temp[1])
    i <- as.numeric(str_split(parts.temp[2],".xml")[[1]][1])/n
    
    the.desired.iter <- 0
    suppressWarnings(if(exists('df',mode='list'))rm('df'))
    out.dir <- pp(the.out.dir,'/ITERS/it.',the.desired.iter,'/')
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
      my.cat(pp("Warning, no data loaded for kfold #",i," for sample size ",n," and iteration ",the.desired.iter))
      next
    }
    df[,n:= n]
    df[,i:= i]
    ev[[length(ev)+1]] <- df
    rm('df')
  }
  ev <- rbindlist(ev,use.names=T,fill=T)
  ev[,native.order:=1:nrow(ev)]
  ev[,scenario:=pp('samp',n,'-run',i)]

  # first extract VMT data, then remove the travelled events and stranded folks, note that eVMT is estimated below
  ev <- join.on(ev,peeps,'person','personId',c('batteryCapacityInKWh','veh.type','fuelEconomyInKwhPerMile'))
  ev[,hr:=as.numeric(time)/3600]
  ev[,hour:=floor(hr)]

  stranded.peeps <- u(ev[choice=='stranded']$person)
  vmt <- ev[!person%in%stranded.peeps,list(miles=sum(as.numeric(distance),na.rm=T)*0.000621371,n.veh=length(u(person)),days=round(max(time)/24/3600)-1),by=c('scenario','veh.type')]
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

  ev[,':='(energy.level.min.phev.flex=c(energy.level[1],energy.level[1],ifelse(veh.type[1]=='PHEV',energy.level[1],energy.level[2])),
           #energy.level.min.full.flex=c(energy.level[1],energy.level[1],ifelse(veh.type[1]=='PHEV',energy.level[1],energy.level[1]+kwhNeeded[1])),
           energy.level.min=c(energy.level[1],energy.level[1],energy.level[2]),
           hr.min=c(hr[1],hr[3] - (hr[2] - hr[1]),hr[3])),by=c('scenario','person','decisionEventId')]
  #peeps.to.fix <- ev[,all(hr.min==hr.min[1]),by=c('scenario','person','decisionEventId')][V1==T]$person
  #ev[person%in%peeps.to.fix,hr.min:=hr.min+c(0,0,0.1),by=c('scenario','person','decisionEventId')]

  # Now back out the eVMT from the charge delivered
  evmt <- ev[,.(veh.type=veh.type[1],evmt=(energy.level[2]-energy.level[1])/fuelEconomyInKwhPerMile[1]),by=c('scenario','person','decisionEventId')]
  evmt <- evmt[,.(evmt=sum(evmt)/length(u(person))),by=c('scenario','veh.type')]
  vmt <- join.on(vmt,evmt,c('scenario','veh.type'),c('scenario','veh.type'))
  vmt[,evmt:=evmt*(253 + 112/weekday.to.weekend)/days]
  vmt[veh.type=='BEV',evmt:=vmt]
  vmt[,scale:=target/evmt]
  vmt[veh.type=='BEV',scale:=scale/1.033635]
  vmt[veh.type=='PHEV',scale:=scale/1.02362]

  save(ev,file=pp(parent.out.dir,'/all-events.Rdata'))
  save(vmt,file=pp(parent.out.dir,'/all-vmt.Rdata'))
}

##################################################################
# First aggregate for plexos
##################################################################

ts <- seq(0,ceiling(max(ev$hr)),by=1)
soc.raw <- ev[!is.na(kw),list(hr=sort(c(ts,hr)),
                hr.min=sort(c(ts,hr.min)),
                kw=repeat_last(rev(repeat_last(rev(approx(hr,kw,xout=sort(c(ts,hr)),method='constant')$y)))),
                kw.min=repeat_last(rev(repeat_last(rev(approx(hr,kw,xout=sort(c(ts,hr.min)),method='constant')$y)))),
                energy.level=repeat_last(rev(repeat_last(rev(approx(hr,energy.level,xout=sort(c(ts,hr)),method='linear')$y)))),
                energy.level.min=repeat_last(rev(repeat_last(rev(my.approx(hr.min,energy.level.min,xout=sort(c(ts,hr.min)),method='linear')$y)))),
                energy.level.min.phev.flex=repeat_last(rev(repeat_last(rev(my.approx(hr.min,energy.level.min.phev.flex,xout=sort(c(ts,hr.min)),method='linear')$y)))),
                veh.type=veh.type[1]),
                 by=c('scenario','person','final.type')]

  soc <- rbindlist(list(soc.raw[,list(scenario,person,hr,energy.level,kw,constraint='max',final.type,veh.type)],soc.raw[,list(scenario,person,hr=hr.min,energy.level=energy.level.min,kw=kw.min,constraint='min',final.type,veh.type)]))[hr==floor(hr)]
  soc <- soc[,list(kw=sum(kw),energy.level=sum(energy.level),veh.type=veh.type[1]),by=c('scenario','hr','person','final.type','constraint')]
  soc[,d.energy.level:=c(0,ifelse(diff(energy.level)>0,diff(energy.level),0)),by=c('scenario','person','final.type','constraint')]
  if(flex.scenario!='base'){ soc[constraint=='min' & veh.type=='PHEV',d.energy.level:=0] }
  soc[,cumul.energy:=cumsum(d.energy.level),by=c('scenario','person','final.type','constraint')]
  setkey(soc,scenario,hr,person,final.type,constraint)
  soc[,plugged.in.capacity:=ifelse(abs(diff(cumul.energy))>1e-6,kw[1],0),by=c('hr','scenario','person','final.type')]

  rm('soc.raw')
  save('soc',file=pp(parent.out.dir,'/soc.Rdata'))
  load(pp(parent.out.dir,'/soc.Rdata'))

  # Final categorization of load
  #soc.sum <- soc[ constraint=='max',list(d.energy=sum(d.energy.level)),by=c('scenario','hr','final.type','veh.type')]
  #setkey(soc.sum,scenario,hr,final.type,veh.type)
  #ggplot(soc.sum,aes(x=hr,y=d.energy,fill=veh.type))+geom_bar(stat='identity')+facet_grid(final.type~scenario,scales='free_y')+labs(title="BEAM Average Load",x="Hour",y="Load (kW)")

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

  virt.all <- list()
  for(scen in u(soc$scenario)){
    gap.weekday <- gap.analysis(soc.weekday[scenario==scen & hr>=28 & hr<=52,list(cumul.energy=sum(cumul.energy),plugged.in.capacity=sum(plugged.in.capacity)),by=c('hr','constraint','final.type','veh.type')])
    gap.weekend <- gap.analysis(soc.weekend[scenario==scen & hr>=28 & hr<=52,list(cumul.energy=sum(cumul.energy),plugged.in.capacity=sum(plugged.in.capacity)),by=c('hr','constraint','final.type','veh.type')])

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
    virt[,scenario:=scen]
    virt.all[[length(virt.all)+1]] <- virt
  }
  virt.all <- rbindlist(virt.all)
  save('virt.all',file=pp(parent.out.dir,'/virt.Rdata'))

  load(pp(parent.out.dir,'/all-vmt.Rdata'))
  load(pp(parent.out.dir,'/virt.Rdata'))
  ns <- unlist(lapply(lapply(str_split((virt.all$scenario),"samp"),function(ll){ str_split(ll[2],'-run')[[1]]}),function(lll){ lll[1] }))
  is <- unlist(lapply(lapply(str_split((virt.all$scenario),"samp"),function(ll){ str_split(ll[2],'-run')[[1]]}),function(lll){ lll[2] }))
  virt.all[,n:=as.numeric(ns)]
  virt.all[,i:=as.numeric(is)]
  virt.all[,n:=factor(n,levels=sort(u(n)))]

  virt.all[,flex:=max-min]
  virt.all[flex<0,flex:=0]
  maxes <- virt.all[final.type=='Residential',.(the.max=max(flex,na.rm=T)),by=c('n','veh.type')]
  virt.all <- join.on(virt.all,maxes,c('n','veh.type'),c('n','veh.type'))
  virt.all[,flex.scaled:=flex/the.max]
  virt.all <- virt.all

  setkey(virt.all,n,i,veh.type,final.type,hr)
  find.4hr <- function(dmax,dmin){
    if(length(dmax)>=4){
      c(sapply(1:(length(dmax)-3),function(i){ (dmax[i+3]-dmax[i] - max(dmin[i+3]-dmax[i],0) )/4 }),rep(NA,3))
    }else{
      c()
    }
  }
  find.puc <- function(dmax){
    if(length(dmax)>4){
      dd <- data.table(head(diff(dmax),-4),tail(diff(dmax),-4))
      dd[,flex:=0.8*(V2-V1)]
      c(dd$flex,head(dd$flex,5))
    }else{
      c()
    }
  }
  virt.all[,flex.4hr:=find.4hr(max,min),by=c('n','veh.type','final.type','i')]
  virt.all[,flex.1hr:=c(diff(max),0),by=c('n','veh.type','final.type','i')]
  virt.all[,flex.puc:=find.puc(max),by=c('n','veh.type','final.type','i')]
  virt.all[,flex:=flex.4hr]
  virt.all <- join.on(virt.all,vmt,c('scenario','veh.type'),c('scenario','veh.type'),c('n.veh','scale'))
  virt.all[,flex:=flex/n.veh]

  ggplot(melt(virt.all[final.type=='Residential'],measure.vars=c('max','min')),aes(x=hr,y=value, linetype =variable,colour=factor(i)))+geom_line()+facet_grid(factor(n)~veh.type,scales='free_y')
  ggplot(virt.all[final.type=='Residential'],aes(x=hr,y=flex,colour=i))+geom_line()+facet_grid(factor(n)~veh.type,scales='free_y')

  virt.detail <- virt.all[hr>35 & hr<61,.(n.veh=n.veh,scale=scale,flex=sum(flex),flex.4hr=sum(flex.4hr),flex.1hr=sum(flex.1hr),flex.puc=sum(flex.puc)),by=c('hr','i','n','veh.type')]
  virt.detail[,hr:=(hr-24)%%24]

  #Closer look at one night
  ggplot(virt.detail[n==5],aes(x=hr,y=flex,colour=factor(i)))+geom_line()+facet_grid(final.type~veh.type,scales='free_y')

  # As a box plot
  ggplot(virt.detail,aes(x=factor(hr),y=flex))+geom_boxplot()+facet_grid(n~veh.type,scales='free_y')

  # As fraction of max
  cis <- virt.detail[,.(scale=scale[1],prob=seq(0.1,0.9,by=0.2),
                            quantile.4hr=quantile(flex.4hr/n.veh,seq(0.1,0.9,by=0.2)),
                            quantile.1hr=quantile(flex.1hr/n.veh,seq(0.1,0.9,by=0.2)),
                            quantile.puc=quantile(flex.puc/n.veh,seq(0.1,0.9,by=0.2))),by=c('hr','n','veh.type')]
  ci.m <- melt(cis,measure.vars=c('quantile.4hr','quantile.1hr','quantile.puc'))
  ggplot(cis,aes(x=hr,y=quantile.4hr,colour=factor(prob)))+geom_line()+facet_grid(n~veh.type)
  ggplot(ci.m[n=='5'],aes(x=hr,y=value,colour=factor(prob)))+geom_line()+facet_grid(veh.type~variable)

  # Write results
  setkey(virt.detail,n,veh.type,hr)
  write.csv(virt.detail[,.(flex.mean=mean(flex),flex.median=median(flex),flex.sd=sd(flex),flex.025=quantile(flex,.025),flex.975=quantile(flex,.975),flex.25=quantile(flex,.25),flex.75=quantile(flex,.75)),by=c('n','veh.type','hr')],file=pp(parent.out.dir,'/flexible-4hour-capacity-per-vehicle.csv'))

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
  scenarios[,scaling.val:=value]
  ci.m[,method:=variable]

  setkey(ci.m,variable,n,veh.type,prob,hr)
  results.dir.base <- '/Users/critter/GoogleDriveUCB/beam-collaborators/planning/vgi/capacity-analysis-v1/'
  make.dir(results.dir.base)
  the.utility <- scenarios$Electric.Utility[3]
  pen <- scenarios$penetration[1]
  veh.split <- scenarios$veh.type.split[1]
  to.write.all <- list()
  for(the.utility in u(scenarios$Electric.Utility)){
    for(pen in u(scenarios$penetration)){
      for(veh.split in u(scenarios$veh.type.split)){
        to.write <- copy(ci.m)
        to.write <- join.on(to.write,scenarios[Electric.Utility==the.utility & penetration==pen & veh.type.split==veh.split],'veh.type','veh.type','scaling.val')
        to.write <- to.write[,list(kw.per.vehicle=value*scale,mw=sum(68e3*value*scale*scaling.val/1e3)),by=c('hr','veh.type','method','n','prob')]
        to.write[,':='(utility=the.utility,penetration=pen,vehicle.split=veh.split)]
        to.write.all[[length(to.write.all)+1]] <- to.write
      }
    }
  }
  to.write.all <- rbindlist(to.write.all)
  to.write.all <- to.write.all[,.(kw.per.vehicle=kw.per.vehicle[1],mw=sum(mw)),by=c('hr','veh.type','method','n','prob','penetration','vehicle.split')]
  write.csv(to.write.all,pp(results.dir.base,'/flexibility.csv'),row.names=F)

