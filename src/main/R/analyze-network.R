library(colinmisc)
load.libraries(c('XML','rgdal'))

##net <- xmlParse(pp(matsim.shared,"model-inputs/development/network_SF_Bay_detailed.xml"))
#net <- xmlParse(pp(matsim.shared,"model-inputs/development/thinnedNetwork.xml"))
##net <- xmlParse(pp(matsim.shared,"model-inputs/development/test/network.xml"))

#nodes <- data.table(id=as.numeric(xpathSApply(net,'/network/nodes/node', xmlGetAttr,'id')),
                  #x=as.numeric(xpathSApply(net,'/network/nodes/node', xmlGetAttr,'x')),
                  #y=as.numeric(xpathSApply(net,'/network/nodes/node', xmlGetAttr,'y')))

#links <- data.table(id=as.numeric(xpathSApply(net,'/network/links/link', xmlGetAttr,'id')),
                    #from=as.numeric(xpathSApply(net,'/network/links/link', xmlGetAttr,'from')),
                    #to=as.numeric(xpathSApply(net,'/network/links/link', xmlGetAttr,'to')),
                    #length=as.numeric(xpathSApply(net,'/network/links/link', xmlGetAttr,'length')),
                    #freespeed=as.numeric(xpathSApply(net,'/network/links/link', xmlGetAttr,'freespeed')),
                    #capacity=as.numeric(xpathSApply(net,'/network/links/link', xmlGetAttr,'capacity')),
                    #permlanes=as.numeric(xpathSApply(net,'/network/links/link', xmlGetAttr,'permlanes')),
                    #oneway=as.numeric(xpathSApply(net,'/network/links/link', xmlGetAttr,'oneway')),
                    ##origid=as.numeric(xpathSApply(net,'/network/links/link', xmlGetAttr,'origid')),
                    #modes=xpathSApply(net,'/network/links/link', xmlGetAttr,'modes'))

#save(links,nodes,file=pp(matsim.shared,"model-inputs/development/network_SF_Bay_detailed.Rdata"))
#save(links,nodes,file=pp(matsim.shared,"model-inputs/development/thinnedNetwork.Rdata"))

load(file=pp(matsim.shared,"model-inputs/development-old/network_SF_Bay_detailed.Rdata"))
#load(file=pp(matsim.shared,"model-inputs/development-old/thinnedNetwork.Rdata"))

# enforce minimum link length
links[length<4.5,length:=4.5]

link.nodes <- join.on(links,nodes,'from','id',c('x','y'),'from.')
link.nodes <- join.on(link.nodes,nodes,'to','id',c('x','y'),'to.')

sf.counties <- readOGR(dsn = pp(matsim.shared,"spatial-data/sf-bay-area-counties/"), layer = "sf-bay-area-counties")
sf.counties.data <- data.table(data.frame(sf.counties))
sf.counties.epsg <- spTransform(sf.counties,CRS("+init=epsg:26910"))
sf.counties.data[,':='(lon=coordinates(sf.counties)[,1],lat=coordinates(sf.counties)[,2],x=coordinates(sf.counties.epsg)[,1],y=coordinates(sf.counties.epsg)[,2])]

ggplot() +  geom_polygon(data=sf.counties.epsg, aes(x=long, y=lat, group=group)) + geom_segment(data=link.nodes[length>0],aes(x=from.x,xend=to.x,y=from.y,yend=to.y),colour='red')

ggplot() +  geom_polygon(data=sf.counties.epsg, aes(x=long, y=lat, group=group)) + 
geom_segment(data=link.nodes[id%in%c(233738,169972)],aes(x=from.x,xend=to.x,y=from.y,yend=to.y),colour='red') + 
geom_point(data=data.frame(x=c(558999.458613,563848.930589),y=c(4197597.33886,4201400.1429)),colour='yellow',aes(x=x,y=y))


load.libraries('grid')
ggplot() +  geom_segment(data=link.nodes[id %in%c(1,3,5,7,10)], aes(x=from.x,xend=to.x,y=from.y,yend=to.y,colour=factor(id)), arrow = arrow(length = unit(0.5,"cm")))

proj4string(sites) <- CRS("+init=epsg:4326") 
sites <- data.table(coordinates(spTransform(sites,CRS("+init=epsg:26910"))))

# Creating fake data for testing
chargers <- data.table(x=c(0,0,0,1,1,1,2,2,2),y=c(0,1,2,0,1,2,0,1,2))
coordinates(chargers) = ~x + y
proj4string(chargers) <- CRS("+init=epsg:4326") 
chargers.epsg <- spTransform(chargers,CRS("+init=epsg:26910"))


# Write network to XML format
 
file.out <- '/Users/critter/Dropbox/ucb/vto/MATSimPEV/model-inputs/development/network-car-only-v2.xml'

cat('<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE network SYSTEM "http://www.matsim.org/files/dtd/network_v1.dtd">
<network>

<!-- ====================================================================== -->

	<nodes>
',file=file.out) 

nodes.out <- pp('\t\t<node id="sfpt',nodes$id,'" x="',nodes$x,'" y="',nodes$y,'" />')
cat(pp(nodes.out,collapse='\n'),file=file.out,append=T)

cat('
\t</nodes>

<!-- ====================================================================== -->

	<links capperiod="01:00:00" effectivecellsize="7.5" effectivelanewidth="3.75">
',file=file.out,append=T) 

links.out <- pp('\t\t<link id="sfpt',links$id,'" from="sfpt',links$from,'" to="sfpt',links$to,'" length="',links$length,'" freespeed="',links$freespeed,'" capacity="',links$capacity,'" permlanes="',links$permlanes,'" oneway="',links$oneway,'" modes="',links$modes,'" />')
cat(pp(links.out,collapse='\n'),file=file.out,append=T)

cat('
\t</links>

<!-- ====================================================================== -->

</network>
',file=file.out,append=T) 
