
load.libraries(c('sp','maptools','rgdal','GGally'))

best.only <- T
last.change <- function(x,minSSR){
  if(any(diff(minSSR)<0)){
    x[tail(which(diff(minSSR)<0),1)]
  }else{
    tail(x,1)
  }
}

to.download <- c()
to.download <- c('calibration_2017-07-07_06-01-50')
# 1k runs
calib.names <- c( 'calibration_2017-07-06_23-10-37','calibration_2017-07-07_00-22-22','calibration_2017-07-07_00-52-48','calibration_2017-07-07_07-26-14')
docs.base <- '/Users/critter/Documents'
# 10k runs
calib.names <- c( 'calibration_2017-07-06_23-23-58','calibration_2017-07-07_07-28-02')
docs.base <- '/Volumes/critter/Documents'

calib.name <- calib.names[1]
calib.name <- to.download[1]
load.all <- list()
cal.all <- list()
ev.all <- list()
for(calib.name in to.download){
  nersc.dir <- pp('/global/cscratch1/sd/csheppar/',calib.name)
  calib.dir <- pp(docs.base,'/beam/beam-output/',calib.name)
  iter.dir <- pp(calib.dir,'/ITERS')
  make.dir(calib.dir)
  make.dir(iter.dir)
  system(pp('scp csheppar@cori.nersc.gov:',nersc.dir,'/calibration.csv ',calib.dir,'/'))
  dt <- data.table(read.csv(pp(iter.dir,'/../calibration.csv')))
  iters <- dt$iter
  if(best.only){
    iters <- last.change(dt$iter,dt$minSSR)
  }
  for(the.iter in iters){
    system(pp('scp -r csheppar@cori.nersc.gov:',nersc.dir,'/ITERS/it.',the.iter,' ',iter.dir,'/'))
  }
  calib.names <- c(calib.names,calib.name)
}
for(calib.name in calib.names){
  iter.dir <- pp(docs.base,'/beam/beam-output/',calib.name,'/ITERS/')
  dt <- data.table(read.csv(pp(iter.dir,'../calibration.csv')))
  dt[,calib.run:=calib.name]
  cal.all[[length(cal.all)+1]] <- dt
  iters <- sort(unlist(lapply(strsplit(list.files(iter.dir),"\\."),function(ll){ as.numeric(ll[2])})))
  if(best.only){
    iters <- last.change(cal.all[[length(cal.all)]]$iter,cal.all[[length(cal.all)]]$minSSR)
  }
  for(the.iter in iters){
    load.file <- pp(iter.dir,'it.',the.iter,'/run0.',the.iter,'.disaggregateLoadProfile.csv')
    dt <- data.table(read.csv(load.file))
    dt[,iter:=the.iter]
    dt[,calib.run:=calib.name]
    load.all[[length(load.all)+1]] <- dt
    ev.file <- pp(iter.dir,'it.',the.iter,'/run0.',the.iter,'.events.csv')
    if(file.exists(ev.file)){
      dt <- data.table(read.csv(ev.file))
      dt[,iter:=the.iter]
      dt[,calib.run:=calib.name]
      ev.all[[length(ev.all)+1]] <- dt
    }
  }
}
cal.all <- rbindlist(cal.all)
load.all <- rbindlist(load.all)
ev.all <- rbindlist(ev.all)

ev.all[,list(chargeArrPub=sum(choice=='charge' & site>0,na.rm=T),chargeArrHome=sum(choice=='charge' & site<0,na.rm=T),chargeDepHome=sum(choice=='engageWithOriginalPlug'& site<0),chargeDepPub=sum(choice=='engageWithOriginalPlug'& site>0)),by=c('calib.run','iter')]

#ggplot(dt,aes(x=time,y=num.plugged.in,colour=site.type))+geom_bar(stat='identity',position='stack')+facet_wrap(charger.type~spatial.group)
#ggplot(load.all[time>=27 & time<=51,list(num.plugged.in=sum(num.plugged.in)),by=c('iter','time','site.type')],aes(x=time,y=num.plugged.in,fill=site.type))+geom_bar(stat='identity',position='stack')+facet_wrap(~iter)
#ggplot(load.all[iter==1374 & time>=27 & time<=51,list(kw=sum(charging.load.in.kw)),by=c('iter','time')],aes(x=time,y=kw,colour=factor(iter)))+geom_line()
#ggplot(load.all[iter==1374 & time>=27 & time<=51,list(num.plugged.in=sum(num.plugged.in)),by=c('iter','time')],aes(x=time,y=num.plugged.in))+geom_line()+facet_wrap(~iter)
#ggplot(load.all[iter==1374 & time>=27 & time<=51,list(num.plugged.in=sum(num.plugged.in)),by=c('iter','time')],aes(x=time,y=num.plugged.in,colour=factor(iter)))+geom_line()
#ggplot(load.all[iter==1374 & time>=27 & time<=51,list(kw=sum(charging.load.in.kw)),by=c('iter','time')],aes(x=time,y=kw,colour=factor(iter)))+geom_line()

cp <- data.table(read.csv('~/GoogleDriveUCB/beam-core/model-inputs/calibration-v2/cp-data-for-validation-10000.csv'))
cp[time>=3,time:=time+24]
cp[time<3,time:=time+48]

#ggplot(cp[,list(num.plugged.in=sum(num.plugged.in)),by=c('time')],aes(x=time,y=num.plugged.in))+geom_line()

both.all <- list()
for(the.calib.run in u(load.all$calib.run)){
  both <- join.on(cp,load.all[calib.run==the.calib.run & time>=27 & time<=51],c('time','spatial.group','site.type','charger.type'),c('time','spatial.group','site.type','charger.type'),c('num.plugged.in','charging.load.in.kw'),'pred.')
  both[is.na(pred.num.plugged.in),pred.num.plugged.in:=0]
  both[is.na(pred.charging.load.in.kw),pred.charging.load.in.kw:=0]
  both[,hr:=floor(time)]
  both[,calib.run:=the.calib.run]
  both[,SSR:=round(cal.all[calib.run==the.calib.run,last.change(SSR,minSSR)])]
  both.all[[length(both.all)+1]] <- both
}
both.all <- rbindlist(both.all)
both.all[,key:=pp(calib.run,' SSR=',SSR)]
both.all[,hour:=floor(time)]

ggplot(both.all,aes(x= num.plugged.in,y= pred.num.plugged.in,colour=spatial.group))+geom_point()+geom_abline(slope=1,intercept=0)+facet_wrap(~key)
dev.new()
ggplot(both.all,aes(x= charging.load.in.kw,y= pred.charging.load.in.kw,colour=spatial.group))+geom_point()+geom_abline(slope=1,intercept=0)+facet_wrap(~key)
dev.new()
ggplot(melt(cal.all,id.vars=c('iter','SSR','calib.run'),measure.vars=c('yesCharge','isHomeActivityAndHomeCharger','tryChargingLater','continueSearchInLargerArea','abort','departureYes','departureNo')),
       aes(x=variable,y=value,colour=log10(SSR),size=iter))+geom_point()+facet_wrap(~calib.run)

ggplot(both.all[,list(pred.num.plugged.in=sum(pred.num.plugged.in),num.plugged.in=sum(num.plugged.in),charging.load.in.kw=sum(charging.load.in.kw),pred.charging.load.in.kw=sum(pred.charging.load.in.kw)),by=c('spatial.group','key','hour')],aes(x= num.plugged.in,y= pred.num.plugged.in,colour=spatial.group))+geom_point()+geom_abline(slope=1,intercept=0)+facet_wrap(~key)
ggplot(both.all[,list(pred.num.plugged.in=sum(pred.num.plugged.in),num.plugged.in=sum(num.plugged.in),charging.load.in.kw=sum(charging.load.in.kw),pred.charging.load.in.kw=sum(pred.charging.load.in.kw)),by=c('spatial.group','key','hour')],aes(x= charging.load.in.kw,y= pred.charging.load.in.kw,colour=spatial.group))+geom_point()+geom_abline(slope=1,intercept=0)+facet_wrap(~key)



ggplot(both,aes(x= num.plugged.in,y= pred.num.plugged.in,colour=charger.type))+geom_point()+geom_abline(slope=1,intercept=0)

ggplot(both,aes(x= charging.load.in.kw,y= pred.charging.load.in.kw,colour=spatial.group))+geom_point()+geom_abline(slope=1,intercept=0)
ggplot(both,aes(x= charging.load.in.kw,y= pred.charging.load.in.kw,colour=charger.type))+geom_point()+geom_abline(slope=1,intercept=0)

ggplot(melt(both,id.vars=c('time','hr','spatial.group','site.type','charger.type'),measure.vars=c('num.plugged.in','pred.num.plugged.in'))[,list(value=sum(value)),by=c('hr','variable')],aes(x= hr, y=value/4,colour=variable))+geom_line()


cal.all.fin <- cal.all[,list(SSR=last.change(SSR,minSSR),yesCharge=last.change(yesCharge,minSSR),tryChargingLater=last.change(tryChargingLater,minSSR),continueSearchInLargerArea=last.change(continueSearchInLargerArea,minSSR),abort=last.change(abort,minSSR),departureYes=last.change(departureYes,minSSR),departureNo=last.change(departureNo,minSSR)),by='calib.run']

ggpairs(as.data.frame(cal.all.fin),columns=2:8, mapping=ggplot2::aes(colour=calib.run))

xy.to.latlon <- function(str){
  x <- as.numeric(strsplit(str,'"')[[1]][2])
  y <- as.numeric(strsplit(str,'"')[[1]][4])
  xy <- data.frame(x=x,y=y)
  xy <- SpatialPoints(xy,proj4string=CRS("+init=epsg:26910"))
  xy <- data.frame(coordinates(spTransform(xy,CRS("+init=epsg:4326"))))
  my.cat(pp(xy$y,',',xy$x))
}
