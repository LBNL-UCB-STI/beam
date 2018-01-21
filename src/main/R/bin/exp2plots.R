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
  args<-'/Users/critter/Documents/beam/beam-output/experiments/vot/'
  #args<-'/Users/critter/Documents/beam/beam-output/experiments/prices-25k/'
  args <- parse_args(OptionParser(option_list = option_list,usage = "exp2plots.R [experiment-directory]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "exp2plots.R [experiment-directory]"),positional_arguments=T)
}
######################################################################################################

factor.to.scale.personal.back <- 32

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
  events.csv <- pp(run.dir,'output/ITERS/it.0/0.events.csv')
  ev <- csv2rdata(events.csv)
  ev[,run:=grp]
  for(fact in factors){
    if(fact %in% names(ev))stop(pp('Factor name "',fact,'" also a column name in events.csv, please change factor name in experiments.csv'))
    the.level <- streval(pp('exp$',fact,'[run.i]'))
    streval(pp('ev[,',fact,':="',the.level,'"]'))
  }
  evs[[length(evs)+1]] <- ev[type%in%c('PathTraversal','ModeChoice')]
}
ev <- rbindlist(evs)

ev <- clean.and.relabel(ev,factor.to.scale.personal.back)

setkey(ev,type)

## Prep data needed to do quick version of energy calc
en <- data.table(read.csv('~/Dropbox/ucb/vto/beam-all/beam/test/input/sf-light/energy/energy-consumption.csv'))
setkey(en,vehicleType)
en <- u(en)
## Energy Density in MJ/liter or MJ/kWh
en.density <- data.table(fuelType=c('gasoline','diesel','electricity'),density=c(34.2,35.8,3.6))
ev[tripmode%in%c('car') & vehicle_type=='Car',':='(num_passengers=1)]
ev[,pmt:=num_passengers*length/1609]
ev[is.na(pmt),pmt:=0]
setkey(ev,type)

scale_fill_manual(values = colours)


# Mode splits 
for(fact in factors){
  streval(pp('ev[,the.factor:=',fact,']'))
  if(all(c('low','base','high') %in% u(ev$the.factor)))ev[,the.factor:=factor(the.factor,levels=c('low','base','high'))]
  if(all(c('Low','Base','High') %in% u(ev$the.factor)))ev[,the.factor:=factor(the.factor,levels=c('Low','Base','High'))]
  
  toplot <- ev[J('ModeChoice')][,.(tot=length(time)),by=the.factor]
  toplot <- join.on(ev[J('ModeChoice')][,.(num=length(time)),by=c('the.factor','tripmode')],toplot,'the.factor','the.factor')
  toplot[,frac:=num/tot]
  toplot[,tripmode:=pretty.modes(tripmode)]
  setkey(toplot,the.factor,tripmode)
  p <- ggplot(toplot,aes(x=the.factor,y=frac*100,fill=tripmode))+geom_bar(stat='identity',position='stack')+labs(x="Scenario",y="% of Trips",title=pp('Factor: ',fact),fill="Trip Mode")+
      scale_fill_manual(values=as.character(mode.colors$color.hex[match(sort(u(toplot$tripmode)),mode.colors$key)]))
  pdf.scale <- .6
  ggsave(pp(plots.dir,'mode-split-by-',fact,'.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

  target <- data.frame(tripmode=rep(c('Car','Walk','Transit','TNC'),length(u(toplot$the.factor))),
                       perc=rep(c(79,4,13,5),length(u(toplot$the.factor))),
                       the.factor=rep(u(toplot$the.factor),each=4))
  p <- ggplot(toplot,aes(x=tripmode,y=frac*100))+geom_bar(stat='identity')+facet_wrap(~the.factor)+geom_point(data=target,aes(y=perc),colour='red')
  ggsave(pp(plots.dir,'mode-split-lines-by-',fact,'.pdf'),p,width=15*pdf.scale,height=8*pdf.scale,units='in')
}


# Energy by Mode
for(fact in factors){
  streval(pp('ev[,the.factor:=',fact,']'))
  if(all(c('low','base','high') %in% u(ev$the.factor)))ev[,the.factor:=factor(the.factor,levels=c('low','base','high'))]
  if(all(c('Low','Base','High') %in% u(ev$the.factor)))ev[,the.factor:=factor(the.factor,levels=c('Low','Base','High'))]

  toplot <- ev[J('PathTraversal')][,.(fuel=sum(fuel),numVehicles=as.double(length(fuel)),numberOfPassengers=as.double(sum(num_passengers)),pmt=sum(pmt)),by=c('the.factor','vehicle_type','tripmode')]
  toplot <- toplot[vehicle_type!='Human' & tripmode!="walk"]
  toplot <- join.on(toplot,en,'vehicle_type','vehicleType','fuelType')
  toplot <- join.on(toplot,en.density,'fuelType','fuelType')
  toplot[,energy:=fuel*density]
  toplot[vehicle_type=='TNC',tripmode:='TNC']
  toplot[vehicle_type%in%c('Car','TNC'),energy:=energy*factor.to.scale.personal.back]
  toplot[vehicle_type%in%c('Car','TNC'),numVehicles:=numVehicles*factor.to.scale.personal.back]
  toplot[vehicle_type%in%c('Car','TNC'),pmt:=pmt*factor.to.scale.personal.back]
  toplot[vehicle_type%in%c('Car','TNC'),numberOfPassengers:=numVehicles]
  toplot[,ag.mode:=tripmode]
  toplot[tolower(ag.mode)%in%c('bart','bus','cable_car','muni','rail','tram','transit'),ag.mode:='Transit']
  toplot[ag.mode=='car',ag.mode:='Car']
  toplot.ag <- toplot[,.(energy=sum(energy),pmt=sum(pmt)),by=c('the.factor','ag.mode')]
  setkey(toplot.ag,the.factor,ag.mode)
  p <- ggplot(toplot.ag,aes(x=the.factor,y=energy/1e6,fill=ag.mode))+geom_bar(stat='identity',position='stack')+labs(x="Scenario",y="Energy Consumption (TJ)",title=to.title(fact),fill="Trip Mode")+
      scale_fill_manual(values=as.character(mode.colors$color.hex[match(sort(u(toplot.ag$ag.mode)),mode.colors$key)]))
  ggsave(pp(plots.dir,'energy-by-mode-',fact,'.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

  per.pmt <- toplot[,.(energy=sum(energy)/sum(pmt)),by=c('the.factor','tripmode')]
  per.pmt[,tripmode:=pretty.modes(tripmode)]
  the.first <- per.pmt[the.factor==per.pmt$the.factor[1]]
  per.pmt[,tripmode:=factor(tripmode,levels=the.first$tripmode[rev(order(the.first$energy))])]
  p <- ggplot(per.pmt[energy<Inf],aes(x=the.factor,y=energy,fill=tripmode))+geom_bar(stat='identity',position='dodge')+labs(x="Scenario",y="Energy Consumption (MJ/passenger mile)",title=to.title(fact),fill="Trip Mode")+
      scale_fill_manual(values=as.character(mode.colors$color.hex[match(levels(per.pmt$tripmode),mode.colors$key)]))
  ggsave(pp(plots.dir,'energy-per-pmt-by-vehicle-type-',fact,'.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')
}





##################################################################################
## Decide which to analyze
## ridehail_num ridehail_price toll_price transit_capacity transit_price vot_vot
##################################################################################
#outs.exps <- c('ridehail_num','ridehail_price','transit_capacity','transit_price','vot_vot')
#outs.exp <- 'transit_capacity'
#for(outs.exp in outs.exps){
  #my.cat(outs.exp)
  #outs.dir <- pp(outs.dir.base,outs.exp,"/")
  #load(pp(outs.dir,"combined-events.Rdata"))

  ############################
  ## Combine base with the rest 
  ############################
  #load(pp(outs.dir.base,"/base/combined-events.Rdata"))
  #ev <- rbindlist(list(ev,cbind(base,exp[key==outs.exp & level=='base'])),fill=T)
  #rm('base')
  #setkey(ev,type)
  #ev[,level:=factor(level,levels=c('low','base','high'))]
  ## Correct for past mistakes
  #ev[vehicle_type=='Caltrain',vehicle_type:='Rail']
  #ev[vehicle_type=='Cable_Car',vehicle_type:='Muni']

  ############################
  ## Default Plots 
  ############################

  ## VMT by time and mode
  ##ggplot(ev[J('PathTraversal')][!vehicle_type%in%c('Ferry','Caltrain'),.(vmt=sum(length/1609)),by=c('hr','level','vehicle_type')],aes(x=hr,y=vmt))+geom_bar(stat='identity')+facet_grid(level~vehicle_type)+labs(x="Hour",y="Vehicle Miles Traveled")
  #p <- ggplot(ev[J('PathTraversal')][!vehicle_type%in%c('Ferry','Caltrain'),.(vmt=sum(length/1609)),by=c('hr','level','vehicle_type')],aes(x=hr,y=vmt,fill=vehicle_type))+geom_bar(stat='identity',position='stack')+facet_wrap(~level)+labs(x="Hour",y="Vehicle Miles Traveled",fill="Vehicle Type",title=to.title(outs.exp))
  #pdf.scale <- .6
  #ggsave(pp(outs.dir,'vmt-by-hour.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

  ## Transit use
  ##ggplot(ev[tripmode=='transit',],aes(x=length,y= num_passengers))+geom_point()
  ##ggplot(toplot[,],aes(x=num_passengers/capacity))+geom_histogram()+facet_wrap(~level)
  #toplot<-ev[J('PathTraversal')][tripmode=='transit']
  #p <- ggplot(toplot[,.(cap.factor=mean(num_passengers/capacity,na.rm=T),frac.full=ifelse(all(capacity==0),as.numeric(NA),sum(num_passengers==capacity)/length(capacity))),by=c('hr','vehicle_type','level')],aes(x=hr,y=cap.factor,fill=vehicle_type))+geom_bar(stat='identity',position='dodge')+geom_line(aes(y=frac.full))+facet_grid(vehicle_type~level)+labs(x="Hour",y="Capacity Factor (bars) and Fraction of Trips at Full (line)",title=to.title(outs.exp),fill="Transit Type")
  #pdf.scale <- .8
  #ggsave(pp(outs.dir,'transit-use.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')
  ## Passenger mile once "length" is correct
  ##p <- ggplot(toplot[,.(pass.mile=sum(num_passengers*length,na.rm=T)),by=c('hr','vehicle_type','level')],aes(x=hr,y=pass.mile,fill=vehicle_type))+geom_bar(stat='identity',position='dodge')+facet_grid(vehicle_type~level)+labs(x="Hour",y="Passenger Miles Traveled",title=to.title(outs.exp),fill="Transit Type")
  ##pdf.scale <- .8
  ##ggsave(pp(outs.dir,'transit-passenger-miles.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')



  #toplot <- ev[J('ModeChoice')][,.(length(time)),by=c('hr','level','tripmode')]
  #setkey(toplot,hr,level,tripmode)
  #p <- ggplot(toplot,aes(x=hr,y=V1,fill=tripmode))+geom_bar(stat='identity',position='stack')+facet_wrap(~level)+labs(x="Hour",y="# Trips",title=to.title(outs.exp),fill="Trip Mode")
  #pdf.scale <- .8
  #ggsave(pp(outs.dir,'mode-split-by-hour.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

  ## Deadheading
  #toplot <- ev[J('PathTraversal')][vehicle_type=='TNC',.(dead=num_passengers==0,miles=length/1609,hr,level)]
  #setkey(toplot,hr,dead)
  #dead.frac <- toplot[,.(dead.frac=pp(roundC(100*sum(miles[dead==T])/sum(miles[dead==F]),0),"% Deadhead")),by=c('level')]
  #toplot <- toplot[,.(miles=sum(miles)),by=c('dead','hr','level')]
  #p <- ggplot(toplot,aes(x=hr,y=miles,fill=dead))+geom_bar(stat='identity')+labs(x="Hour",y="VMT",fill="Empty",title=pp("TNC Price"))+geom_text(data=dead.frac,aes(x=20,y=max(toplot$miles),label=dead.frac,fill=NA))+facet_wrap(~level)
  #pdf.scale <- .6
  #ggsave(pp(outs.dir,'dead-heading.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

#}

############################
## Exploratory Plots 
############################
## From / to arrows
##ggplot(ev[type=='PathTraversal'],aes(x=start.x,y=start.y,xend=end.x,yend=end.y,colour=vehicle_type))+geom_curve(arrow= arrow(length = unit(0.03, "npc")),curvature=0.1)
## BART tracks
##ggplot(ev[J('PathTraversal')][vehicle_type=='Bus' & substr(vehicle_id,1,2)=='BA'][1:2000],aes(x=start.x,y=start.y,xend=end.x,yend=end.y,colour=vehicle_id))+geom_curve(arrow= arrow(length = unit(0.01, "npc")),curvature=0.1)

## Beam leg by time and mode
#ggplot(ev[J('PathTraversal')],aes(x=time/3600))+geom_histogram()+facet_wrap(name~vehicle_type)+labs(x="Hour",y="# Vehicle Movements")
#setkey(ev,level,vehicle_type)
#ggplot(ev[J('PathTraversal')][,.(num=length(num_passengers)),by=c('hr','vehicle_type','level')],aes(x=hr,y=num))+geom_bar(stat='identity')+facet_grid(level~vehicle_type)+labs(x="Hour",y="Person Movements")

############################
## Tables
############################
#ev[,.(fuel=sum(fuel,na.rm=T)),by='vehicle_type']


############################
## Energy Consumption
############################
#the.counties <- c('Alameda','Contra Costa','Marin','Napa','San Francisco','San Mateo','Santa Clara','Sonoma','Solano')
#counties <- readShapePoly('~/Dropbox/ucb/vto/beam-core/spatial-data/ca-counties/ca-counties.shp',proj4string=CRS("+proj=longlat +datum=WGS84"))
#sf.county.inds <- counties$NAME %in% the.counties
#sf.counties <- spTransform(counties[sf.county.inds,],CRS("+proj=longlat +datum=WGS84"))
#sf.county.pts <- data.table(fortify(sf.counties,region="OBJECTID"))
#sf.county.pts[,id:=as.numeric(id)]
#sf.county.pts <- join.on(sf.county.pts,data.table(sf.counties@data),'id','OBJECTID')

#do.or.load('/Users/critter/Documents/beam/beam-output/experiments/energy/pathTraversalSpatialTemporalAnalysisTable.Rdata',function(){
  #en <- data.table(read.table('/Users/critter/Documents/beam/beam-output/experiments/energy/pathTraversalSpatialTemporalAnalysisTable_base_2017-10-26_10-21-06_hourly.txt',header=T))
  #en[,hour:=timeBin]
  #en[,energy:=fuelConsumption.MJ.]
  #en[,fuelConsumption.MJ.:=NULL]
  #en <- en[county%in%the.counties]
  ## Scale energy, vehicles, and passengers back up
  #en[mode%in%c('Car','TNC','Human'),energy:=energy*20]
  #en[mode%in%c('Car','TNC','Human'),numberOfVehicles:=numberOfVehicles*20]
  #en[mode%in%c('BART','Bus','Cable_Car','Muni','Rail','TNC'),numberOfPassengers:=numberOfPassengers*20]
  ## For Car/Human we want to count the driver, so we add # vehicle to # passengers
  #en[,numTravelers:=ifelse(mode%in%c("Car","Human"),numberOfVehicles+numberOfPassengers,numberOfPassengers)]

  ## Emissions factors from LCFS
  ## walk intensity from: http://web.mit.edu/2.813/www/readings/DrivingVsWalking.pdf
  ## 230g/1.5mi / 0.386MJ/mi == 397 g/MJ
  #intensity <- data.table(fuelType=c('diesel','electricity','food','gasoline','naturalGas'),g.per.mj=c(102.010,105.160,397,98.470,80.19))
  
  #en <- join.on(en,intensity,'fuelType','fuelType')
  #en[,ghg.kton:=energy*g.per.mj/1e9]
  #en <- en[!(mode=='Car' & fuelType=='food')] # don't know where this came from but messing with plots
  #list(en=en)
#})
##en <- join.on(en,links,'linkId','linkId')

#setkey(en,mode)
#p <- ggplot(en[,.(energy=sum(energy)),by=c('hour','mode','county')],aes(x=hour,y=energy/1e6,fill=mode))+geom_bar(stat='identity')+facet_wrap(~county)+labs(x='Hour',y='Energy Consumed (PJ)',fill='Mode')
#pdf.scale <- .6
#ggsave(pp(outs.dir.base,'energy/energy-by-hour.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

#p <- ggplot(en[,.(ghg=sum(ghg.kton)),by=c('hour','mode','county')],aes(x=hour,y=ghg,fill=mode))+geom_bar(stat='identity')+facet_wrap(~county)+labs(x='Hour',y='Greenhouse Gas Emissions (kton)',fill='Mode')
#pdf.scale <- .6
#ggsave(pp(outs.dir.base,'energy/emissions-by-hour.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

#emiss <- en[,.(ghg=sum(ghg.kton)),by=c('mode','fuelType')]
#emiss.agg <- en[,.(ghg=sum(ghg.kton)),by=c('mode')]
#emiss[,mode:=factor(mode,levels=emiss.agg$mode[rev(order(emiss.agg$ghg))])]
#p <- ggplot(emiss,aes(x=mode,y=ghg,fill=fuelType))+geom_bar(stat='identity')+labs(x='Mode',y='Greenhouse Gas Emissions (kton)',fill='Fuel Type')
#pdf.scale <- .6
#ggsave(pp(outs.dir.base,'energy/emissions-by-mode.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

## reality check, CA emits 116 million tonnes per year in light duty transportation
## 116e6 / 1.1 / 365 * (7/39) # 1.1 ton per tonne, 7M bay area peeps / 39M CA peeps
## = 51,000 tons per day
## vs. 37,000 tons per day from our baseline scenario

#setkey(en,mode)
#passmile <- en[fuelType!='food',.(energy=sum(energy)/sum(numTravelers*lengthInMeters/1609),ghg=sum(ghg.kton)/sum(numTravelers*lengthInMeters/1609)),by=c('mode','fuelType')]
##passmile <- rbindlist(list(passmile,en[J('Bus')][hour==7,.(mode="Bus @ Rush",energy=sum(energy)/sum(numTravelers*lengthInMeters/1609),ghg=sum(ghg.kton)/sum(numTravelers*lengthInMeters/1609)),by=c('fuelType')]),use.names=T)
#passmile.agg <- passmile[,.(energy=sum(energy),ghg=sum(ghg)),by='mode']
#passmile[,mode:=factor(mode,passmile.agg$mode[rev(order(passmile.agg$energy))])]
#p <- ggplot(passmile,aes(x=mode,y=energy,fill=fuelType))+geom_bar(stat='identity',position='dodge')+labs(x='Mode',y='Energy (MJ per Passenger-Mile)',fill='Fuel Type')
#pdf.scale <- .6
#ggsave(pp(outs.dir.base,'energy/energy-per-passenger-mile.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')
#p <- ggplot(passmile,aes(x=mode,y=ghg*1e6,fill=fuelType))+geom_bar(stat='identity',position='dodge')+labs(x='Mode',y='Emissions (kg per Passenger-Mile)',fill='Fuel Type')
#pdf.scale <- .6
#ggsave(pp(outs.dir.base,'energy/emissions-per-passenger-mile.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

#passmile[,mode:=factor(mode,passmile.agg$mode[rev(order(passmile.agg$ghg))])]
#en.tot.agg <- en[,.(energy=sum(energy)),by=c('mode')]
#en.tot <- en[,.(energy=sum(energy)),by=c('mode','fuelType')]
#en.tot[,mode:=factor(mode,en.tot.agg$mode[rev(order(en.tot.agg$energy))])]

#p <- ggplot(en.tot,aes(x=mode,y=energy/1e6,fill=fuelType))+geom_bar(stat='identity')+labs(x='Mode',y='Energy (PJ)',fill='Fuel Type')
#pdf.scale <- .6
#ggsave(pp(outs.dir.base,'energy/energy-by-mode.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

#en.tot[,energy.perc:=energy/sum(energy)*100]
#setkey(en.tot,mode)
#p <- ggplot(en.tot,aes(x="",y=energy.perc,fill=mode))+geom_bar(stat='identity')+coord_polar("y",start=0)+labs(y='Energy (% of Total)',fill='Mode')
#pdf.scale <- .6
#ggsave(pp(outs.dir.base,'energy/energy-by-mode-pie-chart.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

#centroids <- en[,.(x=mean(xCoord),y=mean(yCoord)),by='county']
#en.by.hr <- join.on(en[,.(energy=sum(energy)),by=c('county','hour')],centroids,'county','county')
#p <- ggplot(en.by.hr,aes(x=x,y=y,size=energy/1e6,fill=county))+geom_polygon(data=sf.county.pts,aes(x=long,y=lat,group=group,fill=NAME),size=1)+geom_point()+facet_wrap(~hour)+labs(x="Lon",y="Lat",fill="County",size="Energy(PJ)")
#pdf.scale <- 1
#ggsave(pp(outs.dir.base,'energy/energy-by-county-by-hour.pdf'),p,width=10*pdf.scale,height=10*pdf.scale,units='in')

  ## Transit Passenger Miles
  #passmile <- en[!mode%in%c('Human','Car','TNC'),.(pass.mile=sum(numberOfPassengers*lengthInMeters)/1609),by=c('hour','mode')]
  #setkey(passmile,hour,mode)
  #p <- ggplot(passmile,aes(x=hour,y=pass.mile/1e3,fill=mode))+geom_bar(stat='identity')+labs(x='Hour',y='Passenger Miles Traveled (thousand)',fill='Transit Mode')
  #pdf.scale <- .6
  #ggsave(pp(outs.dir.base,'energy/transit-passenger-miles.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

## we filter to street modes b/c the BART and Rail tend to concentrate whole trips into a single point making spikey results that aren't realistic
## but they can be added once the correct geoms are being used for non-street transit
#en.dots <- en
#en.dots[,energyRounded:=sapply(en.dots$energy/500,function(x){ rpois(1,x)})]
#en.dots[,i:=1:nrow(en.dots)]
#en.dots <- en.dots[,.(lon=rep(xCoord,energyRounded),lat=rep(yCoord,energyRounded)),by='i']
#write.csv(en.dots[,.(lon,lat)],file='/Users/critter/Documents/beam/beam-output/experiments/energy-deck-2.csv',row.names = F)

############################
## Load links data
############################
#do.or.load(pp(outs.dir.base,'bayAreaR5NetworkLinks.Rdata'),function(){
  #links <- data.table(read.table(pp(outs.dir.base,'bayAreaR5NetworkLinks.txt'),header=T))
  #counties <- readShapePoly('~/Dropbox/ucb/vto/beam-core/spatial-data/ca-counties/ca-counties.shp',proj4string=CRS("+proj=longlat +datum=WGS84"))
  #links[,county:=over(SpatialPoints(links[,list(x,y)],proj4string=CRS(proj4string(counties))),counties)$NAME]
  #list(links=links)
#})

## Just Base Scenario Analysis
