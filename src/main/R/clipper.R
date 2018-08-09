
plots.dir <- '/Users/critter/Dropbox/ucb/vto/beam-colin/sf-bay-area/clipper/'

if(file.exists("/Users/critter/Dropbox/ucb/vto/beam-colin/sf-bay-area/clipper/2014-Clipper-prepped.Rdata")){
  load("/Users/critter/Dropbox/ucb/vto/beam-colin/sf-bay-area/clipper/2014-Clipper-prepped.Rdata")
}else{
  load("/Users/critter/Dropbox/ucb/vto/beam-colin/sf-bay-area/clipper/2014-Clipper.Rdata")
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
  save(stations,df,file="/Users/critter/Dropbox/ucb/vto/beam-colin/sf-bay-area/clipper/2014-Clipper-prepped.Rdata")
}


p <- ggplot(df[,.(n=length(Year)),by=c('AgencyName','on.hr')],aes(x=on.hr,y=n,fill= AgencyName))+geom_bar(stat='identity')+facet_wrap(~AgencyName)
pdf.scale <- .8
ggsave(pp(plots.dir,'clipper-use.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

p <- ggplot(df[CircadianDayOfWeek_Name=='Wednesday',.(n=length(Year)),by=c('AgencyName','on.hr')],aes(x=on.hr,y=n,fill= AgencyName))+geom_bar(stat='identity')+facet_wrap(~AgencyName)
pdf.scale <- .8
ggsave(pp(plots.dir,'clipper-use-wednesday.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

p <- ggplot(df[CircadianDayOfWeek_Name=='Wednesday' & AgencyName%in%c('AC Transit','SF Muni','VTA','BART'),.(n=length(Year)),by=c('AgencyName','on.hr')],aes(x=on.hr,y=n,fill= AgencyName))+geom_bar(stat='identity')+facet_wrap(~AgencyName)
pdf.scale <- .8
ggsave(pp(plots.dir,'clipper-use-wednesday-big4.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

# now make scaled plot that approximates ridership for the big 4 based on 
# http://www.vitalsigns.mtc.ca.gov/transit-ridership

big4 <- data.table(name=c('AC Transit','SF Muni','VTA','BART'),num=c(181.9,777,146.7,458.9)*1e3)
clip4 <- df[CircadianDayOfWeek_Name=='Wednesday' & AgencyName%in%c('AC Transit','SF Muni','VTA','BART'),.(n=length(Year)),by=c('AgencyName')]
big4 <- join.on(big4,clip4,'name','AgencyName')
big4[,scale:=num/n]

toplot <- join.on(df[CircadianDayOfWeek_Name=='Wednesday' & AgencyName%in%c('AC Transit','SF Muni','VTA','BART'),.(n=length(Year)),by=c('AgencyName','on.hr')],big4,'AgencyName','name','scale')

p <- ggplot(toplot,aes(x=on.hr,y=n*scale,fill=AgencyName))+geom_bar(stat='identity')+facet_wrap(~AgencyName)
pdf.scale <- .8
ggsave(pp(plots.dir,'clipper-use-wednesday-big4-scaled.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

toplot <- df[CircadianDayOfWeek_Name=='Wednesday' & AgencyName%in%c('AC Transit','SF Muni','VTA','BART'),.(n=length(Year)),by=c('AgencyName','on.hr')]
toplot <- join.on(toplot,toplot[,.(max=max(n)),by=c('AgencyName')],c('AgencyName'),c('AgencyName'))

p <- ggplot(toplot[on.hr>4],aes(x=on.hr,y=n/max,fill=AgencyName))+geom_bar(stat='identity')+facet_wrap(~AgencyName)+labs(x="Hour",y="Normalized Boarding Passengers",title='',fill="Transit Agency")
pdf.scale <- .8
ggsave(pp(plots.dir,'clipper-use-wednesday-big4-normalized.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')
