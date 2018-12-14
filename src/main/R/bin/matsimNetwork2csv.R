#!/usr/local/bin/Rscript
##############################################################################################################################################
# Script to convert XML files to an R data table and then save as Rdata 
##############################################################################################################################################

##############################################################################################################################################
# LOAD LIBRARIES NEED BY THIS SCRIPT
library(colinmisc)
load.libraries(c('optparse','XML','stringr','rgdal','sp'),quietly=T)

##############################################################################################################################################
# COMMAND LINE OPTIONS 
option_list <- list(
)
if(interactive()){
  #setwd('~/downs/')
  args<-'/Users/critter/Dropbox/ucb/vto/beam-all/beam/production/sfbay/r5-nolocal/physsim-network.xml'
  args <- parse_args(OptionParser(option_list = option_list,usage = "matsimNetwork2csv.R [file-to-convert]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "matsimNetwork2csv.R [file-to-convert]"),positional_arguments=T)
}

######################################################################################################
file.path <- args$args[1]

path <- str_split(file.path,"/")[[1]]
if(length(path)>1){
  the.file <- tail(path,1)
  the.dir <- pp(pp(head(path,-1),collapse='/'),'/')
}else{
  the.file <- path
  the.dir <- './'
}
the.file.rdata <- pp(head(str_split(the.file,'xml')[[1]],-1),'Rdata')

dat <- xmlParse(file.path)

nodes <- data.table(id=xpathSApply(dat,"/network/nodes/node", xmlGetAttr,'id',default=NA),x=as.numeric(xpathSApply(dat,"/network/nodes/node", xmlGetAttr,'x',default=NA)),y=as.numeric(xpathSApply(dat,"/network/nodes/node", xmlGetAttr,'y',default=NA)))
reproj.nodes <- nodes
coordinates(reproj.nodes) <- ~ x+y
proj4string(reproj.nodes) <-  CRS("+init=epsg:26910")
reproj.nodes.wgs <- spTransform(reproj.nodes,CRS("+init=epsg:4326"))
nodes[,lon:=coordinates(reproj.nodes.wgs)[,1]]
nodes[,lat:=coordinates(reproj.nodes.wgs)[,2]]

links <- data.table(id=xpathSApply(dat,"/network/links/link", xmlGetAttr,'id',default=NA),
                    from=xpathSApply(dat,"/network/links/link", xmlGetAttr,'from',default=NA),
                    to=xpathSApply(dat,"/network/links/link", xmlGetAttr,'to',default=NA),
                    freespeed=as.numeric(xpathSApply(dat,"/network/links/link", xmlGetAttr,'freespeed',default=NA)),
                    length=as.numeric(xpathSApply(dat,"/network/links/link", xmlGetAttr,'length',default=NA)))
links[,travelTime:=length/freespeed]

write.csv(nodes,file=pp(the.dir,'physsim-network-nodes.csv'),row.names=F)
write.csv(links,file=pp(the.dir,'physsim-network-links.csv'),row.names=F)
save(nodes,links,file=pp(the.dir,'physsim-network.Rdata'))

