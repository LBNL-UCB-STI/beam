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
  args<-'/Users/critter/Downloads/RH2Transit-2Iter/RH2Transit'
  args<-'/Users/critter/Downloads/cost-sensitivities/cost-sensitivity/'
  #args<-'/Users/critter/Downloads/diffusion-5iter/diffusion/'
  args <- parse_args(OptionParser(option_list = option_list,usage = "exp2plots.R [experiment-directory]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "exp2plots.R [experiment-directory]"),positional_arguments=T)
}
######################################################################################################

# TODO make these come from conf file
factor.to.scale.personal.back <- 20
factor.to.scale.transit.back <- 1/.22 # inverse of param: beam.agentsim.tuning.transitCapacity
plot.congestion <- F
plot.modality.styles <- F

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
links <- list()
pops <- list()
for(run.i in 1:nrow(exp)){
  grp <-  exp$experimentalGroup[run.i]
  run.dir <- pp(exp.dir,'runs/run.',grp,'/')
  output.dir <- ifelse(file.exists(pp(run.dir,'output_')),'output_','output')
  last.iter <- tail(sort(unlist(lapply(str_split(list.files(pp(run.dir,output.dir,'/ITERS')),"it."),function(x){ as.numeric(x[2])}))),1)
  if(!file.exists(pp(run.dir,output.dir,'/ITERS/it.',last.iter,'/',last.iter,'.population.csv.gz')))last.iter <- last.iter - 1
  events.csv <- pp(run.dir,output.dir,'/ITERS/it.',last.iter,'/',last.iter,'.events.csv')
  tt.csv <- pp(run.dir,output.dir,'/ITERS/it.',last.iter,'/',last.iter,'.linkstats.csv.gz')
  ev <- csv2rdata(events.csv)
  ev[,run:=grp]
  for(fact in factors){
    if(fact %in% names(ev))stop(pp('Factor name "',fact,'" also a column name in events.csv, please change factor name in experiments.csv'))
    the.level <- streval(pp('exp$',fact,'[run.i]'))
    streval(pp('ev[,',fact,':="',the.level,'"]'))
  }
  evs[[length(evs)+1]] <- ev[type%in%c('PathTraversal','ModeChoice')]
  if(plot.congestion){
    link <- parse.link.stats(tt.csv)
    streval(pp('link[,',fact,':="',the.level,'"]'))
    for(fact in factors){
      the.level <- streval(pp('exp$',fact,'[run.i]'))
      streval(pp('link[,',fact,':="',the.level,'"]'))
    }
    links[[length(links)+1]] <- link
  }
  if(plot.modality.styles){
    last.iter.minus.5 <- ifelse(last.iter>4,last.iter - 4,1)
    for(the.iter in last.iter.minus.5:last.iter){
      pop.csv <- pp(run.dir,output.dir,'/ITERS/it.',the.iter,'/',the.iter,'.population.csv.gz')
      if(file.exists(pop.csv)){
        pop <- csv2rdata(pop.csv)
        pop[,iter:=the.iter]
        streval(pp('pop[,',fact,':="',the.level,'"]'))
        pops[[length(pops)+1]] <- pop
      }
    }
  }
}
ev <- rbindlist(evs,use.names=T,fill=T)
rm('evs')
if(plot.modality.styles){
  pop <- rbindlist(pops,use.names=T,fill=T)
  rm('pops')
}
if(plot.congestion){
  link <- rbindlist(links,use.names=T,fill=T)
  rm('links')
}

ev <- clean.and.relabel(ev,factor.to.scale.personal.back,factor.to.scale.transit.back)

setkey(ev,type)

## Prep data needed to do quick version of energy calc
en <- data.table(read.csv('~/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/samples/vehicleTypes.csv'))
setkey(en,vehicleTypeId)
en <- u(en)
## Energy Density in MJ/liter or MJ/kWh
# https://en.wikipedia.org/wiki/Gasoline_gallon_equivalent
#en.density <- data.table(fuelType=c('gasoline','diesel','electricity','biodiesel'),density=c(31.81905,36.14286,3.6,33.2))
ev[tripmode%in%c('car') & vehicle_type=='Car',':='(num_passengers=1)]
ev[,pmt:=num_passengers*length/1609]
ev[is.na(pmt),pmt:=0]

scale_fill_manual(values = colours)


#########################
# Mode Choice Plots
#########################
mc <- ev[J('ModeChoice')]
setkey(mc,run,time)
mc[,tripIndex:=1:length(time),by=c('run','person','tourIndex')]
for(fact in factors){
  streval(pp('mc[,the.factor:=',fact,']'))
  if(all(c('low','base','high') %in% u(ev$the.factor))){
    mc[,the.factor:=factor(the.factor,levels=c('low','base','high'))]
  }else if(all(c('Low','Base','High') %in% u(mc$the.factor))){
    mc[,the.factor:=factor(the.factor,levels=c('Low','Base','High'))]
  }else{
    streval(pp('mc[,the.factor:=factor(the.factor,levels=exp$',fact,')]'))
  }
  
  # Modal splits 
  toplot <- mc[tripIndex==1,.(tot=length(time)),by=the.factor]
  toplot <- join.on(mc[tripIndex==1,.(num=length(time)),by=c('the.factor','tripmode')],toplot,'the.factor','the.factor')
  toplot[,frac:=num/tot]
  toplot[,tripmode:=pretty.modes(tripmode)]
  setkey(toplot,the.factor,tripmode)
  p <- ggplot(toplot,aes(x=the.factor,y=frac*100,fill=tripmode))+geom_bar(stat='identity',position='stack')+
    labs(x="Scenario",y="% of Trips",title=pp('Factor: ',fact),fill="Trip Mode")+
    scale_fill_manual(values=as.character(mode.colors$color.hex[match(sort(u(toplot$tripmode)),mode.colors$key)]))
  pdf.scale <- .6
  ggsave(pp(plots.dir,'mode-split-by-',fact,'.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')
  write.csv(toplot,file=pp(plots.dir,'mode-split-by-',fact,'.csv'))

  # Modal splits by hour
  toplot <- mc[tripIndex==1,.(tot=length(time)),by=c('hr','the.factor')]
  toplot <- join.on(mc[tripIndex==1,.(num=length(time)),by=c('the.factor','hr','tripmode')],toplot,c('the.factor','hr'),c('the.factor','hr'))
  toplot[,frac:=num/tot]
  toplot[,tripmode:=pretty.modes(tripmode)]
  setkey(toplot,hr,the.factor,tripmode)
  p <- ggplot(toplot,aes(x=hr,y=frac*100,fill=tripmode))+geom_bar(stat='identity',position='stack')+labs(x="Scenario",y="% of Trips",title=pp('Factor: ',fact),fill="Trip Mode")+facet_wrap(~the.factor)
      scale_fill_manual(values=as.character(mode.colors$color.hex[match(sort(u(toplot$tripmode)),mode.colors$key)]))
  pdf.scale <- .6
  ggsave(pp(plots.dir,'mode-split-by-hour-by-',fact,'.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')
  write.csv(toplot,file=pp(plots.dir,'mode-split-by-hour-',fact,'.csv'))

  target <- data.frame(tripmode=rep(c('Car','Walk','Transit','Ride Hail'),length(u(toplot$the.factor))),
                       perc=rep(c(79,4,13,5),length(u(toplot$the.factor))),
                       the.factor=rep(u(toplot$the.factor),each=4))
  p <- ggplot(toplot,aes(x=tripmode,y=frac*100))+geom_bar(stat='identity')+facet_wrap(~the.factor)+geom_point(data=target,aes(y=perc),colour='red')
  ggsave(pp(plots.dir,'mode-split-lines-by-',fact,'.pdf'),p,width=15*pdf.scale,height=8*pdf.scale,units='in')

  # Accessibility
  if(!all(is.nan(mc[1]$access))){
    pdf.scale <- .8
    p <- ggplot(mc[tripIndex==1,.(access=mean(access,na.rm=T)),by=c('the.factor','personalVehicleAvailable')],aes(x=the.factor,y=access))+geom_bar(stat='identity')+facet_wrap(~personalVehicleAvailable)+labs(x=fact,y="Avg. Accessibility Score",title='Accessibility by Availability of Private Car')
    ggsave(pp(plots.dir,'accessibility-by-private-vehicle.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')
    p <- ggplot(mc[tripIndex==1,.(access=mean(access,na.rm=T)),by=c('the.factor','mode')],aes(x=the.factor,y=access))+geom_bar(stat='identity')+facet_wrap(~mode)+labs(x=fact,y="Avg. Accessibility Score",title='Accessibility by Chosen Mode')
    ggsave(pp(plots.dir,'accessibility-by-chosen-mode.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')
    p <- ggplot(mc[tripIndex==1,.(access=mean(access,na.rm=T)),by=c('the.factor')],aes(x=the.factor,y=access))+geom_bar(stat='identity')+labs(x=fact,y="Avg. Accessibility Score",title='Overall Accessibility')
    ggsave(pp(plots.dir,'accessibility.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')
  }
}
rm('mc')


#########################
# Path Traversal Plots
#########################
pt <- ev[J('PathTraversal')]
for(fact in factors){
  # Energy by Mode
  streval(pp('pt[,the.factor:=',fact,']'))
  if(all(c('low','base','high') %in% u(pt$the.factor))){
    pt[,the.factor:=factor(the.factor,levels=c('low','base','high'))]
  }else if(all(c('Low','Base','High') %in% u(ev$the.factor))){
    pt[,the.factor:=factor(the.factor,levels=c('Low','Base','High'))]
  }else{
    streval(pp('pt[,the.factor:=factor(the.factor,levels=exp$',fact,')]'))
  }

  toplot <- pt[,.(fuel=sum(fuel),numVehicles=as.double(length(fuel)),numberOfPassengers=as.double(sum(num_passengers)),pmt=sum(pmt),vmt=sum(length)/1609,mpg=sum(length)/1609/sum(fuel/3.78)),by=c('the.factor','vehicle_type','tripmode')]
  toplot <- toplot[vehicle_type!='Human' & tripmode!="walk"]
  if(nrow(en)>30){
    toplot <- join.on(toplot,en[vehicleTypeId%in%c('SUBWAY-DEFAULT','BUS-DEFAULT','CABLE_CAR-DEFAULT','Car','FERRY-DEFAULT','TRAM-DEFAULT','RAIL-DEFAULT') | substr(vehicleTypeId,0,3)=='BEV'],'vehicle_type','vehicleTypeId','primaryFuelType')
  }else{
    toplot <- join.on(toplot,en,'vehicle_type','vehicleTypeId','primaryFuelType')
  }
  toplot[,energy:=fuel] # fuel is now in units of J so no need to convert from L to J
  toplot[tripmode%in%c('car','ride_hail'),energy:=energy*factor.to.scale.personal.back]
  toplot[tripmode%in%c('car','ride_hail'),numVehicles:=numVehicles*factor.to.scale.personal.back]
  toplot[tripmode%in%c('car','ride_hail'),pmt:=pmt*factor.to.scale.personal.back]
  toplot[tripmode%in%c('car','ride_hail'),numberOfPassengers:=numVehicles]
  toplot[,ag.mode:=tripmode]
  toplot[tolower(ag.mode)%in%c('bart','bus','cable_car','muni','rail','tram','transit'),ag.mode:='Transit']
  toplot[ag.mode=='car',ag.mode:='Car']
  toplot[ag.mode=='ride_hail',ag.mode:='Ride Hail']
  toplot.ag <- toplot[,.(energy=sum(energy),pmt=sum(pmt)),by=c('the.factor','ag.mode')]
  pdf.scale <- .6
  setkey(toplot.ag,the.factor,ag.mode)
  p <- ggplot(toplot.ag,aes(x=the.factor,y=energy/1e12,fill=ag.mode))+geom_bar(stat='identity',position='stack')+labs(x="Scenario",y="Energy Consumption (TJ)",title=to.title(fact),fill="Trip Mode")+
      scale_fill_manual(values=as.character(mode.colors$color.hex[match(sort(u(toplot.ag$ag.mode)),mode.colors$key)]))
  ggsave(pp(plots.dir,'energy-by-mode-',fact,'.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')
  write.csv(toplot.ag,file=pp(plots.dir,'energy-by-mode-',fact,'.csv'))

  per.pmt <- toplot[,.(energy=sum(energy)/sum(pmt)),by=c('the.factor','tripmode')]
  per.pmt[,tripmode:=pretty.modes(tripmode)]
  the.first <- per.pmt[the.factor==per.pmt$the.factor[1]]
  per.pmt[,tripmode:=factor(tripmode,levels=the.first$tripmode[rev(order(the.first$energy))])]
  p <- ggplot(per.pmt[energy<Inf],aes(x=the.factor,y=energy,fill=tripmode))+geom_bar(stat='identity',position='dodge')+labs(x="Scenario",y="Energy Consumption (MJ/passenger mile)",title=to.title(fact),fill="Trip Mode")+
      scale_fill_manual(values=as.character(mode.colors$color.hex[match(levels(per.pmt$tripmode),mode.colors$key)]))
  ggsave(pp(plots.dir,'energy-per-pmt-by-vehicle-type-',fact,'.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

  # Deadheading 
  toplot <- pt[tripmode=='ride_hail',.(dead=num_passengers==0,miles=length/1609,hr,the.factor)]
  setkey(toplot,hr,dead)
  dead.frac <- toplot[,.(dead.frac=pp(roundC(100*sum(miles[dead==T])/sum(miles),1),"% Empty")),by=c('the.factor')]
  toplot <- toplot[,.(miles=sum(miles)),by=c('dead','hr','the.factor')]
  p <- ggplot(toplot,aes(x=hr,y=miles,fill=dead))+geom_bar(stat='identity')+labs(x="Hour",y="Vehicle Miles Traveled",fill="Empty",title=pp("Ride Hail Deadheading"))+geom_text(data=dead.frac,hjust=1,aes(x=24,y=max(toplot$miles),label=dead.frac,fill=NA))+facet_wrap(~the.factor)
  pdf.scale <- .6
  ggsave(pp(plots.dir,'dead-heading.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')
}
rm('pt')

#########################
# Network Plots
#########################
if(plot.congestion){
  for(fact in factors){
    streval(pp('link[,the.factor:=',fact,']'))
    link[,relative.sp:=(LENGTH/FREESPEED)/traveltime]
    congestion.levels <- c(1,0.5,0.1,0.01)
    congestion <- link[,.(level=congestion.levels,frac.below=sapply(congestion.levels,function(lev){ sum(relative.sp<lev)/length(relative.sp) })),by=c('hour','the.factor')]
    p <- ggplot(congestion,aes(x=hour,y=frac.below,colour=factor(level)))+geom_line()+facet_wrap(~the.factor)+labs(x="Hour",y="Fraction of Links Below Each Relative Speed",colour="Relative Speed",title=pp("Fraction of Links at Varying Levels of Congestion by ",fact))
    pdf.scale <- .6
    ggsave(pp(plots.dir,'congestion.pdf'),p,width=12*pdf.scale,height=8*pdf.scale,units='in')
  }
}

if(plot.modality.styles){
  if('customAttributes' %in% names(pop)){
    for(fact in factors){
      streval(pp('pop[,the.factor:=',fact,']'))
      if(all(c('low','base','high') %in% u(pop$the.factor))){
        pop[,the.factor:=factor(the.factor,levels=c('low','base','high'))]
      }else if(all(c('Low','Base','High') %in% u(pop$the.factor))){
        pop[,the.factor:=factor(the.factor,levels=c('Low','Base','High'))]
      }else{
        streval(pp('pop[,the.factor:=factor(the.factor,levels=exp$',fact,')]'))
      }
      pop[,style:=customAttributes]
      pop <- pop[style!='']
      if(any(u(pop$style)=='class1')){
        new.names <- c(class1='Multimodals',class2='Empty nesters',class3='Transit takers',class4='Inveterate drivers',class5='Moms in cars',class6='Car commuters')
        pop[,style:=new.names[as.character(style)]]
      }
      toplot <- pop[,.(n=length(type)),by=c('style','the.factor','iter')]
      toplot <- toplot[,.(n=mean(n)),by=c('style','the.factor')]
      setkey(toplot,style,the.factor)

      pdf.scale <- .8
      p<-ggplot(toplot,aes(x=the.factor,y=n,fill=style))+geom_bar(stat='identity',position='stack')+labs(x='Trip #',y='# Agents',title='Modality Style Distributions')
      ggsave(pp(plots.dir,'modality-styles.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

      p<-ggplot(toplot,aes(x=style,y=n,fill=the.factor))+geom_bar(stat='identity',position='dodge')+labs(x='',y='# Agents',fill=fact,title='Modality Style Distributions')
      ggsave(pp(plots.dir,'modality-styles-dodged.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')
    }
  }
}

