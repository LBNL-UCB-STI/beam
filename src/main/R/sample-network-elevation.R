
load.libraries(c('raster','rgdal'))

load(file='/Users/critter/Dropbox/ucb/vto/MATSimPEV/model-inputs/development/link-nodes.Rdata')

r <- raster('/Users/critter/Dropbox/ucb/vto/MATSimPEV/spatial-data/sf-bay-area-dem/sf-bay-area-dem.tif')

setkey(link.nodes,to)
nodes <- u(link.nodes)[,list(x=to.x,y=to.y,id=to)]
nodes.sp <- spTransform(SpatialPointsDataFrame(nodes[,list(x,y)],nodes[,list(id)],proj4string = CRS("+init=epsg:26910")),CRS("+proj=longlat +ellps=GRS80 +no_defs"))

nodes[,elev:=extract(r,nodes.sp)]

link.nodes <- join.on(link.nodes,nodes,'to','id','elev','to.')
link.nodes <- join.on(link.nodes,nodes,'from','id','elev','from.')
link.nodes[,gradient:=(to.elev - from.elev)/length]
link.nodes[is.na(gradient),gradient:=0.0]

# Cut the gradient off at 50%
link.nodes[gradient< -0.3,gradient:=-0.3]
link.nodes[gradient> 0.3,gradient:=0.3]

#write.csv(link.nodes[,list(linkId=pp('sfpt',id),gradient=gradient)],file='/Users/critter/Dropbox/ucb/vto/MATSimPEV/model-inputs/development/link-attributes.csv',row.names=F)

file.out <- '/Users/critter/Dropbox/ucb/vto/MATSimPEV/model-inputs/development/network-car-only-v2-plus-gradient.xml'

cat('<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE network SYSTEM "http://www.matsim.org/files/dtd/network_v2.dtd">
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

links.out <- pp('\t\t<link id="sfpt',link.nodes$id,'" from="sfpt',link.nodes$from,'" to="sfpt',link.nodes$to,'" length="',link.nodes$length,'" freespeed="',link.nodes$freespeed,'" capacity="',link.nodes$capacity,'" permlanes="',link.nodes$permlanes,'" oneway="',link.nodes$oneway,'" modes="',link.nodes$modes,'">
\t\t\t<attributes>
\t\t\t\t<attribute name="gradient" class="java.lang.Double" >',link.nodes$gradient,'</attribute>
\t\t\t</attributes>
\t\t</link>')

cat(pp(links.out,collapse='\n'),file=file.out,append=T)

cat('
\t</links>

<!-- ====================================================================== -->

</network>
',file=file.out,append=T) 
