
load.libraries(c('maptools','sp'))

counties <- readShapePoly('~/Dropbox/ucb/vto/beam-developers/spatial-data/ca-counties/ca-counties.shp',proj4string=CRS("+proj=longlat +datum=WGS84"))
zips <- readShapePoly('~/Dropbox/ucb/vto/beam-developers/spatial-data/ca-zips/sf-bay-area-zips.shp',proj4string=CRS("+proj=longlat +datum=WGS84"))
sf.county.inds <- counties$NAME %in% c('Alameda','San Mateo','Sonoma','Marin','Contra Costa','Napa','Solano','Santa Clara','San Francisco')
zips.in.sf <- which(!is.na(over(zips,counties[sf.county.inds,])$NAME))
sf.zips <- spTransform(zips[zips.in.sf,],CRS("+proj=longlat +datum=WGS84"))
sf.counties <- spTransform(counties[sf.county.inds,],CRS("+proj=longlat +datum=WGS84"))
zip.to.county <- rbindlist(list(data.table(zip=sf.zips@data$ZCTA5CE10,county=over(sf.zips,sf.counties)$NAME),
                                data.table(zip=c(94143,94497,94614, 94562),
                                           county=c('San Francisco','San Mateo','Alameda','Napa'))))


dump.code <- 'backup-2017-01-24'

do.or.load(pp('~/Documents/beam/scraper/',dump.code,'/charging.Rdata'),function(){
  station <- data.table(read.csv(pp('~/Documents/beam/scraper/',dump.code,'/station.csv')))
  status <- data.table(read.csv(pp('~/Documents/beam/scraper/',dump.code,'/status.csv')))
  update <- data.table(read.csv(pp('~/Documents/beam/scraper/',dump.code,'/update.csv')))

  status[,dt:=as.POSIXct(timestamp,origin='1970-01-01')]
  station[,id.long:=factor(id)]
  station[,id:=as.numeric(id.long)]
  station[tolower(state)=='california',state:='CA']
  setkey(station,id.long)
  station[,level:= unlist(lapply(str_split(id.long,"-"),function(ll){ tail(ll,1) }))]
  station[level=="DC" | source=='avinc',level:='DCFAST']
  station[,county:=NULL]
  station[,county:=over(SpatialPoints(station[,list(longitude,latitude)],proj4string=CRS(proj4string(sf.counties))),sf.counties)$NAME]
  station <- station[!is.na(county)]

  status[,id:=station[J(station_id)]$id]
  status <- join.on(status,station,'id','id',c('zip_code','county','level'))
  status <- status[!is.na(county)]
  status[,year.doy.hr:=pp(year(dt),'-',yday(dt),'-',hour(dt))]
  status[,':='(month=factor(month(dt),levels=as.character(1:12),labels=c('Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec')),hour=hour(dt))]
  status.sum <- status[,list(in.use=mean(in_use,na.rm=T),zip_code=zip_code[1],county=county[1],month=month[1],hour=hour[1],level=level[1]),by=c('id','year.doy.hr')]
  status.sum <- status.sum[,list(in.use=mean(in.use,na.rm=T),zip_code=zip_code[1],county=county[1],level=level[1]),by=c('id','hour')]

  # save(station,status,update,status.sum,file=pp('~/Documents/beam/scraper/',dump.code,'/charging.Rdata'))
  return(list(station=station,status=status,update=update,status.sum=status.sum))
})

add.tz <- function(date.str,timezone){
  pp(date.str,' ',ifelse(timezone=='PDT','-0700',
                        ifelse(timezone=='PST','-0800',
                        ifelse(timezone=='EDT','-0400',
                        ifelse(timezone=='UTC','-0000',
                        ifelse(timezone=='EST','-0500',
                        ifelse(timezone=='MST','-0700',
                        ifelse(timezone=='CST','-0600',
                        ifelse(timezone=='IST','-0800','-0000')))))))))
}
round.to.minute <- function(dt,mins=30*60){
  to.posix(format(to.posix('1970-01-01',tz='GMT') + round(as.numeric(dt)/mins)*mins,'%Y-%m-%d %H:%M',tz='America/Los_Angeles'),'%Y-%m-%d %H:%M',tz='America/Los_Angeles')
}
cp.base <- pp(matsim.shared,'/data/chargepoint/')
do.or.load(pp(cp.base,'cp.Rdata'),function(){
  cp <- list()
  for(the.file in c('cp-comm-2017-10.csv','cp-comm-2017-11.csv','cp-comm-2017-12.csv','cp-res-2017-Q4.csv')){
    cp[[length(cp)+1]] <- data.table(read.csv(pp(cp.base,the.file),skip=1))
    cp[[length(cp)]][,file:=the.file]
  }
  cp <- rbindlist(cp,use.names=T)
  cp[,type:='Commercial']
  cp[file=='cp-res-2017-Q4.csv',type:='Residential']
  cp[,session.time:=as.numeric(as.character(session.time))]
  cp[,start:=to.posix(add.tz(start,timezone),"%m/%d/%y %H:%M %z")]
  cp[,start:=to.posix(format(start,tz='America/Los_Angeles'),'%Y-%m-%d %H:%M:%S',tz='America/Los_Angeles')]
  cp[,end:=to.posix(add.tz(end,timezone),"%m/%d/%y %H:%M %z")]
  cp[,end:=to.posix(format(end,tz='America/Los_Angeles'),'%Y-%m-%d %H:%M:%S',tz='America/Los_Angeles')]

  ##########################
  # Data cleaning
  ##########################
  cp[session.time<0,start.swap:=start]
  cp[session.time<0,start:=end]
  cp[session.time<0,end:=start.swap]
  cp[,start.swap:=NULL]

  cp[,duration:=(as.numeric(end)-as.numeric(start))/3600]
  cp <- cp[is.na(duration) | duration>=0]
  cp[,start.hour:=hour(start)]
  cp[,start.month:=month(start)]
  cp[,start.mday:=mday(start)]
  cp[,start.wday:=wday(start)]

  # look at unrealistic power estimates
  #cp[id%in%cp[duration>0,list(id=id,kw=max.kwh/duration)][kw>100]$id]

  cp[id%in%cp[duration>0,list(id=id,kw=max.kwh/duration)][kw>100]$id,end:=NA]
  cp[id%in%cp[duration>0,list(id=id,kw=max.kwh/duration)][kw>100]$id,duration:=NA]
  cp <- cp[duration>0] # non-session session or one's with a tiny amount of energy delivered, just igore
  cp <- cp[!category%in%c('','Demo Unit')]

  cp[,hour.of.week:=(start.wday-1)*24+start.hour]
  cp[zip=='94536-2666',zip:='94536']
  cp[zip=='950145841',zip:='95014']

  # What would end times look like if we assume 1.5 / 6.7 / 50 for kW
  cp[,kw:=ifelse(port.type%in%c('SAE-Combo-CCS1','CHAdeMO'),50,ifelse(port.type=='NEMA',1.5,6.7))]
  cp[,sim.end:=start+(max.kwh/kw)*3600]
  cp[,kw:=ifelse(sim.end>end,max.kwh/duration,kw)]
  cp[,sim.end:=ifelse(sim.end>end,end,sim.end)]
  cp[,end.diff:=as.numeric(end-sim.end)/3600]
  cp[,level:=ifelse(kw<3,'L1',ifelse(kw>20,'L3','L2'))]
  cp[,start.day.of.year:=yday(start)]

  n.min <- 5
  cp[,start.round:=round.to.minute(start,n.min*60)]
  cp[,end.round:=round.to.minute(end,n.min*60)]

  cp <- join.on(cp,zip.to.county,'zip','zip')

  cp <- cp[!is.na(county)] # a few stray zips in socal are out of scope 

  # save(station,status,update,status.sum,file=pp('~/Documents/beam/scraper/',dump.code,'/charging.Rdata'))
  return(list(cp=cp))
})


#cp[,list(mwh=sum(max.kwh)/1e3),by=c('type','category','port.type')]
#cp.sum <- cp[start.month>1,list(mwh=sum(max.kwh)/1e3),by=c('start.hour','port.type','category','start.month')]
#setkey(cp.sum,'port.type','start.hour')
#ggplot(cp.sum,aes(x=start.hour,y=mwh,fill=port.type))+geom_bar(stat='identity')+facet_grid(category~start.month,scales='free_y')



######################### 
# Data Exploration
#########################
cp[,list(kwh=sum(max.kwh),n.devices=length(u(device.id)),kwh.per.device=sum(max.kwh)/length(u(device.id)),
         n.sessions=length(id),kwh.per.session=sum(max.kwh)/length(id)),by=c('start.month','type')]
   #start.month        type       kwh n.devices kwh.per.device n.sessions kwh.per.session
#1:          10  Commercial 2660456.9      4582     580.632235     287495        9.253924
#2:          11  Commercial 2536664.9      4632     547.639227     270592        9.374501
#3:          12  Commercial 2379636.4      4713     504.909060     253599        9.383461
#4:          10 Residential  109748.2       582     188.570790      13355        8.217761
#5:          11 Residential  112290.6       621     180.822222      13168        8.527536
#6:          12 Residential  125282.6       698     179.487966      14496        8.642563
#7:           1 Residential      41.6         7       5.942857          7        5.942857

#ggplot(cp[,list(id=id,port.type=port.type,category=category,kw=max.kwh/duration)],aes(x=kw))+geom_histogram()+facet_wrap(~category)
#ggplot(cp[,list(id=id,port.type=port.type,category=category,kw=max.kwh/duration,L1=max.kwh/duration<3)],aes(x=kw,fill=L1))+geom_histogram(binwidth=1)+facet_wrap(~category,scales='free_y')


#ggplot(cp[,list(mwh=sum(max.kwh)/1e3),by=c('category','level','hour.of.week','start.month')],aes(x=hour.of.week,y=mwh,colour=level))+geom_line()+facet_wrap(~category,scales='free_y')+labs(x="Hour of Week",y="MWh")
#ggplot(cp,aes(x=as.numeric(end)/60,y=as.numeric(sim.end)/60,colour=factor(kw)))+geom_point()+facet_wrap(~category,scales='free_y')

#########################################################################################
# VGI
#########################################################################################

# Sample power
#cp.samp <- cp[,list(t=seq(start.round,end.round,by=n.min*60),kw=kw,category=category,level=level,month=start.month,county=county),by='id']
#cp.samp[,month:=month(t)]
#cp.samp[,mday:=mday(t)]
#cp.samp[,hour:=hour(t)]
## We've double counted the hour of daylight savings because it happened twice
#cp.samp[month==11&mday==6&hour==1,kw:=kw/2]
#cp.samp.hr <- cp.samp[,list(t=min(t),kw=sum(kw)/(60/n.min)),by=c('month','mday','hour','category','level','county')]
#cp.samp.hr[,wday:=wday(t)]
#cp.samp.hr[,yday:=yday(t)]
#cp.samp.hr[,whour:=(wday-1)*24+hour]
#cp.samp.hr[,mhour:=(mday-1)*24+hour]

# Export load shapes for analysis by Julia
#to.export <- cp.samp.hr[!(month==11 & mday%in%24:25) &!(month==12 & mday%in%26) & level!='L1' & month>1, list(kw=sum(kw)),by=c('category','level','whour','yday','month')][,list(kw=mean(kw)),by=c('category','level','whour','month')]
#setkey(to.export,category,level,month,whour)
#ggplot(to.export[,list(kw=mean(kw)),by=c('whour','level','category')],aes(x=whour,y=kw,colour=level))+geom_line()+facet_wrap(~category,scales='free_y')+labs(x="Hour of Week",y="kW",title="Load by Site Type")+scale_x_continuous(breaks=seq(0,24*7,by=24))
#write.csv(to.export,file=pp(cp.base,'cp-summary.csv'))

#ggplot(to.export[category=="Single family residential"],aes(x=whour,y=kw,colour=factor(month)))+geom_line()+labs(colour="Month",x="Hour of Week",y="kW",title="Single Family Load by Month")+scale_x_continuous(breaks=seq(0,24*7,by=24))

#ggplot(cp.samp.hr[month>1 & category=="Single family residential"],aes(x=mhour,y=kw,colour=factor(month)))+geom_line()+labs(colour="Month",x="Day of Month",y="kW",title="Single Family Load by Month")+scale_x_continuous(breaks=seq(0,24*31,by=24),labels=0:31)+facet_wrap(~month)

# Analyze the strange spike in residential

#res <- cp[category=="Single family residential",list(kwh=sum(max.kwh),n=length(max.kwh)),by=c('start.month','start.mday')]
#setkey(res,start.month,start.mday)


# Compare Scraped vs CP

# Num Devices
# Scraped: 1062
# CP: ~1800

# Go with just CP



#########################################################################################
# CALIBRATION
#########################################################################################
# Export for the validation data
# Resample to the 15-minute bin

n.min <- 15
cp[,start.round:=round.to.minute(start,n.min*60)]
cp[,end.round:=round.to.minute(end,n.min*60)]
cp.samp <- cp[,list(t=seq(start.round,end.round,by=n.min*60),kw=kw,category=category,port.type=port.type,month=start.month,county=county),by='id']
cp.samp[,month:=month(t)]
cp.samp[,wday:=wday(t)]
cp.samp[,mday:=mday(t)]
cp.samp[,hour:=hour(t)]
cp.samp[,dec.hour:= hour(t) + as.numeric(t)/3600 - floor(as.numeric(t)/3600)]
cp.samp[,site.type:='Non-Residential']
cp.samp[grep('residential|Home',category,perl=T),site.type:='Residential']
cp.samp[,spatial.group:=county]
cp.samp[,charger.type:=c('CHAdeMO'='CHAdeMO','J1772'='J-1772-2','NEMA'='J-1772-1','SAE-Combo-CCS1'='SAE-Combo-3')[as.character(port.type)]]

cp.load <- cp.samp[wday>1 & wday<7,list(kw=sum(kw),num.plugged.in=length(kw)),by=c('dec.hour','port.type','category','county','month','mday')]
cp.load <- cp.load[,list(kw=mean(kw),num.plugged.in=round(mean(num.plugged.in))),by=c('dec.hour','port.type','category','county')]

ggplot(cp.load[,list(kw=sum(kw)),by=c('dec.hour','port.type','category')],aes(x=dec.hour,y=kw,colour=port.type))+geom_line()+facet_wrap(~category,scales='free_y')
cp.load.sum <- cp.load[,list(kw=sum(kw)),by=c('dec.hour','county','category')]
setkey(cp.load.sum,county,dec.hour,category)
ggplot(cp.load.sum,aes(x=dec.hour,y=kw,fill=county))+geom_bar(stat='identity')+facet_wrap(~category,scales='free_y')

cp.load <- cp.samp[wday>1 & wday<7,list(kw=sum(kw),num.plugged.in=length(kw)),by=c('dec.hour','charger.type','site.type','spatial.group','month','mday')]
cp.load <- cp.load[,list(kw=mean(kw),num.plugged.in=round(mean(num.plugged.in))),by=c('dec.hour','charger.type','site.type','spatial.group')]
to.export <- cp.load[!is.na(charger.type),list(time=dec.hour,spatial.group,site.type,charger.type=tolower(charger.type),charging.load=kw,num.plugged.in)]
setkey(to.export,spatial.group,site.type,charger.type,time)
write.csv(to.export,file='/Users/critter/Dropbox/ucb/vto/beam-colin/analysis/calibration-v2/cp-data-for-validation.csv',row.names=F)

# Revise BEAM inputs, decide which public and residential chargers to include in outputs for calibration

points <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-developers/model-inputs/calibration-v2/charging-points.csv',stringsAsFactors=F))
sites  <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-developers/model-inputs/calibration-v2/charging-sites-with-counties.csv',stringsAsFactors=F))
plug.types <- data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-developers/model-inputs/calibration-v2/charging-plug-types.csv'))
plug.types[,cp.type:=c('CHAdeMO'='CHAdeMO','J-1772-2'='J1772','J-1772-1'='NEMA','SAE-Combo-3'='SAE-Combo-CCS1')[as.character(plugTypeName)]]

points <- join.on(points,sites,'siteID','id',c('latitude','longitude'))

points[,zip:=over(SpatialPointsDataFrame(points[, list(longitude, latitude)],data=points,proj4string=CRS("+proj=longlat +datum=WGS84")),spTransform(zips,CRS("+proj=longlat +datum=WGS84")))$ZCTA5CE10]

points <- join.on(points,plug.types,'plugTypeID','id','cp.type')

# WARNING -- This step here caused some confusion in the final results (which are correct) where it looked like we ended up with 
# ~300 more points that we started. Actually we ended up with 300 more points than what is in "plug.sum" but that's because in 
# this step I exclude points that don't have a matching CP type.
plug.sum <- points[!is.na(points$cp.type),list(n=length(id)),by=c('zip','cp.type')]

cp.sum <- cp[type=='Commercial' & start.month==12,list(n=length(u(device.id))),by=c('zip','port.type')]
cp.sum[,port.type:=as.character(port.type)]

both <- join.on(plug.sum,cp.sum,c('zip','cp.type'),c('zip','port.type'),'n','cp.')
both[is.na(cp.n),cp.n:=0]

ggplot(both,aes(x=n,y=cp.n))+geom_point()+facet_wrap(~cp.type,scales='free')+geom_abline(slope=1,intercept=0)+labs(title="Comparison of # Chargers in AFDC vs. ChargePoint")

n.new.points <- both[cp.n > n]
n.new.points[,num:=cp.n-n]
drop.points <- both[cp.n < n]
drop.points[,num:=n-cp.n]

do.or.load('/Users/critter/GoogleDriveUCB/beam-developers/model-inputs/calibration-v2/work.Rdata',function(){
  load('/Users/critter/Documents/beam/input/run0-201-plans-all.Rdata')
  load('/Users/critter/Documents/beam/input/sf-bay-sampled-plans.Rdata')
  plans <- plans[id%in%sampled.reg$smart.id]
  plans[,type:=factor(type)]
  plans[,row:=1:nrow(plans)]
  plans[,zip:=over(SpatialPointsDataFrame(plans[, list(x,y)],data=plans[,list(row,x,y)],proj4string=CRS("+proj=utm +zone=10 +ellps=GRS80 +datum=NAD83 +units=m +no_defs")),spTransform(zips,CRS("+proj=utm +zone=10 +ellps=GRS80 +datum=NAD83 +units=m +no_defs")))$ZCTA5CE10]

  setkey(plans,id)
  work <- unique(plans[type=='Work'])
  setkey(work,zip)
  work.coords <- coordinates(spTransform(SpatialPointsDataFrame(work[, list(x,y)],data=work[,list(row,x,y)],proj4string=CRS("+proj=utm +zone=10 +ellps=GRS80 +datum=NAD83 +units=m +no_defs")),CRS("+proj=longlat +datum=WGS84")))
  work[,':='(latitude=work.coords[,'y'],longitude=work.coords[,'x'])]

  list(work=work)
})

sample.parking.spaces <- function(num){
  as.numeric(sample(names(table(points$numParkingSpacesPerPoint)),num,replace=T,prob=table(points$numParkingSpacesPerPoint)))
}

site.id <- max(sites$id)+1
point.id <- max(points$id)+1
new.sites <- list()
new.points <- list()
for(i in 1:nrow(n.new.points)){
  if(n.new.points$num[i] > nrow(work[J(n.new.points$zip[i])])){
    to.use <- sample(work[J(n.new.points$zip[i])]$row,n.new.points$num[i],replace=T)
  }else{
    to.use <- sample(work[J(n.new.points$zip[i])]$row,n.new.points$num[i])
  }
  to.use <- join.on(data.table(row=to.use,temp=NA),work,'row','row',allow.cartesian=T)
  new.sites[[length(new.sites)+1]] <- data.frame(id=site.id:(site.id+nrow(to.use)-1),latitude=to.use$latitude,longitude=to.use$longitude,policyID=7,networkOperatorID=1)
  new.points[[length(new.points)+1]] <- data.frame(id=point.id:(point.id+nrow(to.use)-1),siteID=new.sites[[length(new.sites)]]$id,plugTypeID=plug.types[cp.type==n.new.points[i]$cp.type]$id,
                                                   numPlugs=1,numParkingSpacesPerPoint=sample.parking.spaces(nrow(to.use)),useInCalibration=1)
  site.id <- site.id + nrow(to.use)
  point.id <- point.id + nrow(to.use)
}
new.sites <- rbindlist(new.sites)
new.sites[,spatialGroup:=over(SpatialPoints(new.sites[,list(longitude,latitude)],proj4string=CRS(proj4string(sf.counties))),sf.counties)$NAME]
new.sites[,siteType:='Non-Residential']
new.points <- rbindlist(new.points)

points[,useInCalibration:=T]
points[,row:=1:nrow(points)]
setkey(points,zip,cp.type)
for(i in 1:nrow(drop.points)){
  if(drop.points$num[i] > nrow(points[J(drop.points$zip[i],drop.points$cp.type[i])])){
    my.cat('warning')
    to.drop <- points[J(drop.points$zip[i],drop.points$cp.type[i])]$row
  }else{
    to.drop <- sample(points[J(drop.points$zip[i],drop.points$cp.type[i])]$row,drop.points$num[i])
  }
  points[row%in%to.drop, useInCalibration:=F]
}

all.points <- rbindlist(list(points[,list(id,siteID,plugTypeID,numPlugs,numParkingSpacesPerPoint,useInCalibration)],new.points),use.names=T)
all.points[,use:=ifelse(useInCalibration==0,'FALSE','TRUE')]
all.points[,':='(useInCalibration=use,use=NULL)]
all.sites <- rbindlist(list(data.table(read.csv('/Users/critter/GoogleDriveUCB/beam-developers/model-inputs/calibration-v2/charging-sites-with-counties.csv')),new.sites),use.names=T)

write.csv(all.points,file='/Users/critter/GoogleDriveUCB/beam-developers/model-inputs/calibration-v2/charging-points-cp.csv',row.names=F)
write.csv(all.sites,file='/Users/critter/GoogleDriveUCB/beam-developers/model-inputs/calibration-v2/charging-sites-cp.csv',row.names=F)

