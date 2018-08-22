
df <- data.table(read.table('~/Dropbox/ucb/vto/beam-colin/analysis/smart-calibration/mc-v5.txt',sep='@',colClasses='character',header=F))
df[,isfile:=substr(V1,1,4)=="/Use"]
df[,num:=cumsum(isfile)]
mc <- join.on(df[isfile==F],df[isfile==T],'num','num','V1','file')
mc[,':='(choice=V1,V1=NULL,file=fileV1,fileV1=NULL,isfile=NULL)]
mc[,scenario:=unlist(lapply(str_split(file,"/"),function(x){tail(x,1)}))]
mc[,file:=NULL]
mc[,n:=unlist(lapply(str_split(choice,' ModeChoice,,'),function(x){as.numeric(head(x,1))}))]
mc[,mode:=unlist(lapply(str_split(choice,' ModeChoice,,'),function(x){tail(x,1)}))]
toplot <- mc[,.(perc=n/sum(n),mode=mode),by=c('scenario')]
toplot.trans <- toplot[mode%in%c('walk_transit','drive_transit'),.(perc=sum(perc),mode='transit'),by='scenario']
toplot <- rbindlist(list(toplot[!mode%in%c('walk_transit','drive_transit')],toplot.trans),use.names=T)
target <- data.frame(mode=rep(c('car','walk','transit','ride_hail'),length(u(toplot$scenario))),perc=rep(c(79,4,13,5),length(u(toplot$scenario))),scenario=rep(u(toplot$scenario),each=length(u(toplot$mode))))
ggplot(toplot,aes(x=mode,y=perc*100))+geom_bar(stat='identity')+facet_wrap(~scenario)+geom_point(data=target,aes(y=perc),colour='red')

