
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
peeps <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-developers/model-inputs/sf-bay/person-attributes-from-reg.csv'))
vehs <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-developers/model-inputs/sf-bay/vehicle-types.csv'))
peeps <- join.on(peeps,vehs,'vehicleTypeId','id','batteryCapacityInKWh')

out.dirs <- list('dev'='/Users/critter/Documents/beam/beam-output/sf-bay_2016-12-01_21-22-27/ITERS/it.90/')

scens <- names(out.dirs)

scen <- scens[1]
ev <- list()
for(scen in scens){
  out.dir <- out.dirs[[scen]]
  if(file.exists(pp(out.dir,'run0.0.events.Rdata'))){
    my.cat(out.dir)
    if(exists('dt'))rm('dt')
    load(pp(out.dir,'run0.0.events.Rdata'),verb=T)
  }else{
    dt <- read.data.table.with.filter(pp(out.dir,'run0.0.events.csv'),c('DepartureChargingDecisionEvent','ArrivalChargingDecisionEvent','BeginChargingSessionEvent','EndChargingSessionEvent','UnplugEvent'),'choiceUtility')
    save(dt,file=pp(out.dir,'run0.0.events.Rdata'))
  }
  dt[,scenario:= scen]
  ev[[length(ev)+1]] <- dt
  rm('dt')
}
ev <- rbindlist(ev,use.names=T,fill=T)
ev[,hr:=as.numeric(time)/3600]
ev[,hour:=floor(hr)]
ev <- ev[!type%in%c('arrival','departure','travelled','actend','actstart','PreChargeEvent')]


# First fill in the SOC gaps, plugType, vehilceBatteryCap
ev[,soc:=ifelse(type=='BeginChargingSessionEvent',c(NA,head(soc,-1)),soc),by='person']
ev[,soc:=as.numeric(soc)]
ev[,time:=as.numeric(time)]
ev[plugType=='',plugType:=NA]
ev[,plugType:=repeat_last(plugType),by='person']

# ev[person==6114071 & decisionEventId==17]

# Now focus in on just charge session events
ev <- ev[type%in%c('BeginChargingSessionEvent','EndChargingSessionEvent','UnplugEvent')]

# For now, create fake battery cap and charging rates
ev[,kw:=c("j-1772-2"=7,"sae-combo-3"=50,"j-1772-1"=1.5,"chademo"=50,"tesla-2"=20)[plugType]]
ev <- join.on(ev,peeps,'person','personId','batteryCapacityInKWh')
ev[,energy.level:=soc*batteryCapacityInKWh]
 
# Prep for the minimum cumul energy constraint
peeps.to.skip <- ev[,type==c('BeginChargingSessionEvent','EndChargingSessionEvent','UnplugEvent'),by=c('person','decisionEventId')][V1==F]$person
ev <- ev[!person%in%peeps.to.skip]


ev[,':='(energy.level.min=c(energy.level[1],energy.level[1],energy.level[2]),hr.min=c(hr[1],hr[3] - (hr[2] - hr[1]),hr[3])),by=c('person','decisionEventId')]

##################################################################
# First aggregate for plexos
##################################################################

ts <- seq(0,ceiling(max(ev$hr)),by=1)

#plot(sort(c(ts,ev[person==6114071]$hr)),repeat_last(rev(repeat_last(rev(approx(ev[person==6114071]$hr,ev[person==6114071]$soc,xout=sort(c(ts,ev[person==6114071]$hr)),method='linear')$y)))),ylim=c(0,1))
#points(ev[person==6114071]$hr,ev[person==6114071]$soc,col='red')
#diff(repeat_last(rev(repeat_last(rev(approx(ev[person==6114071]$hr,ev[person==6114071]$soc,xout=sort(c(ts,ev[person==6114071]$hr)),method='linear')$y)))))

while(F){
  soc <- ev[person%in%sample(u(ev$person),12)][,list(hr=sort(c(ts,hr)),hr.min=sort(c(ts,hr.min)),soc=repeat_last(rev(repeat_last(rev(approx(hr,soc,xout=sort(c(ts,hr)),method='linear')$y)))),soc.min=repeat_last(rev(repeat_last(rev(approx(hr.min,soc.min,xout=sort(c(ts,hr.min)),method='linear')$y))))),by='person']

  #soc.max <- soc[floor(hr)==hr,list(person,hr,soc,constraint='max')]
  #soc.min <- soc[floor(hr.min)==hr.min,list(person,hr=hr.min,soc=soc.min,constraint='min')]
  soc.max <- soc[,list(person,hr,soc,constraint='max')]
  soc.min <- soc[,list(person,hr=hr.min,soc=soc.min,constraint='min')]

  soc <- rbindlist(list(soc.min,soc.max))

  soc[,d.soc:=c(0,ifelse(diff(soc)>0,diff(soc),0)),by=c('person','constraint')]
  soc[,cap:=24]
  soc[,d.energy:=d.soc*cap]
  soc[,cumul.energy:=cumsum(d.energy),by=c('person','constraint')]

  p <- ggplot(soc[hr==floor(hr),list(hr,cumul.energy,person,constraint)],aes(x=hr,y=cumul.energy,colour=constraint))+geom_line()+facet_wrap(~person)
  print(p)
  system('sleep 3')
}

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

ggplot(soc[hr<=33 & hr==floor(hr),list(cumul.energy=sum(cumul.energy)),by=c('hr','constraint')],aes(x=hr,y=cumul.energy,colour=constraint))+geom_line()
ggplot(soc[hr<=33 & hr==floor(hr),list(kw=sum(kw)),by=c('hr','constraint')],aes(x=hr,y=kw,colour=constraint))+geom_line()

# Put final constraints needed by PLEXOS into a table

virtual.battery.energy <- soc[hr<=33 & hr==floor(hr),list(cumul.energy=sum(cumul.energy)),by=c('hr','constraint')]

plexos.constraints <- data.table(hour=1:max(virtual.battery.energy$hr),
                                 pev.inflexible.load.mw=diff(virtual.battery.energy[constraint=='max']$cumul.energy)/1000,
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
