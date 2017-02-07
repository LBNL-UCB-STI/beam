load.libraries(c('XML','rgdal','maptools'))

counties <- readShapePoly('~/Dropbox/ucb/vto/MATSimPEV/spatial-data/ca-counties/ca-counties.shp',proj4string=CRS("+proj=longlat +datum=WGS84"))
zips <- readShapePoly('~/Dropbox/ucb/vto/MATSimPEV/spatial-data/ca-zips/sf-bay-area-zips.shp',proj4string=CRS("+proj=longlat +datum=WGS84"))
sf.county.inds <- counties$NAME %in% c('Alameda','San Mateo','Sonoma','Marin','Contra Costa','Napa','Solano','Santa Clara','San Francisco')
zips.in.sf <- which(!is.na(over(zips,counties[sf.county.inds,])$NAME))
sf.zips <- spTransform(zips[zips.in.sf,],CRS("+init=epsg:26910"))
sf.counties <- spTransform(counties[sf.county.inds,],CRS("+init=epsg:26910"))

do.or.load(pp(matsim.shared,"model-inputs/development/link-nodes.Rdata"),function(){
  load(file=pp(matsim.shared,"model-inputs/development/network_SF_Bay_detailed.Rdata"))
  link.nodes <- join.on(links,nodes,'from','id',c('x','y'),'from.')
  link.nodes <- join.on(link.nodes,nodes,'to','id',c('x','y'),'to.')
  link.nodes[,cent.x:=(to.x+from.x)/2]
  link.nodes[,cent.y:=(to.y+from.y)/2]
  link.nodes[,zip:=over(SpatialPoints(link.nodes[,list(cent.x,cent.y)],proj4string=CRS(proj4string(sf.zips))),sf.zips)$ZCTA5CE10]
  link.nodes[,county:=over(SpatialPoints(link.nodes[,list(cent.x,cent.y)],proj4string=CRS(proj4string(sf.counties))),sf.counties)$NAME]
  list(link.nodes=link.nodes)
})

load('/Users/critter/Documents/matsim/pev/sf-bay_2016-09-25_23-53-40/ITERS/it.0/run0.0.events.Rdata')
ev <- df
rm('df')
nl <- ev[type=='NestedLogitDecisionEvent']
nl[,need:=max(choiceUtility) - choiceUtility]
nl[,hour:=floor(time/3600)]
nl[,link:=as.numeric(substr(as.character(link),5,str_length(need)))]
nl <- join.on(nl,ev[type=='ArrivalChargingDecisionEvent'],'decisionEventId','decisionEventId',c('choice','adaptation','soc','plugType','searchRadius'))
nl[,chargerType:='Level 2']
nl[plugType%in%c('chademo','sae-combo-3','tesla-3'),chargerType:='DC Fast']
nl[plugType%in%c('j-1772-1'),chargerType:='Level 1']

need <- nl[,list(need=sum(need)),by=c('link','hour','isBEV','choice','adaptation','chargerType','isHomeActivity')][!is.na(need) & !is.na(link)]
need <- join.on(need,link.nodes,'link','id')
# Scale need by length to make it spatially sensible
need[,need:=need/length]
need[,need:=need/max(need,na.rm=T)]
need[,priority:=factor(floor(log10(need) - min(log10(need))))]
need.by.link <- need[,list(to.x=to.x[1],to.y=to.y[1],from.x=from.x[1],from.y=from.y[1],need=sum(need)),by=c('link')]
need.by.link[,priority:=factor(floor(log10(need) - min(log10(need))))]

the.ns <- c(500,1000,2500,5000)
the.ns <- c(1000)
sampled.ns <- need.by.link[match(sample(link,max(the.ns),prob=need,replace=T),link)]
ggplot(data.table(adply(the.ns,1,function(i){ copy(need.by.link)[,n:=i] })),aes(x=to.x,xend=from.x,y=to.y,yend=from.y,colour=6+log10(need)))+ scale_colour_gradient(low='white',high="red")+ geom_segment(size=.75)+
  geom_point(data=data.table(adply(the.ns,1,function(i){ copy(sampled.ns[1:i])[,n:=i] })),colour='blue',alpha=0.5)+
  geom_path(data=data.table(adply(the.ns,1,function(i){ data.table(fortify(sf.counties,region='NAME'))[,n:=i] })),aes(x=long,y=lat,group=group,xend=long,yend=lat),colour='black')+facet_wrap(~n)+
  labs(x="",y="",colour="Need")

setkey(need,link,hour,isBEV,choice,adaptation,chargerType,isHomeActivity)
ggplot(need[,list(need=sum(need)),by='hour'],aes(x=hour,y=need))+geom_line()+labs(x='Hour',y='Charging Infrastructure Need')
ggplot(need[,list(need=sum(need)),by=c('county','hour')],aes(x=hour,y=need,colour=county))+geom_line()+labs(x='Hour',y='Charging Infrastructure Need')

ggplot(need[,list(need=sum(need)),by=c('isBEV','hour')],aes(x=hour,y=need,fill=factor(isBEV)))+geom_bar(stat='identity')+labs(x='Hour',y='Charging Infrastructure Need')
ggplot(nl,aes(x=nextTripTravelDistance,y=need))+geom_point()
ggplot(nl,aes(x=soc,y=need,colour=factor(isHomeActivity),shape=chargerType))+geom_point()+facet_wrap(~choice)+labs(x="State of Charge",y="Charging Infrastructure Need",colour="Home Activity?",shape="Level")

