
load.libraries(c('optparse','XML','stringr','rgdal','sp'),quietly=T)

load("/Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/physsim-network.Rdata")
pems <- data.table(read.csv('~/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/counts/preprocessing/matched_sensors.csv'))

linknodes <- join.on(join.on(links,nodes,'from','id',c('x','y'),'from.'),nodes,'to','id',c('x','y'),'to.')
linknodes[,cent.x:=(from.x+to.x)/2]
linknodes[,cent.y:=(from.y+to.y)/2]
reproj.nodes <- linknodes
coordinates(reproj.nodes) <- ~ cent.x+cent.y
proj4string(reproj.nodes) <-  CRS("+init=epsg:26910")
reproj.nodes.wgs <- spTransform(reproj.nodes,CRS("+init=epsg:4326"))
linknodes[,cent.lon:=coordinates(reproj.nodes.wgs)[,1]]
linknodes[,cent.lat:=coordinates(reproj.nodes.wgs)[,2]]
  

thresh <- 0.005
for(i in 1:nrow(pems)){
  if(i%%25==0)my.cat(i)
  search.set <- linknodes[abs(cent.lon - pems[i]$Longitude)<thresh & abs(cent.lat - pems[i]$Latitude)<thresh]
  if(nrow(search.set)==0){
    my.cat(pp('No links within ',thresh,' of pem station ',pems[i]$ID))
  }else{
    search.set[,dist.sq:=(cent.lon- pems[i]$Longitude)^2 + (cent.lat -  pems[i]$Latitude)^2]
    pems[i,link:=search.set[which.min(dist.sq)]$id]
  }
}

# visualize it
pems2 <- join.on(pems,linknodes,'link','id',c('cent.lon','cent.lat'))
ggplot(nodes,aes(x=lon,y=lat))+geom_point(size=0.1,colour='grey')+geom_point(data= pems2[inds],aes(x=Longitude,y=Latitude),size=2)+geom_point(data=pems2[inds],aes(x=cent.lon,y=cent.lat),colour='red',size=1)

# Grab the counts data
pems.base <- '/Users/critter/odrive/GoogleDriveUCB/Shared with Me/ucb_smartcities_data/2. SF Smart Bay/f. Sensors/2015_data/2015_all'

pems[,found:=sapply(ID,function(id){ file.exists(pp(pems.base,'/',id,'/time_series.csv'))})]
inds <- sample(which(pems$found),300)

counts.all <- list()
for(id in pems[inds]$ID){
  if(file.exists(pp(pems.base,'/',id,'/time_series.csv'))){
    cat(pp(id,','))
    ts <- data.table(read.csv(pp(pems.base,'/',id,'/time_series.csv')))
    ts[,dt:=to.posix(Timestamp,'%m/%d/%Y %H:%M:%S')]
    ts[,':='(doy=yday(dt),hr=hour(dt),dow=wday(dt))]
    counts <- ts[dow>2 & dow<6,.(flow=sum(Total_Flow)),by=c('doy','hr')][,.(counts.per.hr=mean(flow,na.rm=T)),by='hr']
    counts[,ID:=id]
    counts.all[[length(counts.all)+1]] <- counts
  }else{
    my.cat(pp('no data for station ',id))
  }
} 
counts <- rbindlist(counts.all)
counts[hr==0,hr:=24]
setkey(counts,ID,hr)


# write them out
outfile <- '~/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/counts/sfbay-counts-2018-09-26.xml'

the.str <- '<?xml version="1.0" ?>\n
<counts desc="Fall 2015 wkdys" layer="0" name="Fall 2015 - TWTh" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://matsim.org/files/dtd/counts_v1.xsd" year="2015">\n'
cat(the.str,file=outfile,append=F)
i <- inds[1]
for(i in inds){
  id <- pems$ID[i]
  link <- pems$link[i]
  the.str <- pp('\t<count cs_id="',id,'" loc_id="',link,'">\n')
  the.cnts <- pp('\t\t<volume h="',1:24,'" val="',round(counts[J(id)]$counts.per.hr,0),'"/>\n')
  the.close <- pp('\t</count>\n')
  cat(the.str,file=outfile,append=T)
  cat(the.cnts,file=outfile,append=T)
  cat(the.close,file=outfile,append=T)
}
the.str <- '</counts>'
cat(the.str,file=outfile,append=T)

