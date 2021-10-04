#!/usr/local/bin/Rscript
##############################################################################################################################################
# Script to process results of a BEAM experiment and create some default plots comparing the runs against each other by the 
# factors. This is intended to be run from the project root directory.
# 
# Argument: the path to the experiment directory containing the .yaml file defining the experiment *and* the runs directory containing the 
# results.
##############################################################################################################################################
library(colinmisc)
setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')
load.libraries(c('optparse'),quietly=T)
load.libraries(c('maptools','sp','stringr','ggplot2'))

##############################################################################################################################################
# COMMAND LINE OPTIONS 
option_list <- list(
)
if(interactive()){
  #setwd('~/downs/')
  args<-'/Users/critter/Documents/beam/beam-output/EVFleet-Final/EVFleet-2018-09-21/'
  args <- parse_args(OptionParser(option_list = option_list,usage = "exp2plots.R [experiment-directory]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "exp2plots.R [experiment-directory]"),positional_arguments=T)
}
######################################################################################################

factor.to.scale.personal.back <- 35
factor.to.scale.transit.back <- 2

######################################################################################################
# Load the exp config
exp.dir <- ifelse(strtail(args$args)=="/",args$args,pp(args$args,"/"))
exp.file <- pp(exp.dir,'runs/experiments.csv')
plots.dir <- pp(exp.dir,'plots/')
make.dir(plots.dir)
exp <- data.table(read.csv(exp.file))
if('experimentalGroup'%in%names(exp)){
  exp[,':='(run=experimentalGroup,experimentalGroup=NULL)]
}
factors <- as.character(sapply(sapply(str_split(exp$run[1],"__")[[1]],str_split,"_"),function(x){ x[1] }))

levels <- list()
for(fact in factors){
  levels[[fact]] <- streval(pp('u(exp$',fact,')'))
}

grp <- exp$run[1]
evs <- list()
wait.times <- list()
for(run.i in 1:nrow(exp)){
  grp <-  exp$run[run.i]
  run.dir <- pp(exp.dir,'runs/run.',grp,'/')
  output.dir <- ifelse(file.exists(pp(run.dir,'output_')),'output_',ifelse(file.exists(pp(run.dir,'output')),'output',''))
  last.iter <- tail(sort(unlist(lapply(str_split(list.files(pp(run.dir,output.dir,'/ITERS')),"it."),function(x){ as.numeric(x[2])}))),1)
  #if(!file.exists(pp(run.dir,output.dir,'/ITERS/it.',last.iter,'/',last.iter,'.population.csv.gz')))last.iter <- last.iter - 1
  events.csv <- pp(run.dir,output.dir,'/ITERS/it.',last.iter,'/',last.iter,'.events.csv')
  ev <- csv2rdata(events.csv)
  ev[,run:=grp]
  wait.times.csv <- pp(run.dir,output.dir,'/ITERS/it.',last.iter,'/',last.iter,'.rideHailIndividualWaitingTimes.csv')
  wait.time <- csv2rdata(wait.times.csv)
  wait.time[,run:=grp]
  for(fact in factors){
    if(fact %in% names(ev))stop(pp('Factor name "',fact,'" also a column name in events.csv, please change factor name in experiments.csv'))
    the.level <- streval(pp('exp$',fact,'[run.i]'))
    streval(pp('ev[,',fact,':="',the.level,'"]'))
    streval(pp('wait.time[,',fact,':="',the.level,'"]'))
  }
  evs[[length(evs)+1]] <- ev[type%in%c('PathTraversal','ModeChoice','RefuelEvent','PersonEntersVehicle')]
  wait.times[[length(wait.times)+1]] <- wait.time
}
ev <- rbindlist(evs,use.names=T,fill=T)
rm('evs')
setkey(ev,type)
wait.time <- rbindlist(wait.times,use.names=T,fill=T)
rm('wait.times')
scale_fill_manual(values = colours)


#########################
# Ride Hail Fleet Plots
#########################

rh <- ev[J('PathTraversal')][substr(vehicle,1,5)=='rideH'][,.(time=departure_time,duration=(arrival_time-departure_time),vehicle,num_passengers,length,start.x,start.y,end.x,end.y,kwh=fuel/3.6e6,run)]
rh[duration==0,duration:=1]
rh[,speed:=length/1609/(duration/3600)]
rh <- rh[start.x < -100]
rh <- rh[length > 0 | num_passengers>0]

ref <- ev[J('RefuelEvent')][,.(time,duration=duration,vehicle,num_passengers=NA,length=NA,start.x=location.x,start.y=location.y,end.x=NA,end.y=NA,kwh=fuel/3.6e6,run)]
# filter out weird trips with bad coords and 0 length empty vehicle movements

detect.repos <- function(n){
 df <- data.table(n2=c(n,NA),n=c(NA,n))
 df[,res:=F]
 df[n==0&n2==0,res:=T]
 tail(df$res,-1)
}
rh[,reposition:=detect.repos(num_passengers),by=c('run','vehicle')]
rh[,type:='Movement']
ref[,type:='Charge']
both <- rbindlist(list(rh,ref),use.names=T,fill=T)
setkey(both,time)
both[,hour:=floor(time/3600)]

write.csv(both,file=pp(plots.dir,'/beam-ev-ride-hail-trips-and-charging.csv'),row.names=F)

#write.csv(rh,file='/Users/critter/Downloads/output 3/application-sfbay/base__2018-06-18_16-21-35/ITERS/it.1/beam-ride-hail-base-2018-06-20.csv')

enters <- ev[type=='PersonEntersVehicle'  & substr(vehicle,1,5)=='rideH' & substr(person,1,5)!='rideH',.(person,vehicle,run)]
#length(u(enters$person))


enters.sum <- enters[,.(n.trips.with.passenger=.N,n.unique.passengers=length(u(person))),by='run']

rh.sum <- rh[,.(n.vehicle.legs=.N,
                n.vehicles=length(u(vehicle)),
                total.vmt=sum(length)/1608,
                vmt.per.vehicle=sum(length)/1608/length(u(vehicle)),
                avg.trip.len.with.passenger=sum(length[num_passengers>0])/sum(num_passengers>0)/1608,
                avg.trip.deadhead.miles=sum(length[num_passengers==0 & reposition==F])/sum(num_passengers==0 & reposition==F)/1608,
                avg.trip.reposition.miles=sum(length[num_passengers==0 & reposition==T])/sum(num_passengers==0 & reposition==T)/1608,
                empty.vmt.fraction=sum(length[num_passengers==0])/sum(length),
                deadhead.vmt.fraction=sum(length[num_passengers==0& reposition==F])/sum(length),
                reposition.vmt.fraction=sum(length[num_passengers==0& reposition==T])/sum(length),
                avg.speed=mean(speed),
                avg.speed.vmt.weighted=weighted.mean(speed,length)
                ),by='run']

ref.sum <- ref[,.(n.charge.sessions=.N,
                  n.unique.vehicles.that.charge=length(u(vehicle)),
                  avg.session.minutes=mean(duration)/60,
                  avg.kwh=mean(kwh),
                  max.kwh=max(kwh),
                  min.kwh=min(kwh)
                  ),by='run']

wait.sum <- wait.time[,.(percentile=c(50,75,95,99),minutes=quantile(waitingTimeInSeconds,c(.5,.75,.95,.99),na.rm=T)/60),by='run']
wait.sum <- cast(wait.sum,run ~ percentile,value='minutes')
names(wait.sum) <- c('run',pp("customer.wait.",tail(names(wait.sum),-1),"th.percentile"))

all.sum <- join.on(join.on(join.on(rh.sum,enters.sum,'run','run'),ref.sum,'run','run'),data.table(wait.sum),'run','run')
all.sum <- join.on(all.sum,exp,'run','run',names(exp)[!names(exp)%in%names(all.sum)])

all.m <- melt(all.sum,id.vars=c(names(exp)))

p <- ggplot(all.m,aes(x=run,y=value))+geom_bar(stat='identity')+facet_wrap(~variable,scales='free_y')+theme(axis.text.x = element_text(angle = 35, hjust = 1))
pdf.scale <- 1.5
ggsave(pp(plots.dir,'summary-ride-hail-metrics-by-run.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')
write.csv(all.sum,file=pp(plots.dir,'summary-ride-hail-metrics.csv'))

p <- ggplot(all.m[variable%in%c('n.charge.sessions','n.unique.vehicles.that.charge','n.trips.with.passenger','vmt.per.vehicle','n.charge.sessions','customer.wait.50th.percentile','customer.wait.99th.percentile','deadhead.vmt.fraction')],aes(x=run,y=value))+geom_bar(stat='identity')+facet_wrap(~variable,scales='free_y')+theme(axis.text.x = element_text(angle = 35, hjust = 1))
pdf.scale <- 1.5
ggsave(pp(plots.dir,'summary-ride-hail-metrics-abbrev.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

save(all.sum,exp,file=pp(plots.dir,'summary-metrics.Rdata'))

if(F){
  load(file=pp(plots.dir,'summary-metrics.Rdata'))
  all.m <- melt(all.sum,id.vars=c(names(exp)))
  all.m[,':='(range=beam.agentsim.agents.rideHail.vehicleRangeInMeters/1609,numDrivers=beam.agentsim.agents.rideHail.numDriversAsFractionOfPopulation*410000,power=as.numeric(unlist(lapply(str_split(chargerLevel,"kW"),function(ll){ ll[[1]]}))))]
  for(metric in u(all.m$variable)){
   p <- ggplot(all.m[variable==metric],aes(x=range,y=numDrivers,colour=value,size=value))+geom_point()+labs(title=metric)+facet_wrap(~power)
   pdf.scale <- 0.75
   ggsave(pp(plots.dir,'metric-',metric,'.pdf'),p,width=11*pdf.scale,height=6*pdf.scale,units='in')
  }
  all.m[,':='(rangeOrdered=factor(vehicleRange, levels=unique(vehicleRange[order(range)]), ordered=TRUE),
              powerOrdered=factor(chargerLevel, levels=unique(chargerLevel[order(power)]), ordered=TRUE))]
  for(fleetSize in u(all.m$ridehailNumber)){
    for(metric in u(all.m$variable)){
     p <- ggplot(all.m[ridehailNumber==fleetSize & variable==metric],aes(x=rangeOrdered,y=value,fill=powerOrdered))+geom_bar(stat='identity',position='dodge')+labs(title=metric)
     pdf.scale <- 0.75
     ggsave(pp(plots.dir,fleetSize,'-fleet-metric-',metric,'.pdf'),p,width=11*pdf.scale,height=6*pdf.scale,units='in')
    }
  }

}

#num.paths <- rh[,.(n=length(speed),frac.repos=sum(num_passengers==0)/sum(num_passengers==1)),by='vehicle']
#num.paths[n==max(num.paths$n)]
#rh[vehicle==num.paths[frac.repos < Inf & frac.repos>1.2]$vehicle[1]]
#busy <- rh[vehicle==num.paths[n==max(num.paths$n)]$vehicle[1]]

#ggplot(rh[vehicle==num.paths[frac.repos < Inf & frac.repos>1.1]$vehicle[12]],aes(x=start.x,y=start.y,colour=factor(time)))+geom_point()


#ggplot(rh,aes(x= start.x,y=start.y,xend=end.x,yend=end.y,colour=reposition))+geom_segment()
#ggplot(rh,aes(x= start.x,y=start.y,colour=kwh))+geom_point()+geom_point(data=both[type=='Charge'])

#inds <- sample(nrow(both),round(nrow(both)/10))
#p <- ggplot(both[inds][type=='Movement'],aes(x= start.x,y=start.y,colour=type))+geom_point(alpha=0.5)+geom_point(data=both[inds][type=='Charge'],size=0.25)+facet_wrap(~run)
#pdf.scale <- 2
#ggsave(pp(plots.dir,'spatial-distribution-movements-and-charging.pdf'),p,width=8*pdf.scale,height=6*pdf.scale,units='in')

#dev.new();ggplot(both[type=='Movement'],aes(x= start.x,y=start.y,colour=type))+geom_point(alpha=0.5)+geom_point(data=both[type=='Charge'],size=0.25)+facet_wrap(~hour)


# find a veh that recharges
#both[vehicle%in%u(both[type=='Charge']$vehicle),.N,by='vehicle']


## Compare RH initial positions to activities
#rhi<-data.table(read.csv(pp("/Users/critter/Documents/beam/beam-output/EVFleet-Final/",out.scen,"/ITERS/it.0/0.rideHailInitialLocation.csv")))
#rhi[,':='(x=xCoord,y=yCoord)]
#rhi[,type:='RH-Initial']
#load('/Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/samples/population.Rdata')
#pop <- df
#rm('df')
#poprh<-rbindlist(list(pop,rhi),use.names=T,fill=T)
#ggplot(poprh,aes(x=x,y=y,colour=type))+geom_point(alpha=.2)


park.dir <- '~/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/parking/'

chs <- list()
dir <- tail(list.dirs(park.dir),-1)[1]
for(dir in tail(list.dirs(park.dir),-1)){
  for(ch.file in list.files(dir)){
    ch <- data.table(read.csv(pp(dir,'/',ch.file)))
    ch[,file:=ch.file]
    chs[[length(chs)+1]]<-ch
  }
}
ch <- rbindlist(chs)

ch[,charge:=ifelse(grepl('P250',file),'P250','P50')]
ch[,range:=ifelse(grepl('R150',file),'R150','R75')]
ch[,qos:=ifelse(grepl('S60',file),'S60',ifelse(grepl('S70',file),'S70',ifelse(grepl('S80',file),'S80',ifelse(grepl('S90',file),'S90',ifelse(grepl('S95',file),'S95','S98')))))]
ch.sum <- ch[reservedFor=='RideHailManager' & numStalls<2147483000 & chargingPointType=='DCFast',.(nchargers=sum(numStalls)),by=c('charge','range','qos')]

library(stringr)

save(all,exp,file=pp(plots.dir,'/summary-metrics-all.Rdata'))
load(file=pp(plots.dir,'/summary-metrics-all.Rdata'))


load("/Users/critter/Documents/beam/beam-output/EVFleet-Final/EVFleet-2018-09-24/plots/summary-metrics.Rdata",verb=T)
load("/Users/critter/Documents/beam/beam-output/EVFleet-Final/EVFleet-2018-09-24/plots-all/summary-metrics-all.Rdata",verb=T)

# adding base to all
all <- rbindlist(list(all,all.sum),fill=T,use.names=T)
all[chargers=='base',range:='Base']
all[chargers=='base',charge:='N/A']
all[chargers=='base',qos:='N/A']

all <- all[!(range=='R150' & qos=='S98' & charge=='P250')]
all <- join.on(all,ch.sum,c('charge','range','qos'),c('charge','range','qos'),'nchargers')
all[chargers=='base',nchargers:=0]

all[range!='Base',empty.vmt.fraction:=empty.vmt.fraction*1608]
all[range!='Base',deadhead.vmt.fraction:=empty.vmt.fraction*1608]

all.m <- melt(all,id.vars=c('range','charge','qos','nchargers'))
all.m[,variable:=str_replace_all(variable,'\\.','_')]
all.m[variable=='empty_vmt_fraction',value:=as.character(as.numeric(value)*100)]
all.m[variable=='empty_vmt_fraction' & range=='Base',value:=as.character(as.numeric(value)/1608)]


#for(metric in u(all.m$variable)){
  #if(all(is.na(as.numeric(all.m[variable==metric]$value)))){
    ## do nothing
  #}else{
    #toplot <- all.m[variable==metric & qos!='S70']
    #toplot[,value:=as.numeric(value)]
    #p<-ggplot(toplot,aes(x=qos,y= value))+geom_bar(stat='identity')+facet_grid(range~charge)+labs(title=metric)
    #pdf.scale <- 0.75
    #ggsave(pp(plots.dir,'metric-',metric,'.pdf'),p,width=11*pdf.scale,height=6*pdf.scale,units='in')
  #}
#}

all.m[charge=='P50' & qos=='S80',qos:='S99']
all.m[charge=='P50' & qos=='S99',charge:='P250']
all.m[,qosn:=as.numeric(substr(qos,2,4))]

#ggplot(all.m[charge=='P50'&variable%in%c('vmt_per_vehicle','n_trips_with_passenger','n_charge_sessions','customer_wait_50th_percentile')],aes(x=qosn,y=as.numeric(value),colour=range))+geom_line()+facet_wrap(~variable)

ggplot(all.m[qos!='S70'&charge=='P250'&variable%in%c('customer_wait_99th_percentile','n_trips_with_passenger','n_charge_sessions','customer_wait_50th_percentile')],aes(x=nchargers,y=as.numeric(value),colour=range,shape=range))+
                    geom_line()+
                    geom_point()+
                    geom_hline(data=all.m[range=='Base'&variable%in%c('customer_wait_99th_percentile','n_trips_with_passenger','n_charge_sessions','customer_wait_50th_percentile')],aes(yintercept=as.numeric(value)),color=my.colors['red'])+
                    scale_colour_manual(values=as.vector(my.colors[c('blue','green')]))+
                    facet_wrap(~variable,scales='free_y')

dev.new()

ggplot(all.m[qos!='S70'&charge=='P250'&variable%in%c('vmt_per_vehicle','total_vmt','empty_vmt_fraction','avg_trip_deadhead_miles')],aes(x=nchargers,y=as.numeric(value),colour=range,shape=range))+
  geom_line()+
  geom_point()+
  geom_hline(data=all.m[range=='Base'&variable%in%c('vmt_per_vehicle','total_vmt','empty_vmt_fraction','avg_trip_deadhead_miles')],aes(yintercept=as.numeric(value)),color=my.colors['red'])+
  scale_colour_manual(values=as.vector(my.colors[c('blue','green')]))+
  facet_wrap(~variable,scales='free_y')


