
ggplot(data.table(x=(seq(-2.5,0.5,by=.1)+2.5), y=pnorm(seq(-2.5,0.5,by=.1))*100),aes(x=x,y=y))+geom_line()+labs(x="Hours Until Departure",y="Option Value on Mobility")

soc.dat <- data.table(x=0:23, y1=c(0.2,0.19,0.17,0.18,0.3,0.45,0.52,0.61,0.65,0.60,0.55,0.5,0.4,0.35,0.31,0.27,0.26,0.25,0.24,0.22,0.21,0.2,0.2,0.2),y2=c(0.5,0.48,0.47,0.55,0.65,0.7,0.78,0.85,0.95,1.0,0.95,0.9,0.89,0.86,0.84,0.8,0.75,0.72,0.63,0.6,0.55,0.53,0.52,0.5),y3=c(0.2,0.19,0.17,0.18,0.3,0.45,0.52,0.61,0.65,0.60,0.55,0.5,0.4,0.35,0.31,0.27,0.26,0.25,0.24,0.22,0.21,0.2,0.2,0.2)+0.1)

ggplot(soc.dat,aes(x=x,ymin=y1,ymax=y2))+labs(x="Hour",y="Min/Max Aggregate SOC")+ geom_ribbon(colour='black',fill='grey')

ggplot(soc.dat,aes(x=x,ymin=y1,ymax=y2))+labs(x="Hour",y="Min/Max Aggregate SOC")+ geom_ribbon(colour='black',fill='grey')+geom_line(aes(y=y3),colour='red',type=2)

ggplot(,aes(x=x,ymin=y1,ymax=y2))+labs(x="Hour",y="Min/Max Aggregate SOC")+ geom_ribbon(colour='black',fill='grey')+geom_line(aes(y=y3),colour='red',type=2)

disag.soc <- soc.dat[,list(y1=0,y2=0,y3=y3,y4=sapply(rnorm(100,y3,0.2),function(xx){ min(max(0,xx),1)})),by='x']
disag.soc <- soc.dat[,list(y1=0,y2=0,y3=y3,y4=sapply(rnorm(100,y3,0.05),function(xx){ min(max(0,xx),1)})),by='x']

ggplot(soc.dat,aes(x=x,ymin=y1,ymax=y2))+labs(x="Hour",y="Min/Max Aggregate SOC")+ geom_ribbon(data=soc.dat,colour='black',fill='grey')+geom_line(data=soc.dat,aes(y=y3),colour='red',type=2)+geom_point(data=disag.soc,aes(x=x,y=y4))


# Something more realistic
e1.max <- c(0,0,7,14,21,rep(24,5),rep(31,14))
e1.min <- c(rep(0,6),7,14,21,rep(24,5),rep(31,10))
e2.max <- c(0,0,0,7,14,rep(14,9),rep(16,10))
e2.min <- c(rep(0,5),7,rep(14,9),rep(16,9))

es <- data.table(e=rep(c('e1','e2'),each=24),t=rep(1:24,2),max=c(e1.max,e2.max),min=c(e1.min,e2.min))
es.m <- melt(es,id.vars=c('t','e'))
setkey(es.m,t,e,variable)
ggplot(es.m,aes(x=t,y=value,colour=variable))+geom_line()+labs(x="Hour of Day",y="Cumulative Energy Demanded (kWh)",title="Individual PEVs")+facet_wrap(~e)

es.sum <- es.m[,list(value=sum(value)),by=c('t','variable')]
ggplot(es.sum,aes(x=t,y=value,colour=variable))+geom_line()+labs(x="Hour of Day",y="Cumulative Energy Demanded (kWh)",title="Aggregated PEVs")
