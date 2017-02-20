
load.libraries(c('XML','rgdal'))

do.or.load(pp(matsim.shared,"model-inputs/calibration-v2/network.Rdata"),function(){
  net <- xmlParse(pp(matsim.shared,"model-inputs/calibration-v2/network.xml"))

  nodes <- data.table(id=xpathSApply(net,'/network/nodes/node', xmlGetAttr,'id'),
                    x=as.numeric(xpathSApply(net,'/network/nodes/node', xmlGetAttr,'x')),
                    y=as.numeric(xpathSApply(net,'/network/nodes/node', xmlGetAttr,'y')))

  links <- data.table(id=xpathSApply(net,'/network/links/link', xmlGetAttr,'id'),
                      from=xpathSApply(net,'/network/links/link', xmlGetAttr,'from'),
                      to=xpathSApply(net,'/network/links/link', xmlGetAttr,'to'),
                      length=as.numeric(xpathSApply(net,'/network/links/link', xmlGetAttr,'length')),
                      freespeed=as.numeric(xpathSApply(net,'/network/links/link', xmlGetAttr,'freespeed')),
                      capacity=as.numeric(xpathSApply(net,'/network/links/link', xmlGetAttr,'capacity')),
                      permlanes=as.numeric(xpathSApply(net,'/network/links/link', xmlGetAttr,'permlanes')),
                      oneway=as.numeric(xpathSApply(net,'/network/links/link', xmlGetAttr,'oneway')),
                      modes=xpathSApply(net,'/network/links/link', xmlGetAttr,'modes'))

  link.nodes <- join.on(links,nodes,'from','id',c('x','y'),'from.')
  link.nodes <- join.on(link.nodes,nodes,'to','id',c('x','y'),'to.')

  list(link.nodes=link.nodes)
})

to.process <- copy(link.nodes[modes=='car'])
setkey(to.process,from)

half.dist.of.group <- 1000

to.process[,done:=F]
setkey(to.process,from)
group.i <- 1
while(any(!to.process$done)){
  start.id <- to.process[done==F][1]$from

  links.in.group <- start.id
  dist.from.center <- 0
  next.id <- start.id
  while(dist.from.center < half.dist.of.group){
    dist.from.center <- dist.from.center + to.process[J(next.id)][done==F]$length[1]
    next.id <- to.process[J(next.id)][done==F]$to[1]

    if(is.na(next.id))break
    links.in.group <- c(links.in.group,next.id)
  }
  to.process[from%in%links.in.group,group:=group.i]
  to.process[from%in%links.in.group,done:=T]
  group.i <- group.i + 1
}

#p <- ggplot() + geom_segment(data=link.nodes[modes!='car'],aes(x=from.x,xend=to.x,y=from.y,yend=to.y),colour='blue') +
#print(p)


# Write to file

file.out <- pp(matsim.shared,"model-inputs/beam/thinnedNetworkThinnedBart.xml")

cat('<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE network SYSTEM "http://www.matsim.org/files/dtd/network_v1.dtd">
<network>

<!-- ====================================================================== -->

	<nodes>
',file=file.out) 

nodes.out <- pp('\t\t<node id="',all.nodes$id,'" x="',all.nodes$x,'" y="',all.nodes$y,'" />')
cat(pp(nodes.out,collapse='\n'),file=file.out,append=T)

cat('
\t</nodes>

<!-- ====================================================================== -->

	<links capperiod="01:00:00" effectivecellsize="7.5" effectivelanewidth="3.75">
',file=file.out,append=T) 

links.out <- pp('\t\t<link id="',all.link.nodes$id,'" from="',all.link.nodes$from,'" to="',all.link.nodes$to,'" length="',all.link.nodes$length,'" freespeed="',all.link.nodes$freespeed,'" capacity="',all.link.nodes$capacity,'" permlanes="',all.link.nodes$permlanes,'" oneway="',all.link.nodes$oneway,'" modes="',all.link.nodes$modes,'" />')
cat(pp(links.out,collapse='\n'),file=file.out,append=T)

cat('
\t</links>

<!-- ====================================================================== -->

</network>
',file=file.out,append=T) 
