
load("/Users/critter/Dropbox/ucb/vto/beam-colin/sf-bay-area/clipper/2014-Clipper.Rdata")

plots.dir <- '/Users/critter/Dropbox/ucb/vto/beam-colin/sf-bay-area/clipper/'

df[,on:=to.posix(as.character(TagOnTime_Time),'%H:%M:%S.0000000')]
df[,off:=to.posix(as.character(TagOffTime_Time),'%H:%M:%S.0000000')]

df[,on.hr:=hour(on)]
df[,off.hr:=hour(off)]

df[,AgencyName:=str_trim(AgencyName)]

stations <- data.table(read.csv('/Users/critter/Dropbox/ucb/vto/beam-colin/sf-bay-area/clipper/BayAreaTransitStations.csv'))
setkey(stations,AgencyName,Station)
stations <- unique(stations)

df <- join.on(df, stations,c('AgencyName','TagOnLocationName'),c('AgencyName','Station'),c('Lat','Lon'))
df[,':='(x.on=Lon,y.on=Lat,Lon=NULL,Lat=NULL)]
df <- join.on(df, stations,c('AgencyName','TagOffLocationName'),c('AgencyName','Station'),c('Lat','Lon'))
df[,':='(x.off=Lon,y.off=Lat,Lon=NULL,Lat=NULL)]

p <- ggplot(df[,.(n=length(Year)),by=c('AgencyName','on.hr')],aes(x=on.hr,y=n,fill= AgencyName))+geom_bar(stat='identity')+facet_wrap(~AgencyName)
pdf.scale <- .8
ggsave(pp(plots.dir,'clipper-use.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

p <- ggplot(df[CircadianDayOfWeek_Name=='Wednesday',.(n=length(Year)),by=c('AgencyName','on.hr')],aes(x=on.hr,y=n,fill= AgencyName))+geom_bar(stat='identity')+facet_wrap(~AgencyName)
pdf.scale <- .8
ggsave(pp(plots.dir,'clipper-use-wednesday.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

p <- ggplot(df[CircadianDayOfWeek_Name=='Wednesday' & AgencyName%in%c('AC Transit','SF Muni','VTA','BART'),.(n=length(Year)),by=c('AgencyName','on.hr')],aes(x=on.hr,y=n,fill= AgencyName))+geom_bar(stat='identity')+facet_wrap(~AgencyName)
pdf.scale <- .8
ggsave(pp(plots.dir,'clipper-use-wednesday-big4.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')
