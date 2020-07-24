#!/usr/local/bin/Rscript
##############################################################################################################################################
# Script to process results of a single BEAM run and create some default plots analyzing the result
# 
# Argument: the path to the run output directory.
##############################################################################################################################################
library(colinmisc)
setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')
load.libraries(c('optparse'),quietly=T)
load.libraries(c('maptools','sp'))

##############################################################################################################################################
# COMMAND LINE OPTIONS 

factor.to.scale.personal.back <- 20 # should be a command line arg
factor.to.scale.transit.back <- 5 # should be a command line arg

option_list <- list(
)
if(interactive()){
  #setwd('~/downs/')
  args<-'/Users/critter/Documents/beam/beam-output/experiments/2018-04/base_2018-04-17_15-36-19/'
  #args<-'/Users/critter/Documents/beam/beam-output/sf-light-25k_2018-02-13_15-04-19/'
  args <- parse_args(OptionParser(option_list = option_list,usage = "run2plots.R [config-file]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "run2plots.R [config-file]"),positional_arguments=T)
}
######################################################################################################

######################################################################################################
# Load the exp config

run.dir <- dir.slash(args$args)
run.name <- tail(head(str_split(run.dir,"/")[[1]],-1),1)
iter.dir <- ifelse("ITERS"%in%list.dirs(run.dir,recur=F,full.names=F),pp(run.dir,'ITERS/'),pp(run.dir,'output/ITERS/'))
conf.file <- pp(iter.dir,'../beam.conf')
plots.dir <- pp(run.dir,'plots/')
make.dir(plots.dir)

evs <- list()
vehs <- list()
pops <- list()
iter <- tail(list.dirs(iter.dir,full.names=F),-1)[1]
for(iter in tail(list.dirs(iter.dir,full.names=F),-1)){
  my.cat(iter)
  iter.i <- as.numeric(tail(str_split(iter,'\\.')[[1]],1))

  events.csv <- pp(iter.dir,iter,'/',iter.i,'.events.csv')
  ev <- csv2rdata(events.csv)
  ev[,iter:=iter.i]
  evs[[length(evs)+1]] <- ev[type%in%c('PathTraversal','ModeChoice')]
  vehs[[length(evs)+1]] <- ev[type%in%c('PersonEntersVehicle','PersonLeavesVehicle')]

  pop.csv <- pp(iter.dir,iter,'/',iter.i,'.population.csv.gz')
  pop <- csv2rdata(pop.csv)
  pop[,iter:=iter.i]
  pops[[length(pops)+1]] <- pop
}
ev <- rbindlist(evs)
veh <- rbindlist(vehs,fill=T)
pop <- rbindlist(pops)
rm('evs')
rm('vehs')
rm('pops')

ev <- clean.and.relabel(ev,factor.to.scale.personal.back,factor.to.scale.transit.back)

veh[,is.transit:=grepl(":",vehicle)]

setkey(ev,type,iter,hr,vehicle_type)

############################
## Default Plots 
############################

## VMT by time and mode
toplot <- ev[J('PathTraversal')][,.(vmt=sum(length/1609,na.rm=T)),by=c('hr','iter','vehicle_type')]
if(max(toplot$iter)>9){
  toplot <- toplot[iter %in% seq(0,max(toplot$iter),by=max(toplot$iter)/8)]
}
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

p <- ggplot(toplot[type=='PersonEntersVehicle',.(n=length(time)*factor.to.scale.personal.back),by=c('hour','agency','iter')],aes(x=hour,y=n,fill=agency))+geom_bar(stat='identity')+facet_wrap(iter~agency)+labs(x="Hour",y="# Boarding Passengers",title=to.title(run.name),fill="Transit Agency")
pdf.scale <- .8
ggsave(pp(plots.dir,'transit-boarding.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

p <- ggplot(toplot[type=='PersonEntersVehicle' & agency%in%c('SF','AC','BA','VT'),.(n=length(time)*factor.to.scale.personal.back),by=c('hour','agency','iter')],aes(x=hour,y=n,fill=agency))+geom_bar(stat='identity')+facet_wrap(iter~agency)+labs(x="Hour",y="# Boarding Passengers",title=to.title(run.name),fill="Transit Agency")
pdf.scale <- .8
ggsave(pp(plots.dir,'transit-boarding-big-4.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

toplot.norm <- toplot[type=='PersonEntersVehicle' & agency%in%c('SF','AC','BA','VT'),.(n=length(time)*factor.to.scale.personal.back),by=c('hour','agency','iter')]
toplot.norm.max <- toplot.norm[,.(max=max(n)),by=c('agency','iter')]
toplot.norm <- join.on(toplot.norm,toplot.norm.max,c('agency','iter'),c('agency','iter'))
toplot.norm[,n.norm:=n/max]
p <- ggplot(toplot.norm,aes(x=hour,y=n.norm,fill=agency))+geom_bar(stat='identity')+facet_wrap(iter~agency)+labs(x="Hour",y="Normalized Boarding Passengers",title=to.title(run.name),fill="Transit Agency")
pdf.scale <- .8
ggsave(pp(plots.dir,'transit-boarding-big-4-normalized.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

p <- ggplot(ev[J('ModeChoice'),.(expMaxUtil=mean(access,na.rm=T)),by=c('iter','hr')],aes(x=hr,y=expMaxUtil))+geom_bar(stat='identity')+facet_wrap(~iter)+labs(x="Hour",y="Avg. Accessibility Score",title=to.title(run.name))
pdf.scale <- .8
ggsave(pp(plots.dir,'accessibility.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')



### Transit analysis, group transit trips (i.e. vehicles) and agencies by ridership
toplot<-ev[J('PathTraversal')][tripmode=='transit' & iter==0]
toplot[,agency:=unlist(lapply(str_split(vehicle_id,":"),function(x){ x[1] }))]
toplot[,transitTrip:=unlist(lapply(str_split(vehicle_id,":"),function(x){ x[2] }))]
toplot <- toplot[,.(numPassengers=sum(num_passengers),pmt=sum(num_passengers*length/1609)),by=c('agency','transitTrip')]

write.csv(toplot,file=pp(plots.dir,'transit-use-by-trip.csv'))
write.csv(toplot[pmt<=20],file=pp(plots.dir,'transit-trips-below-20-pmt.csv'))
write.csv(toplot[pmt<=60],file=pp(plots.dir,'transit-trips-below-60-pmt.csv'))

toplot <- data.table(numPassengers=toplot$numPassengers,pmt=toplot$pmt)
toplot[,type:='# Passengers']
setkey(toplot,numPassengers)
toplot[,i:=1:nrow(toplot)]
toplot2 <- copy(toplot)
toplot2[,type:='PMT']
setkey(toplot2,pmt)
toplot2[,i:=1:nrow(toplot)]
pdf.scale <- .8
p<-ggplot(toplot,aes(x=numPassengers,y=cumsum(toplot$numPassengers)/sum(toplot$numPassengers)))+geom_line()+geom_line(data=toplot2,aes(x=pmt,y=cumsum(toplot2$pmt)/sum(toplot2$pmt)))+facet_wrap(~type,scale='free_x')+labs(x='# Passengers (left), PMT (right)',y='Cumulative Fraction',title='Transit Fleet Utilization')
ggsave(pp(plots.dir,'cumul-transit-v-metric.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')
p<-ggplot(toplot,aes(x=i,y=cumsum(toplot$numPassengers)/sum(toplot$numPassengers)))+geom_line()+geom_line(data=toplot2,aes(x=i,y=cumsum(toplot2$pmt)/sum(toplot2$pmt)))+facet_wrap(~type,scale='free_x')+labs(x='Trip #',y='Cumulative Fraction',title='Transit Fleet Utilization')
ggsave(pp(plots.dir,'cumul-transit-v-trip.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')


# Modality style distributions
if('customAttributes' %in% names(pop)){
  pop[,style:=customAttributes]
  pop <- pop[style!='']
  if(any(u(pop$style)=='class1')){
    new.names <- c(class1='Multimodals',class2='Empty nesters',class3='Transit takers',class4='Inveterate drivers',class5='Moms in cars',class6='Car commuters')
    pop[,style:=new.names[as.character(style)]]
  }
  toplot <- pop[,.(n=length(type)),by=c('style','iter')]
  setkey(toplot,style,iter)

  pdf.scale <- .8
  p<-ggplot(toplot,aes(x=iter,y=n,fill=style))+geom_bar(stat='identity',position='stack')+labs(x='Trip #',y='# Agents',title='Modality Style Convergence')
  ggsave(pp(plots.dir,'modality-styles-v-iteration.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

  
}



