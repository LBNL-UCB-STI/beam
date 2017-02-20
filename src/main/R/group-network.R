
load.libraries(c('XML','rgdal','dbscan'))

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

to.process[,group:=kmeans(to.process[,list(from.x,from.y)],nrow(to.process)/80)$cluster]
hist(to.process[!is.na(group),sum(length),by='group']$V1,breaks=100)
hist(to.process[,list(max.dist=max(dist(as.matrix(from.x,from.y)))),by=group]$max.dist,breaks=100)

ggplot() + geom_segment(data=to.process,aes(x=from.x,xend=to.x,y=from.y,yend=to.y,colour=factor(group%%20)))

write.csv(to.process[,list(linkId=id,group=group)],file=pp(matsim.shared,"model-inputs/calibration-v2/link-attributes-grouped.csv"),row.names=F)

#### BELOW WAS A FIRST ATTEMPT BUT UNNEEDED

if(F){

  to.process <- join.on(to.process,to.process[,list(n.froms=length(id)),by='from'],'from','from','n.froms')
  setkey(to.process,n.froms)

  half.dist.of.group <- 1000

  to.process[,done:=F]
  setkey(to.process,from)
  group.i <- 1
  while(any(!to.process$done)){
    if(to.process[done==F]$n.froms[1]>1){
      to.process[,n.froms:=NULL]
      to.process <- join.on(to.process,to.process[done==F,list(n.froms=length(id)),by='from'],'from','from','n.froms')
      setkey(to.process,n.froms)
    }
    start.id <- to.process[done==F][1]$from

    links.in.group <- start.id
    dist.from.center <- 0
    next.id <- start.id
    while(dist.from.center < half.dist.of.group){
      the.inds <- which(to.process$from==next.id)
      dist.from.center <- dist.from.center + to.process[the.inds][done==F]$length[1]
      next.id <- to.process[the.inds][done==F]$to[1]

      if(is.na(next.id))break
      links.in.group <- c(links.in.group,next.id)
    }
    to.process[from%in%links.in.group,group:=group.i]
    to.process[from%in%links.in.group,done:=T]
    group.i <- group.i + 1
  }

  p <- ggplot() + geom_segment(data=to.process[1:1000],aes(x=from.x,xend=to.x,y=from.y,yend=to.y),colour='blue') 
  print(p)
}

