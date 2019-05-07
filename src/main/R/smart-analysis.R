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
load.libraries(c('maptools','sp','stringr','ggplot2','rgdal','viridis','h3js'))

##############################################################################################################################################
# COMMAND LINE OPTIONS 
option_list <- list(
)
if(interactive()){
  #setwd('~/downs/')
  args<-'/Users/critter/Documents/beam/beam-output/smart-2019-04-amr-final/'
  #args<-'/Users/critter/Downloads/diffusion-5iter/diffusion/'
  args <- parse_args(OptionParser(option_list = option_list,usage = "smart-analysis.R [experiment-directory]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "smart-analysis.R [experiment-directory]"),positional_arguments=T)
}
######################################################################################################

# TODO make these come from conf file
factor.to.scale.personal.back <- 1/0.08
factor.to.scale.transit.back <- 1/.22 # inverse of param: beam.agentsim.tuning.transitCapacity
plot.congestion <- F
plot.pop <- T

######################################################################################################
# Load the exp config
exp.dir <- ifelse(strtail(args$args)=="/",args$args,pp(args$args,"/"))
plots.dir <- pp(exp.dir,'plots/')
make.dir(plots.dir)

run.dirs <- grep('coupled',grep('plot',list.dirs(exp.dir,recursive=F),value=T,invert=T),value=T,invert=T)
scens <- unlist(lapply(lapply(str_split(run.dirs,"sfbay-smart-"),function(ll){ str_split(ll[2],'__')}),function(lll){ lll[[1]][1]}))
scen.names <- list('base'='Base','a-lt'='Sharing is Caring (Low Tech)','a-ht'='Sharing is Caring (High Tech)','b-lt'='Technology Takeover (Low Tech)','b-ht'='Technology Takeover (High Tech)','c-lt'='All About Me (Low Tech)','c-ht'='All About Me (High Tech)')
scen.names.short <- list('base'='Base','a-lt'='A (LT)','a-ht'='A (HT)','b-lt'='B (LT)','b-ht'='B (HT)','c-lt'='C (LT)','c-ht'='C (HT)')
scen.names.simp <- c('Base','Sharing is Caring','Technology Takeover','All About Me')

events.in.use <- c('PathTraversal','ModeChoice')#,'PersonEntersVehicle','PersonLeavesVehicle')

disag.evs <- list()
evs <- list()
links <- list()
pops <- list()
waits <- list()
run.i <- 1
for(run.i in 1:length(run.dirs)){
  run.dir <- run.dirs[run.i]
  scen <- scens[run.i]
  my.cat(pp('Loading scenario: ',scen))
  last.iter <- tail(sort(unlist(lapply(str_split(list.files(pp(run.dir,'/ITERS')),"it."),function(x){ as.numeric(x[2])}))),1)
  if(!file.exists(pp(run.dir,'/ITERS/it.',last.iter,'/',last.iter,'.population.csv.gz')))last.iter <- last.iter - 1
  events.csv <- pp(run.dir,'/ITERS/it.',last.iter,'/',last.iter,'.events.csv')
  tt.csv <- pp(run.dir,'/ITERS/it.',last.iter,'/',last.iter,'.linkstats.csv.gz')
  need.to.load.all.evs <- F
  for(in.use in events.in.use){
    in.use.file <- pp(events.csv,'-',in.use,'.Rdata')
    if(!file.exists(pp(in.use.file)))need.to.load.all.evs <- T
  }
  if(need.to.load.all.evs){
    ev <- csv2rdata(events.csv)
    ev[,scen.name:=factor(scen.names[[scen]],unlist(scen.names))]
    ev[,scen.name.short:=scen.names.short[[scen]]]
    ev[,scen:=scen]
  }
  for(in.use in events.in.use){
    in.use.file <- pp(events.csv,'-',in.use,'.Rdata')
    if(file.exists(pp(in.use.file))){
      load(in.use.file)
      evs[[in.use]][[length(evs[[in.use]])+1]] <- df
      rm('df')
    }else{
      evs[[in.use]][[length(evs[[in.use]])+1]] <- ev[type == in.use]
      df <- ev[type == in.use]
      save(df,file=in.use.file)
    }
  }
  if(plot.congestion){
    link <- parse.link.stats(tt.csv)
    streval(pp('link[,',fact,':="',the.level,'"]'))
    for(fact in factors){
      the.level <- streval(pp('exp$',fact,'[run.i]'))
      streval(pp('link[,',fact,':="',the.level,'"]'))
    }
    links[[length(links)+1]] <- link
  }
  if(plot.pop){
    pop.csv <- pp(run.dir,'/ITERS/it.',last.iter,'/',last.iter,'.population.csv.gz')
    if(file.exists(pop.csv)){
      pop <- csv2rdata(pop.csv)
      pop[,scen.name:=factor(scen.names[[scen]],unlist(scen.names))]
      pop[,scen.name.short:=scen.names.short[[scen]]]
      pop[,scen:=scen]
      pops[[length(pops)+1]] <- pop
    }
    wait.csv <- pp(run.dir,'/ITERS/it.',last.iter,'/',last.iter,'.rideHailWaitingStats.csv')
    if(file.exists(wait.csv)){
      wait <- csv2rdata(wait.csv)
      wait[,scen.name:=factor(scen.names[[scen]],unlist(scen.names))]
      wait[,scen.name.short:=scen.names.short[[scen]]]
      wait[,scen:=scen]
      waits[[length(waits)+1]] <- wait
    }
  }
}
ev <- list()
for(in.use in events.in.use){
  ev[[in.use]] <- rbindlist(evs[[in.use]],use.names=T,fill=T)
}
rm(c('evs'))
if(plot.pop){
  pop <- rbindlist(pops,use.names=T,fill=T)
  rm('pops')
  wait <- rbindlist(waits,use.names=T,fill=T)
  rm('waits')
}
if(plot.congestion){
  link <- rbindlist(links,use.names=T,fill=T)
  rm('links')
}

ev <- clean.and.relabel(ev,factor.to.scale.personal.back,factor.to.scale.transit.back)

to.simp <- function(x){ 
  x.str <- ifelse(x=='Base','Base',ifelse(grepl('Sharing',x),'Sharing is Caring',ifelse(grepl('All ',x),'All About Me','Technology Takeover')))
  factor(x.str,scen.names.simp)
}
scale_fill_manual(values = colours)
fact <- 'scenario'

#########################
# Mode Choice Plots
#########################
mc <- ev[['ModeChoice']]
setkey(mc,scen,time)
mc[,tripIndex:=1:length(time),by=c('scen','person','tourIndex')]
  
# Modal splits 
toplot <- mc[tripIndex==1,.(tot=length(time)),by=scen.name]
toplot <- join.on(mc[tripIndex==1,.(num=length(time)),by=c('scen.name','tripmode')],toplot,'scen.name','scen.name')
toplot[,frac:=num/tot]
toplot[,tripmode:=pretty.modes(tripmode)]
setkey(toplot,scen.name,tripmode)
p <- ggplot(toplot,aes(x=scen.name,y=frac*100,fill=tripmode))+geom_bar(stat='identity',position='stack')+
  labs(x="Scenario",y="% of Trips",fill="Trip Mode")+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))+
  scale_fill_manual(values=as.character(mode.colors$color.hex[match(sort(u(toplot$tripmode)),mode.colors$key)]))
pdf.scale <- .6
ggsave(pp(plots.dir,'mode-split-by-',fact,'.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')
toplot[,tech:=ifelse(grepl('High',scen.name),'High',ifelse(grepl('Low',scen.name),'Low','Base'))]
toplot[,scen.simp:=to.simp(scen.name)]
#p <- ggplot(toplot,aes(x=scen.simp,y=frac*100,fill=tripmode,group=tech))+geom_bar(stat='identity',position='dodge',colour='black')+
  #labs(x="Scenario",y="% of Trips",fill="Trip Mode")+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))+
  #scale_fill_manual(values=as.character(mode.colors$color.hex[match(sort(u(toplot$tripmode)),mode.colors$key)]))

p <- ggplot(toplot,aes(x=scen.name,y=num,fill=tripmode))+geom_bar(stat='identity',position='stack')+
  labs(x="Scenario",y="Number of Trips",fill="Trip Mode")+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))+
  scale_fill_manual(values=as.character(mode.colors$color.hex[match(sort(u(toplot$tripmode)),mode.colors$key)]))
pdf.scale <- .6
ggsave(pp(plots.dir,'mode-split-abs-by-',fact,'.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')
write.csv(toplot,file=pp(plots.dir,'mode-split-by-',fact,'.csv'))

# Trip distances by mode
toplot <- mc[,.(dist=mean(length/1609)),by=c('tripmode','scen.name')]
toplot[,tripmode:=pretty.modes(tripmode)]
setkey(toplot,scen.name,tripmode)
ggplot(toplot,aes(x=scen.name,y=dist,fill=tripmode))+geom_bar(stat='identity')+facet_wrap(~tripmode)+
  labs(x="Scenario",y="Mean Trip Distance",fill="Trip Mode")+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))+
  scale_fill_manual(values=as.character(mode.colors$color.hex[match(sort(u(toplot$tripmode)),mode.colors$key)]))

# "Bad things happen" incidence

bads <- mc[mode=='walk' & !currentTourMode%in%c('','walk')]


# Modal splits by hour
#toplot <- mc[tripIndex==1,.(tot=length(time)),by=c('hr','scen.name')]
#toplot <- join.on(mc[tripIndex==1,.(num=length(time)),by=c('scen.name','hr','tripmode')],toplot,c('scen.name','hr'),c('scen.name','hr'))
#toplot[,frac:=num/tot]
#toplot[,tripmode:=pretty.modes(tripmode)]
#setkey(toplot,hr,scen.name,tripmode)
#p <- ggplot(toplot,aes(x=hr,y=frac*100,fill=tripmode))+geom_bar(stat='identity',position='stack')+labs(x="Scenario",y="% of Trips",title=pp('Factor: ',fact),fill="Trip Mode")+facet_wrap(~scen.name)
    #scale_fill_manual(values=as.character(mode.colors$color.hex[match(sort(u(toplot$tripmode)),mode.colors$key)]))
#pdf.scale <- .6
#ggsave(pp(plots.dir,'mode-split-by-hour-by-',fact,'.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')
#write.csv(toplot,file=pp(plots.dir,'mode-split-by-hour-',fact,'.csv'))

rm('mc')


#########################
# Path Traversal Plots
#########################
pt <- ev[['PathTraversal']]
# Energy by Mode

#pt[vehicleType%in%c('ConventionalGas_Low','ConventionalGas_High','ConventionalGas_CAV','RH_ConventionalGas_Low','RH_ConventionalGas_High','RH_ConventionalGas_CAV') & scen%in%c('b-lt','c-lt'),primaryFuel:=length/(55 * 8.3141841e-9 * 1609)]
#pt[vehicleType%in%c('ConventionalGas-48V_Low','ConventionalGas-48V_High','RH_ConventionalGas-48V_Low','RH_ConventionalGas-48V_High') & scen%in%c('a-lt'),primaryFuel:=length/(60 * 8.3141841e-9 * 1609)]
n.peeps <- pop[,.(n.peeps=.N),by=c('id','scen')][,.(n.peeps=.N),by='scen']
pt <- join.on(pt,n.peeps,'scen','scen')

toplot <- pt[,.(fuel=sum(primaryFuel+secondaryFuel),numVehicles=as.double(length(fuel)),numberOfPassengers=as.double(sum(numPassengers)),pmt=sum(pmt),vmt=sum(length)/1609,mpg=sum(length)/1609/sum((primaryFuel+secondaryFuel)/3.78),n.peeps=n.peeps[1]),by=c('scen.name','vehicleType','tripmode')]
toplot <- toplot[vehicleType!='Human' & tripmode!="walk"]
toplot[,energy:=fuel]
toplot[tripmode%in%c('car','ride_hail','ride_hail_pooled','cav'),energy:=fuel*factor.to.scale.personal.back]
toplot[tripmode%in%c('car','ride_hail','ride_hail_pooled','cav'),numVehicles:=numVehicles*factor.to.scale.personal.back]
toplot[tripmode%in%c('car','ride_hail','ride_hail_pooled','cav'),pmt:=pmt*factor.to.scale.personal.back]
toplot[tripmode%in%c('car','ride_hail','ride_hail_pooled','cav'),numberOfPassengers:=numVehicles]
toplot[,ag.mode:=tripmode]
toplot <- toplot[ag.mode!='bike']
toplot[ag.mode=='car',ag.mode:='Car']
toplot[ag.mode=='cav',ag.mode:='CAV']
toplot[ag.mode=='transit',ag.mode:='Transit']
toplot[ag.mode=='ride_hail_pooled',ag.mode:='Ride Hail - Pooled']
toplot[ag.mode=='ride_hail',ag.mode:='Ride Hail']
toplot.ag <- toplot[,.(energy=sum(energy),pmt=sum(pmt),energy.per.cap=sum(energy)/n.peeps[1]),by=c('scen.name','ag.mode')]
pdf.scale <- .6
setkey(toplot.ag,scen.name,ag.mode)
p <- ggplot(toplot.ag,aes(x=scen.name,y=energy/1e12,fill=ag.mode))+geom_bar(stat='identity',position='stack')+labs(x="Scenario",y="Energy Consumption (TJ)",fill="Trip Mode")+
    scale_fill_manual(values=as.character(mode.colors$color.hex[match(sort(u(toplot.ag$ag.mode)),mode.colors$key)]))+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))
ggsave(pp(plots.dir,'energy-by-mode-',fact,'.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')
write.csv(toplot.ag,file=pp(plots.dir,'energy-by-mode-',fact,'.csv'))
p <- ggplot(toplot.ag,aes(x=scen.name,y=energy.per.cap/1e6,fill=ag.mode))+geom_bar(stat='identity',position='stack')+labs(x="Scenario",y="Per Capita Energy Consumption (MJ)",fill="Trip Mode")+
    scale_fill_manual(values=as.character(mode.colors$color.hex[match(sort(u(toplot.ag$ag.mode)),mode.colors$key)]))+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))
ggsave(pp(plots.dir,'energy-per-cap-by-mode-',fact,'.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')

per.pmt <- toplot[,.(energy=sum(energy)/sum(pmt)),by=c('scen.name','tripmode')]
per.pmt[,tripmode:=pretty.modes(tripmode)]
the.first <- per.pmt[scen.name==per.pmt$scen.name[1]]
per.pmt[,tripmode:=factor(tripmode,levels=the.first$tripmode[rev(order(the.first$energy))])]
p <- ggplot(per.pmt[energy<Inf],aes(x=scen.name,y=energy,fill=tripmode))+geom_bar(stat='identity',position='dodge')+labs(x="Scenario",y="Energy Consumption (MJ/passenger mile)",fill="Trip Mode")+scale_fill_manual(values=as.character(mode.colors$color.hex[match(levels(per.pmt$tripmode),mode.colors$key)]))+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))
ggsave(pp(plots.dir,'energy-per-pmt-by-vehicle-type-',fact,'.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')

toplot <- pt[,.(fuel1=sum(primaryFuel),fuel2=sum(secondaryFuel),numVehicles=as.double(length(fuel)),numberOfPassengers=as.double(sum(numPassengers)),pmt=sum(pmt),vmt=sum(length)/1609,n.peeps=n.peeps[1]),by=c('scen.name','primaryFuelType','secondaryFuelType')]
p <- ggplot(toplot[!primaryFuelType%in%c('Food')][,.(energy=sum(fuel1)/sum(n.peeps)),by=c('scen.name','primaryFuelType')],aes(x=scen.name,y=energy/1e6,fill=primaryFuelType))+geom_bar(stat='identity',position='stack')+labs(x="Scenario",y="Per Capita Energy Consumption (MJ/person)",fill="Fuel Type")+scale_fill_manual(values=as.character(mode.colors$color.hex[c(1,4,3,2)]))+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))
ggsave(pp(plots.dir,'energy-by-fuel-type.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')

# Deadheading 
toplot <- pt[tripmode=='ride_hail',.(dead=numPassengers==0,miles=length/1609,hr,scen.name)]
setkey(toplot,hr,dead)
dead.frac <- toplot[,.(dead.frac=pp(roundC(100*sum(miles[dead==T])/sum(miles),1),"% Empty")),by=c('scen.name')]
toplot <- toplot[,.(miles=sum(miles)),by=c('dead','hr','scen.name')]
p <- ggplot(toplot,aes(x=hr,y=miles,fill=dead))+geom_bar(stat='identity')+labs(x="Hour",y="Vehicle Miles Traveled",fill="Empty",title=pp("Ride Hail Deadheading"))+geom_text(data=dead.frac,hjust=1,aes(x=24,y=max(toplot$miles),label=dead.frac,fill=NA))+facet_wrap(~scen.name)
pdf.scale <- .6
ggsave(pp(plots.dir,'dead-heading.pdf'),p,width=10*pdf.scale,height=10*pdf.scale,units='in')

# MPG-e
pt[,mpge:=length/1609/((primaryFuel+secondaryFuel)/120276384)] # 120276384 J per gallon
p <- ggplot(pt[mpge<250 & tripmode%in%c('car','ride_hail','cav')],aes(x=mpge))+geom_histogram()+facet_wrap(~vehicleType,scales='free_y')
ggsave(pp(plots.dir,'mpge.pdf'),p,width=25*pdf.scale,height=15*pdf.scale,units='in')
mpge <- pt[,.(mpge=weighted.mean(mpge,length),scen=as.character(scen[1])),by=c('scen.name','vehicleType','tripmode')]
mpge[,rideHail:=substr(vehicleType,1,3)=='RH_']
mpge[,actualVehicleType:=ifelse(substr(vehicleType,1,3)=='RH_',substr(vehicleType,4,nchar(as.character(vehicleType))),as.character(vehicleType))]
mpge[,vehicleScen:=ifelse(scen=='base','Base',ifelse(grepl('-ht',scen),'High','Low'))]
#p <- ggplot(mpge[tripmode%in%c('car','ride_hail')],aes(x=scen.name,y=mpge,fill=actualVehicleType,colour=rideHail))+geom_bar(stat='identity',position='dodge')
p <- ggplot(mpge[tripmode%in%c('car','ride_hail','cav')],aes(x=scen.name,y=mpge,fill=vehicleScen))+geom_bar(stat='identity',position='dodge')+facet_wrap(~actualVehicleType)+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))
ggsave(pp(plots.dir,'mpge-avg.pdf'),p,width=25*pdf.scale,height=15*pdf.scale,units='in')

# Speed
pt[,speed:=length/1609/((time-departureTime)/3600)]
sp <- pt[speed<Inf & tripmode%in%c('car','ride_hail','cav'),.(speed=weighted.mean(speed,length)),by='scen.name']
p <- ggplot(sp,aes(x=scen.name,y=speed))+geom_bar(stat='identity')+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))
ggsave(pp(plots.dir,'speed-avg.pdf'),p,width=6*pdf.scale,height=8*pdf.scale,units='in')

# RH Waits 
p <- ggplot(wait[,.(wait=weighted.mean(avgWait, numberOfPickups)/60),by='scen.name'],aes(x=scen.name,y=wait))+geom_bar(stat='identity')+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))+labs(x='',y='Mean Customer Wait Time (min)')
ggsave(pp(plots.dir,'rh-wait-times.pdf'),p,width=6*pdf.scale,height=8*pdf.scale,units='in')

# VMT
pt[,tripmode2:=as.character(tripmode)]
pt[tripmode%in%c('car','ride_hail','ride_hail_pooled','cav') & numPassengers==0,tripmode2:=pp(tripmode,'-empty')]
pt[tripmode%in%c('car','ride_hail','ride_hail_pooled','cav') & numPassengers>1,tripmode2:=pp(tripmode,'-shared')]
sp <- pt[!tripmode%in%c('walk','bike'),.(vmt=sum(length/1609),vmt.per.cap=sum(length)/1609/n.peeps[1]),by=c('tripmode','tripmode2','scen.name')]
sp[,tripmode:=pretty.modes(tripmode)]
setkey(sp,'tripmode','tripmode2','scen.name')
p <- ggplot(sp,aes(x=scen.name,y=vmt,fill=tripmode))+geom_bar(stat='identity',colour='black')+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))+scale_fill_manual(values=as.character(mode.colors$color.hex[match(pretty.modes(sort(u(sp$tripmode))),mode.colors$key)]))+labs(x='',y='Vehicle Miles Traveled',fill='Mode')
ggsave(pp(plots.dir,'vmt.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')
p <- ggplot(sp,aes(x=scen.name,y=vmt.per.cap,fill=tripmode))+geom_bar(stat='identity',colour='black')+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))+scale_fill_manual(values=as.character(mode.colors$color.hex[match(pretty.modes(sort(u(sp$tripmode))),mode.colors$key)]))+labs(x='',y='Vehicle Miles Traveled',fill='Mode')
ggsave(pp(plots.dir,'vmt-per-cap.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')

sp <- pt[,.(vmt=sum(length/1609)),by=c('tripmode','numPassengers','scen.name')]
sp[,tripmodePass:=pp(tripmode,'-',ifelse(tripmode=='transit','',numPassengers))]
p <- ggplot(sp,aes(x=scen.name,y=vmt,fill=))+geom_bar(stat='identity')+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))#+scale_fill_manual(values=as.character(mode.colors$color.hex[match(sort(u(sp$tripmode)),mode.colors$key)]))
ggsave(pp(plots.dir,'vmt-num-pass.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

# Miles per trip
pt[,.(miles.per.trip=sum(length)/1609/.N),by=c('scen','tripmode')]

pt.cav <- pt[tripmode=='cav']

# Commute dists
comm <- pop[,.(dist.to.work=sqrt((x[1]-x[2])^2+(y[1]-y[2])^2)),by=c('id','scen')]
comm[,.(mean(dist.to.work)/22527.45,median(dist.to.work)/17232.54),by='scen']

# VHT & PHT
vht <- pt[,.(vht=sum(time-departureTime,na.rm=T)/3600,vht.per.cap=sum(time-departureTime,na.rm=T)/3600/n.peeps[1]),by=c('scen.name','tripmode')]
vht[,tripmode.pretty:=pretty.modes(tripmode)]
pht <- pt[numPassengers>0,.(pht=sum((time-departureTime)/numPassengers,na.rm=T)/3600,pht.per.cap=sum((time-departureTime)/numPassengers,na.rm=T)/3600/n.peeps[1]),by=c('scen.name','tripmode')]
pht[,tripmode.pretty:=pretty.modes(tripmode)]
p <- ggplot(vht,aes(x=scen.name,y=vht,fill=tripmode.pretty))+geom_bar(stat='identity')+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))+scale_fill_manual(values=as.character(mode.colors$color.hex[match((sort(u(vht$tripmode.pretty))),mode.colors$key)]))+labs(x="",y="Vehicle Hours Traveled",fill="Trip Mode")
ggsave(pp(plots.dir,'vht.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')
p <- ggplot(vht[tripmode%in%c('car','cav','ride_hail','ride_hail_pooled')],aes(x=scen.name,y=vht.per.cap,fill=tripmode.pretty))+geom_bar(stat='identity',colour='black')+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))+scale_fill_manual(values=as.character(mode.colors$color.hex[match((sort(u(vht[tripmode%in%c('car','cav','ride_hail','ride_hail_pooled')]$tripmode.pretty))),mode.colors$key)]))+labs(x="",y="Vehicle Hours Traveled",fill="Trip Mode")
ggsave(pp(plots.dir,'vht-ldv-per-cap.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')
p <- ggplot(vht,aes(x=scen.name,y=vht.per.cap,fill=tripmode.pretty))+geom_bar(stat='identity')+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))+scale_fill_manual(values=as.character(mode.colors$color.hex[match((sort(u(vht$tripmode.pretty))),mode.colors$key)]))+labs(x="",y="Vehicle Hours Traveled per Capita",fill="Trip Mode")
ggsave(pp(plots.dir,'vht-per-cap.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')
p <- ggplot(vht[tripmode%in%c('car','cav','ride_hail')],aes(x=scen.name,y=vht.per.cap,fill=tripmode.pretty))+geom_bar(stat='identity')+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))+scale_fill_manual(values=as.character(mode.colors$color.hex[match((sort(u(vht[tripmode%in%c('car','cav','ride_hail')]$tripmode.pretty))),mode.colors$key)]))+labs(x="",y="Vehicle Hours Traveled per Capita",fill="Trip Mode")
ggsave(pp(plots.dir,'vht-ldv-per-cap.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')

p <- ggplot(pht,aes(x=scen.name,y=pht,fill=tripmode.pretty))+geom_bar(stat='identity')+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))+scale_fill_manual(values=as.character(mode.colors$color.hex[match((sort(u(pht$tripmode.pretty))),mode.colors$key)]))+labs(x="",y="Person Hours Traveled",fill="Trip Mode")
ggsave(pp(plots.dir,'pht.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')
p <- ggplot(pht,aes(x=scen.name,y=pht.per.cap,fill=tripmode.pretty))+geom_bar(stat='identity')+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))+scale_fill_manual(values=as.character(mode.colors$color.hex[match((sort(u(pht$tripmode.pretty))),mode.colors$key)]))+labs(x="",y="Person Hours Traveled per Capita",fill="Trip Mode")
ggsave(pp(plots.dir,'pht-per-cap.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')
p <- ggplot(pht[tripmode%in%c('car','cav','ride_hail')],aes(x=scen.name,y=pht.per.cap,fill=tripmode.pretty))+geom_bar(stat='identity')+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))+scale_fill_manual(values=as.character(mode.colors$color.hex[match((sort(u(pht[tripmode%in%c('car','cav','ride_hail')]$tripmode.pretty))),mode.colors$key)]))+labs(x="",y="Person Hours Traveled per Capita",fill="Trip Mode")
ggsave(pp(plots.dir,'pht-ldv-per-cap.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')

# VMT per person results for Max
vmt.per.peep <- pt[,.(vmt=sum(length)/1609),by=c('tripmode','scen','driver')]
write.csv(vmt.per.peep,file=pp(plots.dir,'vmt-per-person.csv'))

# Num Vehicles
if(F){
  nveh <- pt[,.(nveh=length(u(vehicle))),by=c('scen.name','vehicleType')]
  nveh[,type:=ifelse(grepl('BUS',vehicleType),'BUS',ifelse(grepl('Conventional-Car',vehicleType) | grepl('ConventionalGas',vehicleType),'Car',ifelse(grepl('Conventional-Truck',vehicleType),'Truck',ifelse(grepl('ConventionalDiesel',vehicleType),'Diesel',ifelse(grepl('BEV',vehicleType),'BEV',
    ifelse(grepl('PHEV',vehicleType),'PHEV',ifelse(grepl('HEV',vehicleType),'HEV',ifelse(grepl('Bike',vehicleType),'Bike',ifelse(grepl('BODY',vehicleType),'BODY',ifelse(grepl('RAIL',vehicleType),'RAIL',''))))))))))]
  nveh[,nveh.scaled:=nveh]
  nveh[!type%in%c('','BUS','RAIL'),nveh.scaled:=nveh*factor.to.scale.personal.back]
  nveh[type%in%c('','BUS','RAIL'),nveh.scaled:=nveh*factor.to.scale.transit.back]
  nveh[,type:=factor(as.character(type),c("BEV",'PHEV','HEV','Diesel','Truck',"Car","Bike","BODY","BUS","","RAIL"))]
  save(nveh,file=pp(plots.dir,'num-vehicles.Rdata'))
  load(file=pp(plots.dir,'num-vehicles.Rdata'))
  nveh[type%in%c('Car','Truck','Diesel'),type:='Gasoline']
  nveh[,type:=factor(as.character(type),c("BEV",'PHEV','HEV','Diesel','Gasoline',"Bike","BODY","BUS","","RAIL"))]
  p <- ggplot(nveh[!type%in%c('','BUS','Bike','BODY','RAIL')][,.(nveh.scaled=sum(nveh.scaled)),by=c('scen.name','type')],aes(x=scen.name,y=nveh.scaled/1e6,fill=type))+geom_bar(stat='identity')+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))+scale_fill_manual(values=as.character(mode.colors$color.hex[c(3,8,1,2)]))+labs(x="",y="Number of Vehicles (millions)",fill="Powertrain")
  ggsave(pp(plots.dir,'num-vehicles.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')
}


rm('pt')


# Skim obs vs modeled
sk<-data.table(read.csv('~/Documents/beam/beam-output/smart-2019-04-amr-final/plots/30.tazODTravelTimeObservedVsSimulated-green.csv.gz'))
sks <- list()
for(drop.i in 0:5){
  sks[[length(sks)+1]] <- sk[counts>drop.i]
  sks[[length(sks)]][,drop:=drop.i] 
}
sks <- rbindlist(sks)
p <- ggplot(sks[drop==3],aes(x=timeObserved,y=timeSimulated*1.1,colour=factor(counts)))+geom_point(alpha=0.25)+geom_abline(slope=1,intercept=0)
ggsave(pp(plots.dir,'skims-obs-v-pred-3-and-above.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')
p <- ggplot(sks,aes(x=timeObserved,y=timeSimulated,colour=factor(counts)))+geom_point(alpha=0.25)+facet_wrap(~drop)+geom_abline(slope=1,intercept=0)
ggsave(pp(plots.dir,'skims-obs-v-pred-by-drop.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')
p<-ggplot(sk[,.(sim=weighted.mean(timeSimulated,counts),obs=mean(timeObserved),n=sum(counts)),by='hour'],aes(x=obs,y=sim,size=n))+geom_point()+geom_abline(slope=1,intercept=0)
ggsave(pp(plots.dir,'skims-obs-v-pred-weighted-mean.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

# Spatial Distribution of ride hail in SF
sf.shapefile <- '/Users/critter/Dropbox/ucb/vto/beam-colin/sf-bay-area/tnc_od_typwkday_hourly/sf-bay-area-counties/s7hs4j'
shape <- readOGR(pp(sf.shapefile,".shp"),tail(strsplit(sf.shapefile,"/")[[1]],1))
sf.coords <- data.table(shape[which(shape@data$COUNTY=='San Francisco'),]@polygons[[1]]@Polygons[[1]]@coords)[,.(x=V1,y=V2)]

rh <- pt[scen=='base' & tripmode=='ride_hail' & startX > -122.519416 & startY < 37.810030 & startX < -122.348440 & startY > 37.705133]
rh[,startH3.7:=h3_geo_to_h3(startY,startX,7)]
rh[,startH3.8:=h3_geo_to_h3(startY,startX,8)]
rh[,startH3.9:=h3_geo_to_h3(startY,startX,9)]
rh.density <- rh[,.(n=.N),by='startH3.8']
hexes <- rbindlist(lapply(rh.density$startH3.8,function(ll){ x <- data.table(h3_to_geo_boundary(ll)); x[,':='(id=ll,x=V2,y=V1,V1=NULL,V2=NULL)]; x}))
hexes <- join.on(hexes,rh.density,'id','startH3.8')
p <- ggplot(hexes,aes(x=x,y=y,fill=n,group=id))+geom_polygon()+geom_polygon(data=sf.coords,aes(x=x,y=y),fill=NA,colour='black',group=1,size=2)
ggsave(pp(plots.dir,'rh-validation-base.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')

load("/Users/critter/Dropbox/ucb/vto/beam-colin/sf-bay-area/tnc_od_typwkday_hourly/tnc_od_typwkday_hourly.Rdata")
tnc.rh <- melt(df,id.vars=c('hour','start_taz','day_of_week'))
tnc.rh[,':='(end_taz=substr(variable,2,nchar(as.character(variable))),variable=NULL)]
tnc.start.density <- tnc.rh[,.(n=sum(value)),by='start_taz']
load("/Users/critter/Dropbox/ucb/vto/beam-colin/sf-bay-area/tnc_od_typwkday_hourly/tnc2day.Rdata")
tnc.start.density <- join.on(tnc.start.density,tnc,'start_taz','taz')
tnc.start.density <- tnc.start.density[x > -122.519416 & y < 37.810030 & x < -122.348440 & y > 37.705133]
tnc.start.density[,startH3.8:=h3_geo_to_h3(y,x,8)]
tnc.start.density[,startH3.9:=h3_geo_to_h3(y,x,9)]
rh.density.tnc <- tnc.start.density[,.(n=sum(n)),by='startH3.8']
hexes.tnc <- rbindlist(lapply(rh.density.tnc$startH3.8,function(ll){ x <- data.table(h3_to_geo_boundary(ll)); x[,':='(id=ll,x=V2,y=V1,V1=NULL,V2=NULL)]; x}))
hexes.tnc <- join.on(hexes.tnc,rh.density.tnc,'id','startH3.8')
p <- ggplot(hexes.tnc,aes(x=x,y=y,fill=n,group=id))+geom_polygon()+geom_polygon(data=sf.coords,aes(x=x,y=y),fill=NA,colour='black',group=1,size=2)
ggsave(pp(plots.dir,'rh-validation-obs.pdf'),p,width=10*pdf.scale,height=12*pdf.scale,units='in')

hexes.tnc[,scen:='Observed']
hexes[,scen:='Simulated']
p <- ggplot(rbindlist(list(hexes[,.(x,y,id,scen,frac=(n/sum(n)))],hexes.tnc[,.(x,y,id,scen,frac=(n/sum(n)))])),aes(x=x,y=y,fill=frac,group=id))+geom_polygon()+geom_polygon(data=sf.coords,aes(x=x,y=y),fill=NA,colour='black',group=1,size=2)+facet_wrap(~scen)+scale_fill_viridis(discrete=F)
ggsave(pp(plots.dir,'rh-validation-both.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')




# Process the vehicle energy inputs to double check them

if(F){

  vt.dir <- '/Users/critter/Dropbox/ucb/vto/beam-all/beam/production/sfbay/smart/vehicle-tech'
  veh.dirs <- list.dirs(vt.dir,recursive=F)

  vt <- list()
  for(veh.dir in veh.dirs){
    veh.files <- list.files(veh.dir)
    for(veh.file in veh.files){
      df <- data.table(read.csv(pp(veh.dir,'/',veh.file)))
      setkey(df,speed_mph_float_bins,grade_percent_float_bins,num_lanes_int_bins)
      the.level <- ifelse(grepl('_L3_',veh.file),3,ifelse(grepl('_L5_',veh.file),5,1))
      df[,level:=the.level]
      df[,scen:=tail(str_split(veh.dir,"/")[[1]],1)]
      df[,file:=veh.file]
      token <- ifelse(the.level==1,'_explicitbin_model_only.csv.gz',ifelse(the.level==3,'_CAV_L3_explicitbin_model_only.csv.gz','_CAV_L5_explicitbin_model_only.csv.gz'))
      df[,mm:=str_split(veh.file,token)[[1]][1]]
      vt[[length(vt)+1]] <- df
    }
  }
  vt <- rbindlist(vt)

  vtype <- list()
  for(type.file in list.files(vt.dir,".csv")){
    vtype[[length(vtype)+1]] <- data.table(read.csv(pp(vt.dir,'/',type.file)))
    vtype[[length(vtype)]][,file:=type.file]
  }
  vtype <- rbindlist(vtype)[!is.na(sampleProbabilityWithinCategory) & sampleProbabilityWithinCategory>0]

  # Which used vehicle energy files are empty
  for(used.energy.file in u(vtype$primaryVehicleEnergyFile)){
    used.energy.file <- tail(str_split(used.energy.file,"/")[[1]],1)
    if(sum(vt[used.energy.file==file]$rate)==0)my.cat(used.energy.file)
  }
  vt[,sum(rate),by='mm'][V1==0]

  ggplot(vt,aes(x=scen,y=rate,colour=factor(level)))+geom_point()+facet_wrap(~mm)+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))

  ggplot(vt[unlist(lapply(str_split(vtype[primaryFuelType=='electricity' & secondaryFuelType!='gasoline' & grepl('tech-b.csv',file)]$primaryVehicleEnergyFile,"/"),function(ll){ tail(ll,1) })) == file],aes(x=scen,y=rate,colour=factor(level)))+geom_point()+facet_wrap(~mm)+theme_bw()+theme(axis.text.x = element_text(angle = 40, hjust = 1))
  

}
