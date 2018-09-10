#!/usr/local/bin/Rscript
##############################################################################################################################################
# Script to process results of a BEAM experiment and create some default plots comparing the runs against each other by the 
# factors. This is intended to be run from the project root directory.
# 
# Argument: the path to the experiment directory containing the .yaml file defining the experiment *and* the runs directory containing the 
# results.
##############################################################################################################################################
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
  args<-'/Users/critter/Documents/beam/beam-output/EVFleet-Final/EVFleet-2018-09-06/'
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
factors <- as.character(sapply(sapply(str_split(exp$experimentalGroup[1],"__")[[1]],str_split,"_"),function(x){ x[1] }))

levels <- list()
for(fact in factors){
  levels[[fact]] <- streval(pp('u(exp$',fact,')'))
}

grp <- exp$experimentalGroup[1]
evs <- list()
for(run.i in 1:nrow(exp)){
  grp <-  exp$experimentalGroup[run.i]
  run.dir <- pp(exp.dir,'runs/run.',grp,'/')
  output.dir <- ifelse(file.exists(pp(run.dir,'output_')),'output_','output')
  last.iter <- tail(sort(unlist(lapply(str_split(list.files(pp(run.dir,output.dir,'/ITERS')),"it."),function(x){ as.numeric(x[2])}))),1)
  if(!file.exists(pp(run.dir,output.dir,'/ITERS/it.',last.iter,'/',last.iter,'.population.csv.gz')))last.iter <- last.iter - 1
  events.csv <- pp(run.dir,output.dir,'/ITERS/it.',last.iter,'/',last.iter,'.events.csv')
  ev <- csv2rdata(events.csv)
  ev[,run:=grp]
  for(fact in factors){
    if(fact %in% names(ev))stop(pp('Factor name "',fact,'" also a column name in events.csv, please change factor name in experiments.csv'))
    the.level <- streval(pp('exp$',fact,'[run.i]'))
    streval(pp('ev[,',fact,':="',the.level,'"]'))
  }
  evs[[length(evs)+1]] <- ev[type%in%c('PathTraversal','ModeChoice','RefuelEvent','PersonEntersVehicle')]
}
ev <- rbindlist(evs,use.names=T,fill=T)
rm('evs')
setkey(ev,type)
scale_fill_manual(values = colours)


#########################
# Ride Hail Fleet Plots
#########################

rh <- ev[J('PathTraversal')][vehicle_type=='BEV' & substr(vehicle,1,5)=='rideH'][,.(time=departure_time,duration=(arrival_time-departure_time),vehicle,num_passengers,length,start.x,start.y,end.x,end.y,kwh=fuel/3.6e6,run)]
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

#write.csv(rh,file='/Users/critter/Downloads/output 3/application-sfbay/base__2018-06-18_16-21-35/ITERS/it.1/beam-ride-hail-base-2018-06-20.csv')

enters <- ev[type=='PersonEntersVehicle'  & substr(vehicle,1,5)=='rideH' & substr(person,1,5)!='rideH',.(person,vehicle,run)]
#length(u(enters$person))

if('experimentalGroup'%in%names(exp)){
  exp[,':='(run=experimentalGroup,experimentalGroup=NULL)]
}

enters.sum <- enters[,.(n.trips.with.passenger=.N,n.unique.passengers=length(u(person))),by='run']

rh.sum <- rh[,.(n.vehicle.legs=.N,
                n.vehicles=length(u(vehicle)),
                total.vmt=sum(length)/1608,
                vmt.per.vehicle=sum(length)/1608/length(u(vehicle)),
                avg.trip.len.with.passenger=sum(length[num_passengers>0])/sum(num_passengers>0)/1608,
                avg.trip.deadhead.miles=sum(length[num_passengers==0 & reposition==F])/sum(num_passengers==0 & reposition==F)/1608,
                avg.trip.reposition.miles=sum(length[num_passengers==0 & reposition==T])/sum(num_passengers==0 & reposition==T)/1608,
                empty.vmt.fraction=sum(length[num_passengers==0])/sum(length)/1608,
                deadhead.vmt.fraction=sum(length[num_passengers==0& reposition==F])/sum(length)/1608,
                reposition.vmt.fraction=sum(length[num_passengers==0& reposition==T])/sum(length)/1608,
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

all.sum <- join.on(join.on(rh.sum,enters.sum,'run','run'),ref.sum,'run','run')
all.sum <- join.on(all.sum,exp,'run','run',names(exp)[!names(exp)%in%names(all.sum)])

all.m <- melt(all.sum,id.vars=c(names(exp)))

p <- ggplot(melt(all.sum,id.vars=c(names(exp))),aes(x=run,y=value))+geom_bar(stat='identity')+facet_wrap(~variable,scales='free_y')+theme(axis.text.x = element_text(angle = 35, hjust = 1))
pdf.scale <- 1.5
ggsave(pp(plots.dir,'summary-ride-hail-metrics-by-run.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')
write.csv(all.sum,file=pp(plots.dir,'summary-ride-hail-metrics.csv'))

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

inds <- sample(nrow(both),round(nrow(both)/10))
p <- ggplot(both[inds][type=='Movement'],aes(x= start.x,y=start.y,colour=type))+geom_point(alpha=0.5)+geom_point(data=both[inds][type=='Charge'],size=0.25)+facet_wrap(~run)
pdf.scale <- 2
ggsave(pp(plots.dir,'spatial-distribution-movements-and-charging.pdf'),p,width=8*pdf.scale,height=6*pdf.scale,units='in')

#dev.new();ggplot(both[type=='Movement'],aes(x= start.x,y=start.y,colour=type))+geom_point(alpha=0.5)+geom_point(data=both[type=='Charge'],size=0.25)+facet_wrap(~hour)

write.csv(both,file=pp(plots.dir,'/beam-ev-ride-hail-trips-and-charging.csv'),row.names=F)

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



