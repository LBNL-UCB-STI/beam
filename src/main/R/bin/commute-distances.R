#!/usr/local/bin/Rscript
##############################################################################################################################################
# Script to convert CSV files to an R data table and then save as Rdata. This is intended to be run from the project root directory.
##############################################################################################################################################
library(colinmisc)
setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')

##############################################################################################################################################
# LOAD LIBRARIES NEED BY THIS SCRIPT
load.libraries(c('sp','h3js','optparse','RCurl','stringr','aws.s3'),quietly=T)

##############################################################################################################################################
# COMMAND LINE OPTIONS 
option_list <- list()
if(interactive()){
  #setwd('~/downs/')
  args<-c('/Users/critter/Dropbox/ucb/vto/beam-colin/analysis/activity/plans.csv')
  args <- parse_args(OptionParser(option_list = option_list,usage = "commute-distance.R [files-to-convert]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "commute-distance.R [files-to-convert]"),positional_arguments=T)
}

local.dir <- '/Users/critter/Documents/beam/beam-output/commutes/'
make.dir(local.dir)

all.plans <- list()
all.skims <- list()
plans <- data.table(read.csv('/Users/critter/Dropbox/ucb/vto/beam-colin/analysis/activity/plans.csv',stringsAsFactors=F))
plans[grepl('html\\#',url),url.corrected:=unlist(lapply(str_split(plans[grepl('html\\#',url)]$url,'s3.us-east-2.amazonaws.com/beam-outputs/index.html#'),function(ll){ pp('https://beam-outputs.s3.amazonaws.com/',ll[2]) }))]
plans[,url.corrected:=url]
plans[,plan.localfile:=tempfile()]
plans[,skim.localfile:=tempfile()]
for(i in 1:nrow(plans)){
  print(plans[i,.(year,config)])
  local.subdir <-  pp(local.dir,head(tail(str_split(plans$url.corrected[i],"\\/")[[1]],ifelse(substrRight(plans$url.corrected[1],1)=="/",2,1)),1),"/")
  make.dir(local.subdir)
  plans[i,plan.localfile:=pp(local.subdir,"plans.csv.gz")]
  if(!file.exists(plans$plan.localfile[i])){
    tryCatch(download.file(pp(plans$url.corrected[i],'plans.csv.gz'),plans[i]$plan.localfile),error=function(e){})
  }
  if(file.exists(plans$plan.localfile[i])){
    df <- csv2rdata(plans[i]$plan.localfile)
    ds <- df[activityType%in%c('Home','Work'),.(x=c(activityLocationX[activityType=='Home'][1], activityLocationX[activityType=='Work'][1]),y=c(activityLocationY[activityType=='Home'][1], activityLocationY[activityType=='Work'][1])),by='personId'][,.(d=sqrt(diff(x)^2+diff(y)^2)/1609),by='personId']
    print(summary(ds$d))
    plans[i,min.dist:=min(ds$d)]
    plans[i,max.dist:=max(ds$d)]
    plans[i,mean.dist:=mean(ds$d)]
    plans[i,median.dist:=median(ds$d)]
    df[,year:=plans$year[i]]
    df[,config:=plans$config[i]]
    df[,frequency:=plans$frequency[i]]
    all.plans[[length(all.plans)+1]] <- df
  }
  it <- as.numeric(substr(plans$config[i],4,4))
  if(is.na(it))it <- 15
  plans[i,skim.localfile:=pp(local.subdir,"skims.csv.gz")]
  if(!file.exists(plans$skim.localfile[i])){
    for(it in 15:0){
      tryCatch(download.file(pp(plans$url.corrected[i],'ITERS/it.',it,'/',it,'.skims.csv.gz'),plans[i]$skim.localfile),error=function(e){})
      if(file.exists(plans[i]$skim.localfile))break
    }
  }
  if(file.exists(plans$skim.localfile[i])){
    df <- csv2rdata(plans$skim.localfile[i])
    plans[i,generalizedCost:=weighted.mean(df$generalizedCost,df$numObservations)]
    plans[i,generalizedTimeMinutes:=weighted.mean(df$generalizedTimeInS/60,df$numObservations)]
    plans[i,travelTimeMinutes:=weighted.mean(df$travelTimeInS/60,df$numObservations)]
    plans[i,milesTraveled:=weighted.mean(df$distanceInM/1609,df$numObservations)]
    df[,year:=plans$year[i]]
    df[,config:=plans$config[i]]
    df[,frequency:=plans$frequency[i]]
    all.skims[[length(all.skims)+1]] <- df
  }
  print(plans[i])
}
all.plans <- rbindlist(all.plans)
all.skims <- rbindlist(all.skims)
#write.csv(plans,'/Users/critter/Dropbox/ucb/vto/beam-colin/analysis/activity/plans-out.csv')
ggplot(melt(plans[!config%in%c('Base-Short-BAU','Base-Short-VTO','Base-Long-BAU','Base-Long-VTO'),.(year,config,frequency,mean.dist)],id.vars=c('year','config','frequency')),aes(x=year,y=value,colour=config,shape=config))+geom_line()+geom_point()+labs(x='Year',y='Mean Home--Work Distance (mi)',colour='Scenario',shape='Scenario')+scale_shape_manual(values=seq(0,15))
#ggplot(melt(plans[,-3,with=F],id.vars=c('year','config')),aes(x=year,y=value,colour=variable))+geom_line()+facet_wrap(~config)

# Skims
all.skims[,scen:=config]
all.skims[,scen:=pp(config,'-',year)]
toplot <- all.skims[hour%in%7:9 & mode%in%c('CAR','CAV','RIDE_HAIL','RIDE_HAIL_POOLED'),.(generalizedTimeInMinutes=weighted.mean(generalizedTimeInS,numObservations)/60,generalizedCost=weighted.mean(generalizedCost,numObservations),travelTimeInMinutes=weighted.mean(travelTimeInS,numObservations)/60,personMilesTraveled=weighted.mean(distanceInM,numObservations)/1609),by='scen']
#toplot <- all.skims[mode%in%c('CAR'),.(generalizedTimeInMinutes=weighted.mean(generalizedTimeInS,numObservations)/60,generalizedCost=weighted.mean(generalizedCost,numObservations),travelTimeInMinutes=weighted.mean(travelTimeInS,numObservations)/60,personMilesTraveled=weighted.mean(distanceInM,numObservations)/1609),by='scen']
ggplot(melt(toplot[!scen%in%pp('base',1:6),.(scen,generalizedTimeInMinutes,generalizedCost,travelTimeInMinutes,personMilesTraveled)],id.vars=c('scen')),aes(x=scen,y=value))+geom_bar(stat='identity')+labs(x='Scenario',y='Value',title='With Ride Hail')+facet_wrap(~variable,scales='free_y')+scale_shape_manual(values=seq(0,15))
ggplot(melt(toplot[scen%in%c('Base-2010','A-BAU-2025','A-VTO-2025','B-BAU-2040','B-VTO-2040','C-BAU-2040','C-VTO-2040'),.(scen,generalizedTimeInMinutes,generalizedCost,travelTimeInMinutes,personMilesTraveled)],id.vars=c('scen')),aes(x=scen,y=value))+geom_bar(stat='identity')+labs(x='Scenario',y='Value',title='')+facet_wrap(~variable,scales='free_y')+scale_shape_manual(values=seq(0,15))

# custom data from old plot consistent with UrbanSim result in the capstone report
toplot[,generalizedTimeInMinutesCustom:=c(rep(38,7),31,30.5,27.5,28,40,41)]
toplot[,scen.cap:=factor(c(pp('Base',0:6),'A2','A3','B5','B6','C5','C6'),levels=c(pp('Base',0:6),'A2','A3','B5','B6','C5','C6'))]
ggplot(toplot[!scen%in%pp('base',1:6)],aes(x=scen.cap,y=generalizedTimeInMinutesCustom))+geom_bar(stat='identity')+labs(x='Scenario',y='Generalized Trave Time (minutes)',title='')
toplot[,commute.dists:=c(rep(14.02,7),14.03,14.028,13.865,13.88,13.92,13.88)]
ggplot(toplot[!scen%in%pp('base',1:6)],aes(x=scen.cap,y=commute.dists))+geom_bar(stat='identity')+labs(x='Scenario',y='Avg. Euclidean Commute Distance (mi)',title='')

# Tabular analysis of skims
all.skims[year==2040 & mode%in%c('CAR','CAV'),.(cost=weighted.mean(cost, numObservations), generalizedCost =weighted.mean(generalizedCost, numObservations),medianGenCost=median(generalizedCost),numObs=sum(numObservations),genTT= weighted.mean(generalizedTimeInS/60, numObservations),tt= weighted.mean(travelTimeInS/60, numObservations)),by='config']

vot <- all.skims[(year==2040 & (grepl('B-',config) | grepl('C-',config)) | (year==2025 & grepl('A-',config)) | (year==2010 & config=='Base')),
          .(cost=weighted.mean(cost, numObservations), 
            numObs=sum(numObservations),
            genTT= weighted.mean(generalizedTimeInS/60, numObservations),
            tt= weighted.mean(travelTimeInS/60, numObservations),
            vottWeight= weighted.mean(generalizedTimeInS/60, numObservations)/weighted.mean(travelTimeInS/60, numObservations)),
by=c('mode','config')]
vot[,config:=factor(config,c('Base','A-BAU','A-VTO','B-BAU','B-VTO','C-BAU','C-VTO'))]
vot[,group:=factor(ifelse(config=='Base','Base',ifelse(grepl('A-',config),'A',ifelse(grepl('B-',config),'B','C'))),c('Base','A','B','C'))]
ggplot(vot,aes(x=config,y=vottWeight,fill=group))+geom_bar(stat='identity')+facet_wrap(~mode)+ geom_hline(data=vot[config=='Base'],aes(yintercept=vottWeight),linetype="dashed",color = "red")+labs(x='Scenario',y='Mean VOTT Weighting Factor',title='Value of Travel Time Weighting Factor')+theme_bw()+theme(axis.text.x = element_text(angle = 30, hjust = 1))

vot.ag <- all.skims[,
          .(cost=weighted.mean(cost, numObservations), 
            numObs=sum(numObservations),
            genTT= weighted.mean(generalizedTimeInS/60, numObservations),
            tt= weighted.mean(travelTimeInS/60, numObservations),
            vottWeight= weighted.mean(generalizedTimeInS/60, numObservations)/weighted.mean(travelTimeInS/60, numObservations)),
by=c('config')]




# BAUS using
# workplace location
#   general TT CAR, WT, negative roughly same magnitude
#   general Cost CAR always interacted HH income, coefficient is negative, -0.4, -0.3, -0.15 (most coefficients are -2 to 2) taking log of cost
# mode choice

all.plans <- all.plans[planElementType=='activity']
all.plans <- all.plans[planElementIndex<=2]
all.plans[,scen:=pp(frequency,'year-',config,'-',year)]
all.skims[,scen:=pp(frequency,'year-',config,'-',year)]
taz <- csv2rdata('/Users/critter/Dropbox/ucb/vto/beam-colin/analysis/activity/taz-centers.csv')
taz[,x:=coord.x]
taz[,y:=coord.y]
all.skims <- join.on(all.skims,taz,'origTaz','taz',c('x','y'),'o.')
all.skims <- join.on(all.skims,taz,'destTaz','taz',c('x','y'),'d.')
all.skims <- xy.dt.to.latlon(all.skims,c('o.x','o.y'))
all.skims <- xy.dt.to.latlon(all.skims,c('d.x','d.y'))
all.skims[,o.7:=h3_geo_to_h3(o.lat,o.lon,7)]
all.skims[,o.8:=h3_geo_to_h3(o.lat,o.lon,8)]
all.skims[,o.9:=h3_geo_to_h3(o.lat,o.lon,9)]
all.skims[,d.7:=h3_geo_to_h3(d.lat,d.lon,7)]
all.skims[,d.8:=h3_geo_to_h3(d.lat,d.lon,8)]
all.skims[,d.9:=h3_geo_to_h3(d.lat,d.lon,9)]
save(all.skims,file='/Users/critter/Dropbox/ucb/vto/beam-colin/analysis/activity/skims-plus.Rdata')
all.plans[,x:=activityLocationX]
all.plans[,y:=activityLocationY]
all.plans <- xy.dt.to.latlon(all.plans)
# h3_geo breaks with large data so need to do it by scen
all.plans[,row:=1:nrow(all.plans)]
hs <- all.plans[,.(row=row,h.7=h3_geo_to_h3(lat,lon,7)),by='scen']
all.plans <- join.on(all.plans,hs,'row','row')
save(all.plans,file='/Users/critter/Dropbox/ucb/vto/beam-colin/analysis/activity/plans-plus.Rdata')

all.skims[,min.per.mile:=generalizedTimeInS/distanceInM*1609/60]
all.skims[origTaz==destTaz,min.per.mile:=NA]
sk.density <- all.skims[,.(tt=weighted.mean(min.per.mile,numObservations,na.rm=T)),by=c('o.7','scen','config','year','frequency')]
toplot <- sk.density[config=='it.2' & frequency==15]
toplot <- join.on(toplot,toplot[year==2010],c('o.7'),c('o.7'),'tt','base.')
toplot[,tt.diff:=tt-base.tt]
hexes <- rbindlist(lapply(toplot$o.7,function(ll){ x <- data.table(h3_to_geo_boundary(ll)); x[,':='(id=ll,x=V2,y=V1,V1=NULL,V2=NULL)]; x}))
hexes <- rbindlist(lapply(u(toplot$scen),function(sc){ join.on(hexes,toplot[scen==sc],'id','o.7') }))
dev.new();ggplot(hexes[config=='it.2' & frequency==15 & year > 2010],aes(x=x,y=y,fill=tt.diff,group=id))+geom_polygon()+facet_wrap(~scen)+scale_fill_gradient2(high='red',low='blue',midpoint=0)
dev.new();ggplot(hexes[config=='it.2' & frequency==15 & year==2010],aes(x=x,y=y,fill=tt,group=id))+geom_polygon()+facet_wrap(~scen)+scale_fill_gradient2(high='red',low='blue',midpoint=median(hexes[config=='it.2' & frequency==15 & year==2010]$tt,na.rm=T))

# Min per mile Diffs from it.0 to it.2
for(the.year in u(sk.density[frequency==15]$year)){
  toplot <- sk.density[frequency==15 & year==the.year & config=='it.2']
  toplot <- join.on(toplot,sk.density[frequency==15 & year==the.year & config=='it.0'],'o.7','o.7','tt','freeflow.')
  toplot[,tt.diff:=tt-freeflow.tt]
  hexes <- rbindlist(lapply(toplot$o.7,function(ll){ x <- data.table(h3_to_geo_boundary(ll)); x[,':='(id=ll,x=V2,y=V1,V1=NULL,V2=NULL)]; x}))
  hexes <- rbindlist(lapply(u(toplot$scen),function(sc){ join.on(hexes,toplot[scen==sc],'id','o.7') }))
  dev.new();print(ggplot(hexes,aes(x=x,y=y,fill=tt.diff,group=id))+geom_polygon()+scale_fill_gradient2(limits=c(-5,5),high='red',low='blue',midpoint=0)+labs(title=pp('Year ',the.year,' Minutes-per-Mile TT Diff Between Congested and Freeflow Scenarios')))
}

sk.density <- all.skims[,.(tt=weighted.mean(generalizedTimeInS/60,numObservations)),by=c('o.7','scen','config','year','frequency')]
toplot <- sk.density[config=='it.2' & frequency==15]
toplot <- join.on(toplot,toplot[year==2010],c('o.7'),c('o.7'),'tt','base.')
toplot[,tt.diff:=tt-base.tt]
hexes <- rbindlist(lapply(toplot$o.7,function(ll){ x <- data.table(h3_to_geo_boundary(ll)); x[,':='(id=ll,x=V2,y=V1,V1=NULL,V2=NULL)]; x}))
hexes <- rbindlist(lapply(u(toplot$scen),function(sc){ join.on(hexes,toplot[scen==sc],'id','o.7') }))
ggplot(hexes[config=='it.2' & frequency==15 & year > 2010],aes(x=x,y=y,fill=tt.diff,group=id))+geom_polygon()+facet_wrap(~scen)+scale_fill_gradient2(high='red',low='blue',midpoint=0)
ggplot(hexes[config=='it.2' & frequency==15 & year==2010],aes(x=x,y=y,fill=tt,group=id))+geom_polygon()+facet_wrap(~scen)+scale_fill_gradient2(high='red',low='blue',midpoint=median(hexes[config=='it.2' & frequency==15 & year==2010]$tt,na.rm=T))

# Home-Work Distance attributed to person's home
ds.base <- all.plans[config=='it.2' & frequency==15,.(d=sqrt(diff(x)^2+diff(y)^2)/1609,h.7=h.7[activityType=='Home'],h.7.w=h.7[activityType=='Work']),by=c('scen','config','year','frequency','personId')]
ds <- ds.base[,.(d=mean(d,na.rm=T)),by=c('scen','config','year','frequency','h.7')]
ds <- join.on(ds,ds[year==2010],c('h.7'),c('h.7'),c('d'),'base.')
ds[,d.diff:=d-base.d]
hexes <- rbindlist(lapply(u(ds$h.7),function(ll){ x <- data.table(h3_to_geo_boundary(ll)); x[,':='(id=ll,x=V2,y=V1,V1=NULL,V2=NULL)]; x}))
hexes <- rbindlist(lapply(u(ds$scen),function(sc){ join.on(hexes,ds[scen==sc],'id','h.7') }))
ggplot(hexes[config=='it.2' & frequency==15 & year > 2010],aes(x=x,y=y,fill=d.diff,group=id))+geom_polygon()+facet_wrap(~scen)+scale_fill_gradient2(high='red',low='blue',midpoint=0)
ggplot(hexes[config=='it.2' & frequency==15 & year==2010],aes(x=x,y=y,fill=d,group=id))+geom_polygon()+facet_wrap(~scen)+scale_fill_gradient2(high='red',low='blue',midpoint=median(hexes[config=='it.2' & frequency==15 & year==2010]$d,na.rm=T))
ggplot(hexes[config=='it.2' & frequency==15 & year > 2010],aes(x=d.diff,group=id))+geom_histogram()+facet_wrap(~scen)

# Home-Work Distance attributed to person's work
ds <- ds.base[,.(d=mean(d,na.rm=T)),by=c('scen','config','year','frequency','h.7.w')]
ds <- join.on(ds,ds[year==2010],c('h.7.w'),c('h.7.w'),c('d'),'base.')
ds[,d.diff:=d-base.d]
hexes <- rbindlist(lapply(u(ds$h.7.w),function(ll){ x <- data.table(h3_to_geo_boundary(ll)); x[,':='(id=ll,x=V2,y=V1,V1=NULL,V2=NULL)]; x}))
hexes <- rbindlist(lapply(u(ds$scen),function(sc){ join.on(hexes,ds[scen==sc],'id','h.7.w') }))
ggplot(hexes[config=='it.2' & frequency==15 & year > 2010],aes(x=x,y=y,fill=d.diff,group=id))+geom_polygon()+facet_wrap(~scen)+scale_fill_gradient2(high='red',low='blue',midpoint=0)
ggplot(hexes[config=='it.2' & frequency==15 & year==2010],aes(x=x,y=y,fill=d,group=id))+geom_polygon()+facet_wrap(~scen)+scale_fill_gradient2(high='red',low='blue',midpoint=median(hexes[config=='it.2' & frequency==15 & year==2010]$d,na.rm=T))

# Home-Work Distance attributed to person's home diff by year
ds.base <- all.plans[frequency==15,.(d=sqrt(diff(x)^2+diff(y)^2)/1609,h.7=h.7[activityType=='Home'],h.7.w=h.7[activityType=='Work']),by=c('scen','config','year','frequency','personId')]
for(the.year in u(all.plans[frequency==15]$year)){
  ds <- ds.base[year==the.year,.(d=mean(d,na.rm=T),n=.N),by=c('scen','config','year','frequency','h.7')]
  ds <- join.on(ds[config=='it.2'],ds[config=='it.0'],'h.7','h.7',c('d','n'),'freeflow.')
  ds[,d.diff:=d-freeflow.d]
  hexes <- rbindlist(lapply(u(ds$h.7),function(ll){ x <- data.table(h3_to_geo_boundary(ll)); x[,':='(id=ll,x=V2,y=V1,V1=NULL,V2=NULL)]; x}))
  hexes <- rbindlist(lapply(u(ds$scen),function(sc){ join.on(hexes,ds[scen==sc],'id','h.7') }))
  dev.new();print(ggplot(hexes,aes(x=x,y=y,fill=d.diff,group=id))+geom_polygon()+facet_wrap(~scen)+scale_fill_gradient2(limits=c(-60,75),high='red',low='blue',midpoint=0)+labs(title=pp('Year ',the.year,' Home-Work Distance Diff Between Congested and Freeflow Scenarios')))
  dev.new();ggplot(hexes,aes(x=d.diff,group=id))+geom_histogram()+facet_wrap(~scen)
  dev.new();ggplot(hexes,aes(x=d.diff*n,group=id))+geom_histogram()+facet_wrap(~scen)
}





























