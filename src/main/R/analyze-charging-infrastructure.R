2883701

dat <- data.table(read.csv('~/Downloads/afi-runs.csv'))

datm <- melt(dat,id.vars='filename')

datm[,group:=rep(1:11,each=(12*6))[1:756]]
 datm[,infra:=(unlist(lapply(str_split(as.character(filename),"-"),function(ll){ ll[3]})))]
 datm[,range:=as.numeric(unlist(lapply(str_split(as.character(filename),"-"),function(ll){ ll[2]})))]

for(i in u(datm$group)){
  dev.new()
  p <- ggplot(datm[group==i],aes(x=filename,y=value,fill=infra))+geom_bar(stat='identity')+facet_wrap(~variable,scales='free_y')+theme(axis.text.x = element_text(angle = 25, hjust = 1))
  print(p)
}


for(i in 1:11){
  
}
