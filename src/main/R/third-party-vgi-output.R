third-party-vgi-outputs.R


if(F){
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
                                   #max.cumulative.energy=diff(virtual.battery.energy[constraint=='max']$cumul.energy)/1000,
                                   #plexos.battery.min.soc=head(virtual.battery.energy[constraint=='max']$cumul.energy - virtual.battery.energy[constraint=='min']$cumul.energy,-1))
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

}
