# Path Traversals over iterations
library(colinmisc)
load.libraries(c('stringr','ggplot2'))
source('/Users/critter/Dropbox/ucb/vto/beam-all/beam/src/main/R/beam-utilities.R')

base.dir <- '/Users/critter/Documents/beam/beam-output/sf-light-25k__2018-12-12_15-34-12/'

pt <- list()
for(iter.dir in list.files(pp(base.dir,'/ITERS/'))){
  iter <- as.numeric(str_split(iter.dir,'it\\.')[[1]][2])
  eventsfile <- pp(base.dir,'ITERS/',iter.dir,'/',iter,'.events.csv.gz')
  if(file.exists(eventsfile)){
    ev <- csv2rdata(eventsfile)
    ev[,iter:=iter]
    pt[[length(pt)+1]] <- ev[type=='PathTraversal']
  }
}
pt <- rbindlist(pt)

# personal travel only
pt[,isRideHail:=substr(vehicle,1,5)=='rideH']
pt <- pt[mode=='car' & !isRideHail]

setkey(pt,iter,time)
pts <- pt[,.(n=.N,len=sum(length)),c('driver','iter')]
ptss <- pts[,.(iter,n.ratio=n/n[1],len.ratio=len/len[1],avg.n=mean(n),avg.len=mean(len)),by='driver']
ptss[,iter.str:=as.character(iter)]

ggplot(ptss[,.(len.ratio=mean(len.ratio[len.ratio<Inf],na.rm=T)),by='iter.str'],aes(x=iter.str,y=len.ratio))+geom_point()
#ggplot(ptss,aes(x=iter.str,y=n.ratio))+geom_boxplot()
#ggplot(ptss,aes(x=iter.str,y=len.ratio))+geom_boxplot()
