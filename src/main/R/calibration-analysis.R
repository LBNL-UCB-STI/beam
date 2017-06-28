
load.libraries(c('sp','maptools','rgdal'))

calib.name <- 'calibration_2017-06-27_21-06-15'
iter.dir <- pp('~/Documents/beam/beam-output/',calib.name,'/ITERS/')

iters <- sort(unlist(lapply(strsplit(list.files(iter.dir),"\\."),function(ll){ as.numeric(ll[2])})))

load.all <- list()
ev.all <- list()
for(the.iter in iters){
  load.file <- pp(iter.dir,'it.',the.iter,'/run0.',the.iter,'.disaggregateLoadProfile.csv')
  dt <- data.table(read.csv(load.file))
  dt[,iter:=the.iter]
  load.all[[length(load.all)+1]] <- dt
  ev.file <- pp(iter.dir,'it.',the.iter,'/run0.',the.iter,'.events.csv')
  if(file.exists(ev.file)){
    dt <- data.table(read.csv(ev.file))
    dt[,iter:=the.iter]
    ev.all[[length(ev.all)+1]] <- dt
  }
}
load.all <- rbindlist(load.all)
ev.all <- rbindlist(ev.all)

ev.all[,list(chargeArrPub=sum(choice=='charge' & site>0,na.rm=T),chargeArrHome=sum(choice=='charge' & site<0,na.rm=T),chargeDep=sum(choice=='engageWithOriginalPlug')),by='iter']

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

both <- join.on(cp,load.all[iter==0 & time>=27 & time<=51],c('time','spatial.group','site.type','charger.type'),c('time','spatial.group','site.type','charger.type'),c('num.plugged.in','charging.load.in.kw'),'pred.')
both[is.na(pred.num.plugged.in),pred.num.plugged.in:=0]
both[is.na(pred.charging.load.in.kw),pred.charging.load.in.kw:=0]
both[,hr:=floor(time)]

ggplot(both,aes(x= num.plugged.in,y= pred.num.plugged.in,colour=spatial.group))+geom_point()+geom_abline(slope=1,intercept=0)


ggplot(both,aes(x= num.plugged.in,y= pred.num.plugged.in,colour=charger.type))+geom_point()+geom_abline(slope=1,intercept=0)

ggplot(both,aes(x= charging.load.in.kw,y= pred.charging.load.in.kw,colour=spatial.group))+geom_point()+geom_abline(slope=1,intercept=0)
ggplot(both,aes(x= charging.load.in.kw,y= pred.charging.load.in.kw,colour=charger.type))+geom_point()+geom_abline(slope=1,intercept=0)

ggplot(melt(both,id.vars=c('time','hr','spatial.group','site.type','charger.type'),measure.vars=c('num.plugged.in','pred.num.plugged.in'))[,list(value=sum(value)),by=c('hr','variable')],aes(x= hr, y=value/4,colour=variable))+geom_line()



xy.to.latlon <- function(str){
  x <- as.numeric(strsplit(str,'"')[[1]][2])
  y <- as.numeric(strsplit(str,'"')[[1]][4])
  xy <- data.frame(x=x,y=y)
  xy <- SpatialPoints(xy,proj4string=CRS("+init=epsg:26910"))
  xy <- data.frame(coordinates(spTransform(xy,CRS("+init=epsg:4326"))))
  my.cat(pp(xy$y,',',xy$x))
}
