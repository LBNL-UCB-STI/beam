#!/usr/local/bin/Rscript
##############################################################################################################################################
# Script to process results of a single BEAM run and create some default plots analyzing the result
# 
# Argument: the path to the config file contained in the run output directory.
##############################################################################################################################################
setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')
load.libraries(c('optparse'),quietly=T)
load.libraries(c('maptools','sp'))

##############################################################################################################################################
# COMMAND LINE OPTIONS 

factor.to.scale.personal.back <- 20 # should be a command line arg

option_list <- list(
)
if(interactive()){
  #setwd('~/downs/')
  args<-'/Users/critter/Documents/beam/beam-output/experiments/base_2018-01-17_15-46-24/beam.conf'
  args <- parse_args(OptionParser(option_list = option_list,usage = "run2plots.R [config-file]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "run2plots.R [config-file]"),positional_arguments=T)
}
######################################################################################################

######################################################################################################
# Load the exp config

run.dir <- pp(pp(head(str_split(args$args,"/")[[1]],-1),collapse="/"),"/")
run.name <- tail(head(str_split(args$args,"/")[[1]],-1),1)
conf.file <- tail(str_split(args$args,"/")[[1]],1)
plots.dir <- pp(run.dir,'plots/')
make.dir(plots.dir)

evs <- list()
vehs <- list()
iter <- tail(list.dirs(pp(run.dir,'ITERS/'),full.names=F),-1)[1]
for(iter in tail(list.dirs(pp(run.dir,'ITERS/'),full.names=F),-1)){
  my.cat(iter)
  iter.i <- as.numeric(tail(str_split(iter,'\\.')[[1]],1))

  events.csv <- pp(run.dir,'ITERS/',iter,'/',iter.i,'.events.csv')
  ev <- csv2rdata(events.csv)
  ev[,iter:=iter.i]
  evs[[length(evs)+1]] <- ev[type%in%c('PathTraversal','ModeChoice')]
  vehs[[length(evs)+1]] <- ev[type%in%c('PersonEntersVehicle','PersonLeavesVehicle')]
}
ev <- rbindlist(evs)
veh <- rbindlist(vehs)
rm('evs')
rm('vehs')

ev <- clean.and.relabel(ev,factor.to.scale.personal.back)

veh[,is.transit:=grepl(":",vehicle)]

setkey(ev,type,iter,hr,vehicle_type)

############################
## Default Plots 
############################

## VMT by time and mode
toplot <- ev[J('PathTraversal')][,.(vmt=sum(length/1609)),by=c('hr','iter','vehicle_type')]
toplot[vehicle_type%in%c('Car','TNC'),vmt:=vmt*factor.to.scale.personal.back]
p <- ggplot(toplot,aes(x=hr,y=vmt,fill=vehicle_type))+geom_bar(stat='identity',position='stack')+facet_wrap(~iter)+labs(x="Hour",y="Vehicle Miles Traveled",fill="Vehicle Type",title=to.title(run.name))
pdf.scale <- .6
ggsave(pp(plots.dir,'vmt-by-hour.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

# Transit use
#ggplot(ev[tripmode=='transit',],aes(x=length,y= num_passengers))+geom_point()
#ggplot(toplot[,],aes(x=num_passengers/capacity))+geom_histogram()+facet_wrap(~level)
toplot<-ev[J('PathTraversal')][tripmode=='transit']
p <- ggplot(toplot[,.(cap.factor=mean(num_passengers/capacity,na.rm=T),frac.full=ifelse(all(capacity==0),as.numeric(NA),sum(num_passengers==capacity)/length(capacity))),by=c('hr','vehicle_type','iter')],aes(x=hr,y=cap.factor,fill=vehicle_type))+geom_bar(stat='identity',position='dodge')+geom_line(aes(y=frac.full))+facet_grid(vehicle_type~iter)+labs(x="Hour",y="Capacity Factor (bars) and Fraction of Trips at Full (line)",title=to.title(run.name),fill="Transit Type")
pdf.scale <- .8
ggsave(pp(plots.dir,'transit-use.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

# Transit Boarding
toplot<-veh[is.transit==T]
toplot[,is.driver.action:=grepl("TransitDriverAgent",person)]
toplot <- toplot[is.driver.action==F]
toplot[,agency:=unlist(lapply(str_split(vehicle,":"),function(x){ x[1] }))]
toplot[,transitTrip:=unlist(lapply(str_split(vehicle,":"),function(x){ x[2] }))]
toplot[,hour:=floor(time/3600)]

p <- ggplot(toplot[type=='PersonEntersVehicle',.(n=length(time)*factor.to.scale.personal.back),by=c('hour','agency')],aes(x=hour,y=n,fill=agency))+geom_bar(stat='identity')+facet_wrap(~agency)+labs(x="Hour",y="# Boarding Passengers",title=to.title(run.name),fill="Transit Agency")
pdf.scale <- .8
ggsave(pp(plots.dir,'transit-boarding.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

p <- ggplot(toplot[type=='PersonEntersVehicle' & agency%in%c('SF','AC','BA','VT'),.(n=length(time)*factor.to.scale.personal.back),by=c('hour','agency')],aes(x=hour,y=n,fill=agency))+geom_bar(stat='identity')+facet_wrap(~agency)+labs(x="Hour",y="# Boarding Passengers",title=to.title(run.name),fill="Transit Agency")
pdf.scale <- .8
ggsave(pp(plots.dir,'transit-boarding-big-4.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')


### Transit analysis, group transit trips (i.e. vehicles) and agencies by ridership
toplot<-ev[J('PathTraversal')][tripmode=='transit']
toplot[,agency:=unlist(lapply(str_split(vehicle_id,":"),function(x){ x[1] }))]
toplot[,transitTrip:=unlist(lapply(str_split(vehicle_id,":"),function(x){ x[2] }))]
toplot <- toplot[,.(numPassengers=sum(num_passengers),pmt=sum(num_passengers*length/1609)),by=c('agency','transitTrip')]

write.csv(toplot,file=pp(plots.dir,'transit-use-by-trip.csv'))
write.csv(toplot[pmt<=10],file=pp(plots.dir,'transit-trips-low-ridership.csv'))




