
al<-data.table(read.csv('~/Documents/beam/beam-output/1k_2017-10-24_10-01-44/choiceAlts.csv'))

alm <- melt(al,id.vars='choice')

alm[value>1e300,value:=NA]

alm[,type:='cost']
alm[grep("Time",variable),type:='time']
alm[grep("Dist",variable),type:='dist']
alm[grep("Dist",variable),value:=value/1609]

alm[,code:=substr(variable,-4,2)]

ggplot(alm,aes(x=code,y=value))+geom_boxplot()+facet_grid(type~choice,scales='free_y')
