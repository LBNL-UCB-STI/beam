#########################################################################################################################################
# BEAM Run to Metrics
#########################################################################################################################################
load.libraries(c('optparse'),quietly=T)
load.libraries(c('maptools','sp'))

# Useful for managing large objects
list_obj_sizes <- function(list_obj=ls(envir=.GlobalEnv)){ 
  sizes <- sapply(list_obj, function(n) object.size(get(n)), simplify = FALSE) 
  print(sapply(sizes[order(-as.numeric(sizes))], function(s) format(s, unit = 'auto'))) 
}

######################################################################################################
# Load the exp config
exp.file <- '~/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/scenarios/experiment.csv'
exp <- data.table(read.csv(exp.file))
exp[,key:=pp(experiment,'_',factor)]

outs.dir.base <- '/Users/critter/Documents/beam/beam-output/experiments/'
#outs.exps <- c('ridehail_num','ridehail_price','toll_price','transit_capacity','transit_price','vot_vot')
outs.exps <- c('toll_price')

outs.exp <- outs.exps[1]
for(outs.exp in outs.exps){
  outs.dir <- pp(outs.dir.base,outs.exp)
  if(!file.exists(pp(outs.dir,"/combined-events.Rdata"))){
    evs <- list()
    out.dir <-  list.dirs(outs.dir,recursive=F)[1]
    for(out.dir in list.dirs(outs.dir,recursive=F)){
      file.path <- pp(out.dir,'/ITERS/it.0/0.events.csv.gz')
      path <- str_split(file.path,"/")[[1]]
      if(length(path)>1){
        the.file <- tail(path,1)
        the.dir <- pp(pp(head(path,-1),collapse='/'),'/')
      }else{
        the.file <- path
        the.dir <- './'
      }
      the.file.rdata <- pp(head(str_split(the.file,'csv')[[1]],-1),'Rdata')
      if(file.exists(the.file.rdata)){
        load(the.file.rdata)
      }else{
        if(!file.exists(file.path) & file.exists(pp(file.path,'.gz'))){
          ev <- data.table(read.csv(gzfile(pp(file.path,'.gz'))))
        }else if(file.exists(pp(the.dir,the.file))){
          ev <- data.table(read.csv(file.path))
        }
        save(ev,file=pp(the.dir,the.file.rdata))
      }

      exp.to.add <- exp[which(sapply(as.character(exp$name),function(str){grepl(str,file.path)}))[1]]
      if(nrow(exp.to.add)==0 & grepl("base",file.path)){
        # just pick one, must be treated as special case below
        exp.to.add <- exp[which(exp$level=='base')]
      }
      ev[,links:=NULL]
      evs[[length(evs)+1]] <- cbind(ev,exp.to.add)
    }
    ev <- rbindlist(evs)
    rm('evs')
    #export to csv for colleagues
    #write.csv(ev,file=pp(outs.dir,"/combined-events.csv"))

    ###########################
    # Clean and relabel
    ###########################
    ev[vehicle_type=="bus",vehicle_type:="Bus"]
    ev[vehicle_type=="CAR" | substr(vehicle_id,1,5)=="rideH",vehicle_type:="TNC"]
    ev[vehicle_type=="subway",vehicle_type:="BART"]
    ev[vehicle_type=="SUV",vehicle_type:="Car"]
    ev[vehicle_type=="cable_car",vehicle_type:="Cable_Car"]
    ev[vehicle_type=="tram",vehicle_type:="Muni"]
    ev[vehicle_type=="rail",vehicle_type:="Rail"]
    ev[vehicle_type=="ferry",vehicle_type:="Ferry"]
    ev[,tripmode:=ifelse(mode%in%c('subway','bus','rail'),'transit',as.character(mode))]
    ev[,hour:=time/3600]
    ev[,hr:=round(hour)]
    setkey(ev,vehicle_type)

    if(outs.exp=='base'){
      base <- ev
      base[,':='(experiment=NULL,factor=NULL,level=NULL,value=NULL,comments=NULL,name=NULL)]
      save(base,file=pp(outs.dir,"/combined-events.Rdata"))
    }else{
      save(ev,file=pp(outs.dir,"/combined-events.Rdata"))
    }
  }
}

pretty.titles <- c('TNC Number'='ridehail_num',
                   'TNC Price'='ridehail_price',
                   'Transit Capacity'='transit_capacity',
                   'Transit Price'='transit_price',
                   'Toll Price'='toll_price',
                   'Value of Time'='vot_vot')
to.title <- function(outs.exp){ names(pretty.titles[which(pretty.titles==outs.exp)]) }
#################################################################################
# Decide which to analyze
# ridehail_num ridehail_price toll_price transit_capacity transit_price vot_vot
#################################################################################
outs.exps <- c('ridehail_num','ridehail_price','toll_price','transit_capacity','transit_price','vot_vot')
outs.exp <- 'transit_capacity'
for(outs.exp in outs.exps){
  my.cat(outs.exp)
  outs.dir <- pp(outs.dir.base,outs.exp,"/")
  load(pp(outs.dir,"combined-events.Rdata"))

  ###########################
  # Combine base with the rest 
  ###########################
  load(pp(outs.dir.base,"/base/combined-events.Rdata"))
  ev <- rbindlist(list(ev,cbind(base,exp[key==outs.exp & level=='base'])),fill=T)
  rm('base')
  setkey(ev,type)
  ev[,level:=factor(level,levels=c('low','base','high'))]
  # Correct for past mistakes
  ev[vehicle_type=='Caltrain',vehicle_type:='Rail']
  ev[vehicle_type=='Cable_Car',vehicle_type:='Muni']

  ###########################
  # Default Plots 
  ###########################

  # VMT by time and mode
  #ggplot(ev[J('PathTraversal')][!vehicle_type%in%c('Ferry','Caltrain'),.(vmt=sum(length/1609)),by=c('hr','level','vehicle_type')],aes(x=hr,y=vmt))+geom_bar(stat='identity')+facet_grid(level~vehicle_type)+labs(x="Hour",y="Vehicle Miles Traveled")
  p <- ggplot(ev[J('PathTraversal')][!vehicle_type%in%c('Ferry','Caltrain'),.(vmt=sum(length/1609)),by=c('hr','level','vehicle_type')],aes(x=hr,y=vmt,fill=vehicle_type))+geom_bar(stat='identity',position='stack')+facet_wrap(~level)+labs(x="Hour",y="Vehicle Miles Traveled",fill="Vehicle Type",title=to.title(outs.exp))
  pdf.scale <- .6
  ggsave(pp(outs.dir,'vmt-by-hour.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

  # Transit use
  #ggplot(ev[tripmode=='transit',],aes(x=length,y= num_passengers))+geom_point()
  #ggplot(toplot[,],aes(x=num_passengers/capacity))+geom_histogram()+facet_wrap(~level)
  toplot<-ev[J('PathTraversal')][tripmode=='transit']
  p <- ggplot(toplot[,.(cap.factor=mean(num_passengers/capacity,na.rm=T),frac.full=ifelse(all(capacity==0),as.numeric(NA),sum(num_passengers==capacity)/length(capacity))),by=c('hr','vehicle_type','level')],aes(x=hr,y=cap.factor,fill=vehicle_type))+geom_bar(stat='identity',position='dodge')+geom_line(aes(y=frac.full))+facet_grid(vehicle_type~level)+labs(x="Hour",y="Capacity Factor (bars) and Fraction of Trips at Full (line)",title=to.title(outs.exp),fill="Transit Type")
  pdf.scale <- .8
  ggsave(pp(outs.dir,'transit-use.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')
  # Passenger mile once "length" is correct
  #p <- ggplot(toplot[,.(pass.mile=sum(num_passengers*length,na.rm=T)),by=c('hr','vehicle_type','level')],aes(x=hr,y=pass.mile,fill=vehicle_type))+geom_bar(stat='identity',position='dodge')+facet_grid(vehicle_type~level)+labs(x="Hour",y="Passenger Miles Traveled",title=to.title(outs.exp),fill="Transit Type")
  #pdf.scale <- .8
  #ggsave(pp(outs.dir,'transit-passenger-miles.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

  # Mode splits 
  toplot <- ev[J('ModeChoice')][,.(tot=length(time)),by=c('level')]
  toplot <- join.on(ev[J('ModeChoice')][,.(num=length(time)),by=c('level','tripmode')],toplot,'level','level')
  toplot[,frac:=num/tot]
  setkey(toplot,level,tripmode)
  p <- ggplot(toplot,aes(x=level,y=frac*100,fill=tripmode))+geom_bar(stat='identity',position='stack')+labs(x="Scenario",y="% of Trips",title=to.title(outs.exp),fill="Trip Mode")
  pdf.scale <- .6
  ggsave(pp(outs.dir,'mode-split.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

  toplot <- ev[J('ModeChoice')][,.(length(time)),by=c('hr','level','tripmode')]
  setkey(toplot,hr,level,tripmode)
  p <- ggplot(toplot,aes(x=hr,y=V1,fill=tripmode))+geom_bar(stat='identity',position='stack')+facet_wrap(~level)+labs(x="Hour",y="# Trips",title=to.title(outs.exp),fill="Trip Mode")
  pdf.scale <- .8
  ggsave(pp(outs.dir,'mode-split-by-hour.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

  # Deadheading
  toplot <- ev[J('PathTraversal')][vehicle_type=='TNC',.(dead=num_passengers==0,miles=length/1609,hr,level)]
  setkey(toplot,hr,dead)
  dead.frac <- toplot[,.(dead.frac=pp(roundC(100*sum(miles[dead==T])/sum(miles[dead==F]),0),"% Deadhead")),by=c('level')]
  toplot <- toplot[,.(miles=sum(miles)),by=c('dead','hr','level')]
  p <- ggplot(toplot,aes(x=hr,y=miles,fill=dead))+geom_bar(stat='identity')+labs(x="Hour",y="VMT",fill="Empty",title=pp("TNC Price"))+geom_text(data=dead.frac,aes(x=20,y=max(toplot$miles),label=dead.frac,fill=NA))+facet_wrap(~level)
  pdf.scale <- .6
  ggsave(pp(outs.dir,'dead-heading.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

}

###########################
# Exploratory Plots 
###########################
# From / to arrows
ggplot(ev[type=='PathTraversal'],aes(x=start.x,y=start.y,xend=end.x,yend=end.y,colour=vehicle_type))+geom_curve(arrow= arrow(length = unit(0.03, "npc")),curvature=0.1)
# BART tracks
ggplot(ev[J('PathTraversal')][vehicle_type=='Bus' & substr(vehicle_id,1,2)=='BA'][1:2000],aes(x=start.x,y=start.y,xend=end.x,yend=end.y,colour=vehicle_id))+geom_curve(arrow= arrow(length = unit(0.01, "npc")),curvature=0.1)

# Beam leg by time and mode
ggplot(ev[J('PathTraversal')],aes(x=time/3600))+geom_histogram()+facet_wrap(name~vehicle_type)+labs(x="Hour",y="# Vehicle Movements")
setkey(ev,level,vehicle_type)
ggplot(ev[J('PathTraversal')][,.(num=length(num_passengers)),by=c('hr','vehicle_type','level')],aes(x=hr,y=num))+geom_bar(stat='identity')+facet_grid(level~vehicle_type)+labs(x="Hour",y="Person Movements")

###########################
# Tables
###########################
ev[,.(fuel=sum(fuel,na.rm=T)),by='vehicle_type']


###########################
# Energy Consumption
###########################
 <- c('Alameda','Contra Costa','Marin','Napa','San Francisco','San Mateo','Santa Clara','Sonoma','Solano')
counties <- readShapePoly('~/Dropbox/ucb/vto/beam-core/spatial-data/ca-counties/ca-counties.shp',proj4string=CRS("+proj=longlat +datum=WGS84"))
sf.county.inds <- counties$NAME %in% the.counties
sf.counties <- spTransform(counties[sf.county.inds,],CRS("+proj=longlat +datum=WGS84"))
sf.county.pts <- data.table(fortify(sf.counties,region="OBJECTID"))
sf.county.pts[,id:=as.numeric(id)]
sf.county.pts <- join.on(sf.county.pts,data.table(sf.counties@data),'id','OBJECTID')

do.or.load('/Users/critter/Documents/beam/beam-output/experiments/energy/pathTraversalSpatialTemporalAnalysisTable.Rdata',function(){
  en <- data.table(read.table('/Users/critter/Documents/beam/beam-output/experiments/energy/pathTraversalSpatialTemporalAnalysisTable_base_2017-09-27_05-05-07_withTransitIntermediateLinks_hourly_2.txt',header=T))
  en[,hour:=timeBin]
  en[,energy:=fuelConsumption.MJ.]
  en[,fuelConsumption.MJ.:=NULL]
  en <- en[county%in%the.counties]
  # Scale energy, vehicles, and passengers back up
  en[mode%in%c('Car','TNC','Human'),energy:=energy*20]
  en[mode%in%c('Car','TNC','Human'),numberOfVehicles:=numberOfVehicles*20]
  en[mode%in%c('BART','Bus','Cable_Car','Muni','Rail','TNC'),numberOfPassengers:=numberOfPassengers*20]
  # For Car/Human we want to count the driver, so we add # vehicle to # passengers
  en[,numTravelers:=ifelse(mode%in%c("Car","Human"),numberOfVehicles+numberOfPassengers,numberOfPassengers)]

  # Emissions factors from LCFS
  # walk intensity from: http://web.mit.edu/2.813/www/readings/DrivingVsWalking.pdf
  # 230g/1.5mi / 0.386MJ/mi == 397 g/MJ
  intensity <- data.table(fuelType=c('diesel','electricity','food','gasoline','naturalGas'),g.per.mj=c(102.010,105.160,397,98.470,80.19))
  
  en <- join.on(en,intensity,'fuelType','fuelType')
  en[,ghg.kton:=energy*g.per.mj/1e9]
  list(en=en)
})
#en <- join.on(en,links,'linkId','linkId')

setkey(en,mode)
p <- ggplot(en[,.(energy=sum(energy)),by=c('hour','mode','county')],aes(x=hour,y=energy/1e6,fill=mode))+geom_bar(stat='identity')+facet_wrap(~county)+labs(x='Hour',y='Energy Consumed (PJ)',fill='Mode')
pdf.scale <- .6
ggsave(pp(outs.dir.base,'energy/energy-by-hour.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')
p <- ggplot(en[,.(ghg=sum(ghg.kton)),by=c('hour','mode','county')],aes(x=hour,y=ghg,fill=mode))+geom_bar(stat='identity')+facet_wrap(~county)+labs(x='Hour',y='Greenhouse Gas Emissions (kton)',fill='Mode')
pdf.scale <- .6
ggsave(pp(outs.dir.base,'energy/emissions-by-hour.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')
emiss <- en[,.(ghg=sum(ghg.kton)),by=c('mode','fuelType')]
emiss.agg <- en[,.(ghg=sum(ghg.kton)),by=c('mode')]
emiss[,mode:=factor(mode,levels=emiss.agg$mode[rev(order(emiss.agg$ghg))])]
p <- ggplot(emiss,aes(x=mode,y=ghg,fill=fuelType))+geom_bar(stat='identity')+labs(x='Mode',y='Greenhouse Gas Emissions (kton)',fill='Fuel Type')
pdf.scale <- .6
ggsave(pp(outs.dir.base,'energy/emissions-by-mode.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

# Transit Passenger Miles
en[mode=='Cable_Car',mode:='Muni']
passmile <- en[!mode%in%c('Human','Car','TNC'),.(pass.mile=sum(numberOfPassengers*lengthInMeters)/1609),by=c('hour','mode')]
setkey(passmile,hour,mode)
p <- ggplot(passmile,aes(x=hour,y=pass.mile/1e6,fill=mode))+geom_bar(stat='identity')+labs(x='Hour',y='Passenger Miles (million)',fill='Transit Mode')
pdf.scale <- .6
ggsave(pp(outs.dir.base,'energy/transit-passenger-miles.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')
# reality check, CA emits 116 million tonnes per year in light duty transportation
# 116e6 / 1.1 / 365 * (7/39) # 1.1 ton per tonne, 7M bay area peeps / 39M CA peeps
# = 51,000 tons per day
# vs. 37,000 tons per day from our baseline scenario

setkey(en,mode)
passmile <- en[,.(energy=sum(energy)/sum(numTravelers*lengthInMeters/1609),ghg=sum(ghg.kton)/sum(numTravelers*lengthInMeters/1609)),by=c('mode','fuelType')]
passmile <- rbindlist(list(passmile,en[J('Bus')][hour==7,.(mode="Bus @ Rush",energy=sum(energy)/sum(numTravelers*lengthInMeters/1609),ghg=sum(ghg.kton)/sum(numTravelers*lengthInMeters/1609)),by=c('fuelType')]),use.names=T)
passmile.agg <- passmile[,.(energy=sum(energy),ghg=sum(ghg)),by='mode']
passmile[,mode:=factor(mode,passmile.agg$mode[rev(order(passmile.agg$energy))])]
p <- ggplot(passmile,aes(x=mode,y=energy,fill=fuelType))+geom_bar(stat='identity')+labs(x='Mode',y='Energy (MJ per Passenger-Mile)',fill='Fuel Type')
pdf.scale <- .6
ggsave(pp(outs.dir.base,'energy/energy-per-passenger-mile.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')
passmile[,mode:=factor(mode,passmile.agg$mode[rev(order(passmile.agg$ghg))])]
en.tot.agg <- en[,.(energy=sum(energy)),by=c('mode')]
en.tot <- en[,.(energy=sum(energy)),by=c('mode','fuelType')]
en.tot[,mode:=factor(mode,en.tot.agg$mode[rev(order(en.tot.agg$energy))])]
p <- ggplot(en.tot,aes(x=mode,y=energy/1e6,fill=fuelType))+geom_bar(stat='identity')+labs(x='Mode',y='Energy (PJ)',fill='Fuel Type')
pdf.scale <- .6
ggsave(pp(outs.dir.base,'energy/energy-by-mode.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

centroids <- en[,.(x=mean(xCoord),y=mean(yCoord)),by='county']
en.by.hr <- join.on(en[,.(energy=sum(energy)),by=c('county','hour')],centroids,'county','county')
p <- ggplot(en.by.hr,aes(x=x,y=y,size=energy/1e6,fill=county))+geom_polygon(data=sf.county.pts,aes(x=long,y=lat,group=group,fill=NAME),size=1)+geom_point()+facet_wrap(~hour)+labs(x="Lon",y="Lat",fill="County",size="Energy(PJ)")
pdf.scale <- 1
ggsave(pp(outs.dir.base,'energy/energy-by-county-by-hour.pdf'),p,width=10*pdf.scale,height=10*pdf.scale,units='in')

# we filter to street modes b/c the BART and Rail tend to concentrate whole trips into a single point making spikey results that aren't realistic
# but they can be added once the correct geoms are being used for non-street transit
en.dots <- en
en.dots[,energyRounded:=sapply(en.dots$energy/500,function(x){ rpois(1,x)})]
en.dots[,i:=1:nrow(en.dots)]
en.dots <- en.dots[,.(lon=rep(xCoord,energyRounded),lat=rep(yCoord,energyRounded)),by='i']
write.csv(en.dots[,.(lon,lat)],file='/Users/critter/Documents/beam/beam-output/experiments/energy-deck-2.csv',row.names = F)

###########################
# Load links data
###########################
do.or.load(pp(outs.dir.base,'bayAreaR5NetworkLinks.Rdata'),function(){
  links <- data.table(read.table(pp(outs.dir.base,'bayAreaR5NetworkLinks.txt'),header=T))
  counties <- readShapePoly('~/Dropbox/ucb/vto/beam-core/spatial-data/ca-counties/ca-counties.shp',proj4string=CRS("+proj=longlat +datum=WGS84"))
  links[,county:=over(SpatialPoints(links[,list(x,y)],proj4string=CRS(proj4string(counties))),counties)$NAME]
  list(links=links)
})
