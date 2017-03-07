
load.libraries(c('Hmisc','sqldf'))

repeat_last = function(x, forward = TRUE, maxgap = Inf, na.rm = FALSE) {
    if (!forward) x = rev(x)           # reverse x twice if carrying backward
    ind = which(!is.na(x))             # get positions of nonmissing values
    if (is.na(x[1]) && !na.rm)         # if it begins with NA
        ind = c(1,ind)                 # add first pos
    rep_times = diff(                  # diffing the indices + length yields how often
        c(ind, length(x) + 1) )          # they need to be repeated
    if (maxgap < Inf) {
        exceed = rep_times - 1 > maxgap  # exceeding maxgap
        if (any(exceed)) {               # any exceed?
            ind = sort(c(ind[exceed] + 1, ind))      # add NA in gaps
            rep_times = diff(c(ind, length(x) + 1) ) # diff again
        }
    }
    x = rep(x[ind], times = rep_times) # repeat the values at these indices
    if (!forward) x = rev(x)           # second reversion
    x
}
read.data.table.with.filter <- function(filepath,match.words,header.word=NA){
  if(!is.na(header.word))match.words <- c(match.words,header.word)
  match.string <- pp("'",pp(match.words,collapse="\\|"),"'")
  return(data.table(read.csv.sql(filepath,filter=pp("grep ",match.string))))
}

# Get person attributes and vehicle types and join
peeps <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-developers/model-inputs/calibration-v2/person-attributes-from-reg-with-spatial-group.csv'))
vehs <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-developers/model-inputs/calibration-v2/vehicle-types.csv'))
plug.types <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-developers/model-inputs/calibration-v2/charging-plug-types.csv'))
peeps <- join.on(peeps,vehs,'vehicleTypeId','id',c('batteryCapacityInKWh','vehicleClassName'))
peeps[,veh.type:='BEV']
peeps[vehicleClassName=='PHEV',veh.type:='PHEV']
peeps[vehicleClassName=='NEV',veh.type:='NEV']

out.dirs <- list('dev'='/Users/critter/Documents/beam/beam-output/calibration_2017-03-03_20-48-12/')

scens <- names(out.dirs)

scen <- scens[1]
ev <- list()
for(scen in scens){
  # find the latest iteration
  the.iters <- data.table(file= list.files(pp(out.dirs[[scen]],'ITERS')))
  the.iters[,iter:=as.numeric(unlist(lapply(str_split(the.iters$file,"it."),function(ll){ ll[2] })))]
  if(nrow(the.iters)==0)next
  setkey(the.iters,iter)
  suppressWarnings(if(exists('df',mode='list'))rm('df'))
  for(i in nrow(the.iters):1){
    out.dir <- pp(out.dirs[[scen]],'ITERS/',the.iters[i]$file,'/')
    if(file.exists(pp(out.dir,'run0.',the.iters$iter[i],'.events.Rdata'))){
      my.cat(out.dir)
      load(pp(out.dir,'run0.',the.iters$iter[i],'.events.Rdata'),verb=T)
    }else if(file.exists(pp(out.dir,'run0.',the.iters$iter[i],'.events.csv'))){
      df <- read.data.table.with.filter(pp(out.dir,'run0.',the.iters$iter[i],'.events.csv'),c('DepartureChargingDecisionEvent','ArrivalChargingDecisionEvent','BeginChargingSessionEvent','EndChargingSessionEvent','UnplugEvent','actend'),'time')
      save(df,file=pp(out.dir,'run0.',the.iters$iter[i],'.events.Rdata'))
    }
    if(exists('df',mode='list'))break
  }
  df[,scenario:= scen]
  ev[[length(ev)+1]] <- df
  rm('df')
}
ev <- rbindlist(ev,use.names=T,fill=T)
ev[,native.order:=1:nrow(ev)]
ev[,hr:=as.numeric(time)/3600]
ev[,hour:=floor(hr)]
ev[actType=="",actType:=NA]
ev[,actType:=ifelse(type=='BeginChargingSessionEvent',repeat_last(as.character(actType),forward=F),as.character(NA)),by='person']

ev <- ev[!type%in%c('arrival','departure','travelled','actend','PreChargeEvent')]

# categorize each charger into Home, Work, Public
sites  <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-developers/model-inputs/calibration-v2/charging-sites-cp.csv',stringsAsFactors=F))
sites[,siteType:='Public']
sites[policyID==7,siteType:='Work']
#points <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-developers/model-inputs/calibration-v2/charging-points-cp.csv',stringsAsFactors=F))
#points <- join.on(points,sites,'siteID','id','policyID')

ev[,site:=as.numeric(site)]
ev <- join.on(ev,sites,'site','id','siteType')
ev[site<0,siteType:='Residential']
setkey(ev,hr,native.order)

# First fill in the SOC gaps, plugType, vehilceBatteryCap, and activityType
ev[,soc:=ifelse(type=='BeginChargingSessionEvent',c(NA,head(soc,-1)),soc),by='person']
ev[,soc:=as.numeric(soc)]
ev[,time:=as.numeric(time)]
ev[plugType=='',plugType:=NA]
ev[,plugType:=repeat_last(plugType),by='person']

# ev[person==6114071 & decisionEventId==17]

# Now focus in on just charge session events
ev <- ev[type%in%c('BeginChargingSessionEvent','EndChargingSessionEvent','UnplugEvent')]

# For now, create fake battery cap and charging rates
ev[,kw:=c("j-1772-2"=6.7,"sae-combo-3"=50,"j-1772-1"=1.9,"chademo"=50,"tesla-2"=20,"tesla-3"=120)[plugType]]
ev <- join.on(ev,peeps,'person','personId',c('batteryCapacityInKWh','veh.type'))
ev[,energy.level:=soc*batteryCapacityInKWh]
setkey(ev,hr,native.order)
 
# Fake end of charging sessions for those that were cut short
peeps.to.fix.1 <- ev[,list(n=length(type)),by=c('person','decisionEventId')][n==1]
peeps.to.fix.1[,row:=1:nrow(peeps.to.fix.1)]
peeps.to.fix.1 <- peeps.to.fix.1[,list(person=person,decisionEventId=decisionEventId,type=c('EndChargingSessionEvent','UnplugEvent'),hr=72,energy.level=25,soc=1,kw=6.7,native.order=c(max(ev$native.order)+1,Inf)),by='row']
peeps.to.fix.2 <- ev[,list(n=length(type)),by=c('person','decisionEventId')][n==2]
peeps.to.fix.2[,row:=1:nrow(peeps.to.fix.2)]
peeps.to.fix.2 <- peeps.to.fix.2[,list(person=person,decisionEventId=decisionEventId,type='UnplugEvent',hr=72,energy.level=25,soc=1,kw=6.7,native.order=Inf),by='row']

ev <- rbindlist(list(ev,peeps.to.fix.1,peeps.to.fix.2),use.names=T,fill=T)
setkey(ev,hr,native.order)

bad.peeps <- ev[,list(n=length(hr)),c('person','decisionEventId')][n!=3]$person
my.cat(pp('Removing ',length(bad.peeps),' peeps'))
ev <- ev[!person%in%bad.peeps]
ev[,':='(energy.level.min=c(energy.level[1],energy.level[1],energy.level[2]),hr.min=c(hr[1],hr[3] - (hr[2] - hr[1]),hr[3])),by=c('person','decisionEventId')]

# Occasionally, the above produces three time stamps that are identical which breaks interpolation below, fix 
peeps.to.fix <- ev[,all(hr==hr[1]),by=c('person','decisionEventId')][V1==T]$person
ev[person%in%peeps.to.fix,hr:=hr+c(0,0,0.1),by=c('person','decisionEventId')]
peeps.to.fix <- ev[,all(hr.min==hr.min[1]),by=c('person','decisionEventId')][V1==T]$person
ev[person%in%peeps.to.fix,hr.min:=hr.min+c(0,0,0.1),by=c('person','decisionEventId')]

peeps.to.skip <- ev[,type==c('BeginChargingSessionEvent','EndChargingSessionEvent','UnplugEvent'),by=c('person','decisionEventId')][V1==F]$person
ev <- ev[!person%in%peeps.to.skip]
setkey(ev,hr,native.order)

ev[,siteType:=repeat_last(siteType),by='person']
ev[,actType:=repeat_last(actType),by='person']


##################################################################
# First aggregate for plexos
##################################################################

ts <- seq(0,ceiling(max(ev$hr)),by=1)

   #Debugging
  #ev[,list(hr=sort(c(ts,hr)),
                #hr.min=sort(c(ts,hr.min)),
                #kw=repeat_last(rev(repeat_last(rev(my.approx(hr,kw,xout=sort(c(ts,hr)),method='constant')$y))))
                #),by=c('person','siteType')]

soc <- ev[,list(hr=sort(c(ts,hr)),
                hr.min=sort(c(ts,hr.min)),
                kw=repeat_last(rev(repeat_last(rev(approx(hr,kw,xout=sort(c(ts,hr)),method='constant')$y)))),
                kw.min=repeat_last(rev(repeat_last(rev(approx(hr,kw,xout=sort(c(ts,hr.min)),method='constant')$y)))),
                energy.level=repeat_last(rev(repeat_last(rev(approx(hr,energy.level,xout=sort(c(ts,hr)),method='linear')$y)))),
                energy.level.min=repeat_last(rev(repeat_last(rev(approx(hr.min,energy.level.min,xout=sort(c(ts,hr.min)),method='linear')$y)))),
                veh.type=veh.type[1]),by=c('person','siteType','actType')]

# Deal with over-representation of charging in early morning due to stranded/cut-off sesssions
#soc[hr>=2 & hr<3,':='(kw=kw/3,energy.level=energy.level/3)]
#soc[hr>=3 & hr<4,':='(kw=kw/5,energy.level=energy.level/5)]
#soc[hr>=4 & hr<5,':='(kw=kw/2,energy.level=energy.level/2)]

# these are sessions that were cut off due to stranding or due to the end of the sim
soc[is.na(actType),actType:='Home']

soc.max <- soc[,list(person,hr,energy.level,kw,constraint='max',siteType,actType,veh.type)]
soc.min <- soc[,list(person,hr=hr.min,energy.level=energy.level.min,kw=kw.min,constraint='min',siteType,actType,veh.type)]

soc <- rbindlist(list(soc.min,soc.max))

soc <- soc[,list(kw=sum(kw),energy.level=sum(energy.level),veh.type=veh.type[1]),by=c('hr','person','siteType','actType','constraint')]

soc[,d.energy.level:=c(0,ifelse(diff(energy.level)>0,diff(energy.level),0)),by=c('person','siteType','actType','constraint')]
soc[,cumul.energy:=cumsum(d.energy.level),by=c('person','siteType','actType','constraint')]

#ggplot(soc[hr==floor(hr),list(cumul.energy=sum(cumul.energy)),by=c('hr','constraint','siteType','actType')],aes(x=hr,y=cumul.energy,colour=constraint))+geom_line()+facet_wrap(~siteType)
#ggplot(soc[hr==floor(hr) & hr>=27 & hr<51 &constraint=='max',list(d.energy=sum(d.energy.level)),by=c('hr','constraint','actType')],aes(x=hr%%24,y=d.energy))+geom_line(lwd=1.5)+facet_wrap(~actType,scales='free_y')+labs(title="BEAM Average Load",x="Hour",y="Load (kW)")
#soc.sum <- soc[hr==floor(hr) & hr>=27 & hr<51 &constraint=='max',list(d.energy=sum(d.energy.level)),by=c('hr','constraint','siteType','actType')]
#setkey(soc.sum,hr,actType)
#ggplot(soc.sum,aes(x=hr%%24,y=d.energy,fill=actType))+geom_bar(stat='identity')+facet_wrap(~siteType,scales='free_y')+labs(title="BEAM Average Load",x="Hour",y="Load (kW)")

# Final categorization of load
soc[,final.type:=ifelse(siteType=='Public' & actType=='Work','Public',actType)]
soc[,final.type.new:='Public']
soc[final.type%in%c('Work','Home'),final.type.new:=actType]
soc[final.type=='Home',final.type.new:='Residential']
soc[,':='(final.type=final.type.new,final.type.new=NULL)]
soc.sum <- soc[hr==floor(hr) & hr>=27 & hr<51 & constraint=='max',list(d.energy=sum(d.energy.level)),by=c('hr','final.type','veh.type')]
setkey(soc.sum,hr,final.type,veh.type)
ggplot(soc.sum,aes(x=hr%%24,y=d.energy,fill=veh.type))+geom_bar(stat='identity')+facet_wrap(~final.type,scales='free_y')+labs(title="BEAM Average Load",x="Hour",y="Load (kW)")

# Load CP data and create scaling factors for turning BEAM workday output into a full week of constraints
load(pp(matsim.shared,'/data/chargepoint/cp.Rdata'))
cp[category=='Workplace',type:='Work']
cp[type=='Commercial',type:='Public']
wdays <- c('Sat'=0,'Sun'=1,'Mon'=2,'Tue'=3,'Wed'=4,'Thu'=5,'Fri'=6,'Sat'=7)
cp[,start.wday:=factor(names(wdays[start.wday]),levels=names(wdays))]
cp.hr <- cp[,list(kw=sum(kw)),by=c('start.month','start.mday','start.wday','hour.of.week','type')][,list(kw=mean(kw),wday=start.wday[1]),by=c('hour.of.week','type')]
ggplot(cp.hr,aes(x=hour.of.week%%24,y=kw,colour=factor(wday)))+geom_line(lwd=1.5)+facet_wrap(~type,scales='free_y')+labs(title="ChargePoint Average Load",x="Hour",y="Load (kW)",colour="Day of Week")
cp.day <- cp[,list(kw=sum(kw)),by=c('start.month','start.mday','start.wday','hour.of.week','type')][,list(kw=sum(kw),wday=start.wday[1]),by=c('start.wday','type')]
cp.day.norm <- cp.day[,list(norm.load= kw/mean(kw[!wday%in%c('Sat','Sun')]),wday=wday),by='type']

# Turn aggregated constraints into virtual battery cumul energy, scale by day of week, then repeat for a year

shave.peak <- function(x,y,shave.start,shave.end){
  c(y[x<shave.start],  approx(x[x%in%c(shave.start,shave.end)],y[x%in%c(shave.start,shave.end)],xout=x[x>=shave.start & x<=shave.end])$y, y[x>shave.end])
}
soc.weekday <- copy(soc)
soc.weekend <- copy(soc)
shave.start <- 26; shave.end <- 28
soc.weekday[,':='(d.energy.level=shave.peak(hr,d.energy.level,shave.start,shave.end)),by=c('person','final.type','veh.type','constraint')]
soc.weekend[,':='(d.energy.level=shave.peak(hr,d.energy.level,shave.start,shave.end)),by=c('person','final.type','veh.type','constraint')]
shave.start <- 28; shave.end <- 36
soc.weekend[,':='(d.energy.level=shave.peak(hr,d.energy.level,shave.start,shave.end)),by=c('person','final.type','veh.type','constraint')]
soc.weekend[,':='(cumul.energy=cumsum(d.energy.level)),by=c('person','final.type','veh.type','constraint')]

gap.analysis <- function(the.df){
  gap <- the.df[,list(max.en=cumul.energy[2],min.en=cumul.energy[1],gap=cumul.energy[2]-cumul.energy[1]),by=c('hr','final.type','veh.type')]
  gap[,hr.cal:=(hr-1)%%24+1]
  gap[,max.norm:=max.en-min(max.en),by=c('final.type','veh.type')]
  gap[hr.cal<=4 & hr>30,max.norm:=max.norm - max(max.norm),by=c('final.type','veh.type')]
  gap <- gap[hr>28]
  setkey(gap,final.type,veh.type,hr.cal)
  gap[hr>30 & hr.cal>=23]
  max.start <- gap[hr>30 & hr.cal>=23,list(maxstart=diff(max.en)),by=c('final.type','veh.type')]
  gap <- join.on(gap,max.start,c('final.type','veh.type'))
  gap[,max.norm:=max.norm-min(max.norm)+maxstart,by=c('final.type','veh.type')]
  gap[,min.norm:=max.norm-gap]
  min.start <- gap[hr>30 & hr.cal>=23,list(minstart=diff(min.norm)),by=c('final.type','veh.type')]
  gap <- join.on(gap,min.start,c('final.type','veh.type'))
  gap[,min.norm.relative.to.min:=min.norm - min(min.norm)+minstart,by=c('final.type','veh.type')]
  gap
}
gap <- gap.analysis(soc[hr>=28 & hr<=52 & hr==floor(hr),list(cumul.energy=sum(cumul.energy)),by=c('hr','constraint','final.type','veh.type')])
gap.weekend <- gap.analysis(soc.weekend[hr>=28 & hr<=52 & hr==floor(hr),list(cumul.energy=sum(cumul.energy)),by=c('hr','constraint','final.type','veh.type')])

scale.the.gap <- function(the.gap,cp.day.norm,the.day){
  day.name <- names(wdays[wdays==the.day])
  the.gap <- join.on(the.gap,cp.day.norm[wday==day.name],'final.type','type','norm.load')
  the.gap[,max.norm:=max.norm*norm.load]
  the.gap[,min.norm:=max.norm-gap*norm.load]
  min.start <- the.gap[hr>30 & hr.cal>=23,list(minstart=diff(min.norm)),by=c('final.type','veh.type')]
  the.gap <- join.on(the.gap,min.start,c('final.type','veh.type'))
  the.gap[,min.norm.relative.to.min:=min.norm - min(min.norm)+minstart,by=c('final.type','veh.type')]
  the.gap 
}

virt <- data.table(expand.grid(list(day=0:7,dhr=1:24,final.type=u(gap$final.type),veh.type=u(gap$veh.type))))
setkey(virt,final.type,veh.type,day,dhr)
virt[,hr:=(day-1)*24+dhr]
virt[,':='(max=0,min=0)]
for(the.day in 0:8){
  if(the.day>1 & the.day<7){
    gap.to.use <- scale.the.gap(copy(gap),cp.day.norm,the.day)
  }else{
    gap.to.use <- scale.the.gap(copy(gap.weekend),cp.day.norm,the.day)
  }
  if(the.day==0){
    virt[day==0,max:=gap.to.use$max.norm]
    virt[day==0,min:=gap.to.use$min.norm]
  }else{
    prev.day <- virt[day==the.day-1]
    max.mins <- prev.day[,list(max.max=max(max),max.min=max(min)),by=c('final.type','veh.type')]
    virt <- join.on(virt,max.mins,c('final.type','veh.type'))
    setkey(virt,final.type,veh.type,day,dhr)
    virt[day==the.day,max:=max.max+gap.to.use$max.norm]
    virt[day==the.day,min:=max.min+gap.to.use$min.norm.relative.to.min]
    virt[,':='(max.max=NULL,max.min=NULL)]
  }
}
setkey(virt,final.type,veh.type,day,hr)
ggplot(virt,aes(x=hr,y=max))+geom_line()+geom_line(aes(y=min))+facet_wrap(veh.type~final.type)


# Put final constraints needed by PLEXOS into a table
plexos.constraints <- data.table(hour=1:24,pev.inflexible.load.mw=diff(virtual.battery.energy[constraint=='max']$cumul.energy)/1000,
                                 plexos.battery.min.soc=head(virtual.battery.energy[constraint=='max']$cumul.energy - virtual.battery.energy[constraint=='min']$cumul.energy,-1))
plexos.constraints[,plexos.battery.max.discharge:=pev.inflexible.load.mw]
plexos.constraints[,plexos.battery.min.soc:=1-plexos.battery.min.soc/max(plexos.battery.min.soc)]

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

# Now generate minute by minute data for just the 24 hour time period of interest
ts <- seq(27,51,by=1/60)

soc <- ev[hr>=27 & hr<51,list(hr=sort(c(ts,hr)),
                kw=repeat_last(rev(repeat_last(rev(approx(hr,kw,xout=sort(c(ts,hr)),method='constant')$y)))),
                energy.level=repeat_last(rev(repeat_last(rev(approx(hr,energy.level,xout=sort(c(ts,hr)),method='linear')$y))))),by='person']

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
