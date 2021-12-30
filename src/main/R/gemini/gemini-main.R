setwd(dirname(rstudioapi::getSourceEditorContext()$path))
source("../common/helpers.R")
source("../common/theme.R")
library('colinmisc')
source("gemini-utils.R")
library(dplyr)
library(ggrepel)
library(stringr)
library(tidyverse)
library(urbnmapr)
library(gganimate)
library(magick)
library(OpenStreetMap)
library(data.table)
library(ggplot2)
library(plyr)
library(sf)
library(proj4)
library(scales)
library(RColorBrewer)
library(ggmap)


scaleup <- FALSE
#expFactor <- (7.75/0.315) * 27.0 / 21.3
expFactor <- (6.015/0.6015)
loadInfo <- new("loadInfo", timebinInSec=900, siteXFCInKW=1000, plugXFCInKW=250)
severity_order <- c("Public <1MW", "Public 1-5MW", "Public >5MW", "Ridehail Depot <1MW", "Ridehail Depot 1-5MW", "Ridehail Depot >5MW")
extreme_lab_order <- c("<1MW", "1-5MW", ">5MW")

dataDir <- normalizePath("~/Data/GEMINI/2021Aug22-Oakland/BATCH3")
#events <- readCsv(pp(dataDir, "/events/0.events.BASE.csv.gz"))
#eventsDir <- paste(dataDir, "/events",sep="")
resultsDir <- paste(dataDir, "/results",sep="")
plotsDir <- paste(dataDir, "/plots",sep="")
mobilityDir <- paste(dataDir, "/mobility",sep="")
dir.create(resultsDir, showWarnings = FALSE)
dir.create(plotsDir, showWarnings = FALSE)

scenarioNames <- c('Scenario0', 'Scenario1')
scenarioBaselineLabel <- 'Scenario0'
countyNames <- c('Alameda County','Contra Costa County','Marin County','Napa County','Santa Clara County','San Francisco County','San Mateo County','Sonoma County','Solano County')

# MAIN
processEventsFileAndScaleUp(dataDir, scaleup, expFactor)

# PLOTS
if (!file.exists(pp(resultsDir,'/ready-to-plot.Rdata'))) {
  generateReadyToPlot(resultsDir, loadTypes, loadInfo, countyNames)
}

## Energy Share Per Load Type
load(file = pp(resultsDir,'/ready-to-plot.Rdata'))
publicLoads <- all.loads[site=='public',.(fuel=sum(fuel)),by=.(code)]
publicLoads.ByChargeType <- all.loads[site=='public',.(fuel=sum(fuel)),by=.(loadType,code)]
for(j in 1:nrow(publicLoads)){
  temp <- publicLoads.ByChargeType[code==publicLoads[j,]$code]
  temp$fuelShare <- temp$fuel/publicLoads[j,]$fuel
  print(publicLoads[j,]$code)
  print(temp[,.(loadType,fuelShare)][order(factor(loadType,levels=names(chargingTypes.colors)))])
}
scens <- as.data.table(readCsv(pp(resultsDir,'/../scenarios.csv')))
all.loads <- as.data.table(all.loads[scens, on="code", mult="all"])



##########################################
# LOADS & ENERGY
##########################################

## Baseline XFC hours per site per day
toplot <- all.loads[name==scenarioBaselineLabel]
toplot[,panel:=revalue(factor(site),c('public'='Public','depot'='Ridehail CAV Depot'))]
p <- toplot[,.(kw=sum(kw)),by=c('severity','hour.bin2', 'panel')] %>%
  ggplot(aes(x=hour.bin2,y=kw/1e6,fill=factor(severity, levels=severity_order)))+
  theme_marain() +
  geom_area(colour="black", size=0.3) +
  labs(x = "Hour", y = "GW", fill="load severity") +
  facet_wrap(~panel) +
  scale_fill_manual(values = c(brewer.pal(3, "Blues"), brewer.pal(3, "Reds"))) +
  theme(strip.text = element_text(size=rel(1.2)))
ggsave(pp(plotsDir,'/baseline-xfc-hours-per-site-per-day.png'),p,width=12,height=4,units='in')


## Baseline public charging
toplot[,panel:=revalue(factor(site),c('public'='Public','depot'='Ridehail CAV Depot'))]
p <- toplot[,.(kw=sum(kw)),by=c('loadType','hour.bin2','name')] %>%
  ggplot(aes(x=hour.bin2,y=kw/1e6,fill=factor(loadType, levels = names(chargingTypes.colors))))+
  theme_marain() +
  geom_area(colour="black", size=0.3) +
  scale_fill_manual(values = chargingTypes.colors, name = "") +
  labs(x = "hour", y = "GW", fill="load severity", title="Public Charging") +
  theme(strip.text = element_text(size=rel(1.2)))
ggsave(pp(plotsDir,'/baseline-public-charging.png'),p,width=6,height=4,units='in')


## Baseline ev charging loads by space time
toplot <- all.loads[name==scenarioBaselineLabel&hour.bin2%in%c(0, 6, 12, 18)]
toplot$mw <- toplot$kw/1000
toplot$hour.bin2.label <- "12am"
toplot[hour.bin2==6]$hour.bin2.label <- "6am"
toplot[hour.bin2==12]$hour.bin2.label <- "12pm"
toplot[hour.bin2==18]$hour.bin2.label <- "6pm"
hour.bin2.label_order <- c("12am", "6am", "12pm", "6pm")
counties <- data.table(urbnmapr::counties)[county_name%in%countyNames]
setkey(toplot,xfc)
p <- ggplot() +
  theme_marain() +
  geom_polygon(data=counties, mapping=aes(x=long,y=lat,group=group), fill="white", size=.2) +
  coord_map(projection = 'albers', lat0=39, lat1=45,xlim=c(-122.78,-121.86),ylim=c(37.37,38.17))+
  geom_point(dat=toplot,aes(x=x2,y=y2,size=mw,stroke=0.5,group=grp,color=mw),alpha=.3)+
  scale_color_gradientn(colours=c("darkgrey", "gold", "salmon", "orange", "red"), breaks=c(0.5,1,2,5)) +
  scale_size_continuous(range=c(0.5,35), breaks=c(0.5,1,2,5))+
  #scale_colour_continuous(breaks=c(999,5000,5001), values=c('darkgrey','orange','red'))+
  #scale_size_continuous(range=c(0.5,35), breaks=c(999,5000,5001))+
  labs(title="EV Charging Loads",colour='Load (MW)',size='Load (MW)',x="",y="")+
  theme(panel.background = element_rect(fill = "#d4e6f2"),
        legend.title = element_text(size = 20),
        legend.text = element_text(size = 20),
        axis.text.x = element_blank(),
        axis.text.y = element_blank(),
        axis.ticks.x = element_blank(),
        axis.ticks.y = element_blank()) +
  facet_wrap(~factor(hour.bin2.label, levels=hour.bin2.label_order), ) +
  guides(color= guide_legend(), size=guide_legend())
ggsave(pp(plotsDir,'/baseline-ev-charging-loads-by-space-time.png'),p,width=16,height=8,units='in')


# toplot <- all.loads[name==scenarioBaselineLabel&hour.bin2%in%c(0, 6, 12, 18)]
# toplot[hour.bin2==0]$hour.bin2.label <- "12am"
# toplot[hour.bin2==6]$hour.bin2.label <- "6am"
# toplot[hour.bin2==12]$hour.bin2.label <- "12pm"
# toplot[hour.bin2==18]$hour.bin2.label <- "6pm"
# counties <- data.table(urbnmapr::counties)[county_name%in%countyNames]
# setkey(toplot,xfc)
# p <- ggplot() +
#   theme_marain() +
#   geom_polygon(data=counties, mapping=aes(x=long,y=lat,group=group), fill="white", size=.2) +
#   coord_map(projection = 'albers', lat0=39, lat1=45,xlim=c(-122.78,-121.86),ylim=c(37.37,38.17))+
#   geom_point(dat=toplot,aes(x=x2,y=y2,size=kw,stroke=0.5,group=grp,colour=factor(extreme.lab, levels=extreme_lab_order)),alpha=.3)+
#   scale_colour_manual(values=c('darkgrey','orange','red'))+
#   scale_size_continuous(range=c(0.5,35), breaks=c(1000,5000,10000))+
#   labs(title="EV Charging Loads",colour='Load Intensity (MW)',size='Charging Site Power (kW)')+
#   theme(panel.background = element_rect(fill = "#d4e6f2"),
#         legend.title = element_text(size = 20),
#         legend.text = element_text(size = 20)) +
#   facet_wrap(~hour.bin2.label)
# ggsave(pp(plotsDir,'/baseline-ev-charging-loads-by-space-time.png'),p,width=16,height=8,units='in')


## temp
source("~/Documents/Workspace/scripts/common/keys.R")
register_google(key = google_api_key_1)
oakland_map <- ggmap::get_googlemap("oakland california", zoom = 14, maptype = "roadmap")

# Plot it
ggmap(oakland_map) +
  theme_void() +
  ggtitle("terrain") +
  theme(
    plot.title = element_text(colour = "orange"),
    panel.border = element_rect(colour = "grey", fill=NA, size=2)
  )
toplot <- all.loads[name==scenarioBaselineLabel&hour.bin2 %in% c(6, 9, 18, 0)]
toplot$hour.bin2.label <- "12am"
toplot[hour.bin2==6]$hour.bin2.label <- "6am"
toplot[hour.bin2==9]$hour.bin2.label <- "9am"
toplot[hour.bin2==18]$hour.bin2.label <- "6pm"
counties <- data.table(urbnmapr::counties)[county_name%in%countyNames]
setkey(toplot,xfc)
p <- ggmap(oakland_map) +
  theme_marain() +
  geom_polygon(data = counties, mapping = aes(x = long, y = lat, group = group), fill="white", size=.2) +
  coord_map(projection = 'albers', lat0 = 39, lat1 = 45,xlim=c(-122.2890,-122.2447),ylim=c(37.7915,37.8170))+
  geom_point(dat=toplot[hour.bin2 %in% c(6, 9, 18, 0)],aes(x=x2,y=y2,size=kw,stroke=0.5,group=grp,colour=factor(extreme.lab, levels=extreme_lab_order)),alpha=.3)+
  scale_colour_manual(values=c('darkgrey','orange','red'))+
  scale_size_continuous(range=c(0.5,35),breaks=c(500,1000,2000,4000))+
  labs(title="EV Charging Loads in Downtown Oakland",colour='Load Severity',size='Charging Site Power (kW)')+
  theme(panel.background = element_rect(fill = "#d4e6f2")) +
  facet_wrap(~hour.bin2.label)
ggsave(pp(plotsDir,'/baseline-ev-charging-loads-by-space-time-in-oakland.png'),p,width=16,height=8,units='in')


## **************************************
##  public charging by scenario
p <- all.loads[site=='public'&name%in%scenarioNames][,.(kw=sum(kw)),by=c('loadType','hour.bin2','name')] %>%
  ggplot(aes(x=hour.bin2,y=kw/1e6,fill=factor(loadType, levels = names(chargingTypes.colors))))+
  theme_marain() +
  geom_area(colour="black", size=0.3) +
  scale_fill_manual(values = chargingTypes.colors, name = "") +
  labs(x = "hour", y = "GW", fill="load severity", title="Public Charging") +
  theme(strip.text = element_text(size=rel(1.2))) +
  facet_wrap(~factor(name,scenarioNames),ncol = 3)
ggsave(pp(plotsDir,'/public-charging-by-scenario.png'),p,width=12,height=7,units='in')


## public  daily charging by scenario
toplot <- join.on(all.loads[site=='public'&name%in%scenarioNames][,.(kw=sum(kw)),by=c('loadType','name')],all.loads[site=='public'&name%in%scenarioNames][,.(tot.kw=sum(kw)),by=c('name')],'name','name')
p <- ggplot(toplot,aes(x=factor(name,scenarioNames),y=kw/tot.kw*100,fill=factor(loadType, levels = names(chargingTypes.colors))))+
  theme_marain() +
  geom_bar(stat="identity",colour='black',size=0.3) +
  scale_fill_manual(values = chargingTypes.colors, name = "") +
  labs(x = "", y = "Share of Charging (%)", fill="load severity", title="Public Charging") +
  theme(axis.text.x = element_text(angle = 0, hjust=0.5), strip.text = element_text(size=rel(1.2)))
  #theme(axis.text.x = element_text(angle = 30, hjust=1), strip.text = element_text(size=rel(1.2)))
ggsave(pp(plotsDir,'/public-daily-charging-by-scenario.png'),p,width=3,height=3,units='in')



## XFC loads by scenario
toplot <- all.loads[name%in%scenarioNames,.(kw=sum(kw)),by=c('severity','hour.bin2','name')][!is.na(kw)]
toplot <- join.on(data.table(expand.grid(list(name=u(toplot$name),severity=u(toplot$severity),hour.bin2=u(toplot$hour.bin2)))),toplot,c('severity','hour.bin2','name'),c('severity','hour.bin2','name'))
toplot[is.na(kw),kw:=0]
setkey(toplot,name,severity,hour.bin2)
p <- ggplot(toplot,aes(x=hour.bin2,y=kw/1e6,fill=factor(severity, levels=severity_order)))+
  theme_marain() +
  geom_area(colour="black", size=0.3) +
  scale_fill_manual(values = c(brewer.pal(3, "Blues"), brewer.pal(3, "Reds"))) +
  labs(x = "hour", y = "GW", fill="Load Severity") +
  theme(strip.text = element_text(size=rel(1.2))) +
  facet_wrap(~factor(name,scenarioNames),ncol = 3)
ggsave(pp(plotsDir,'/xfc-loads-by-scenario.png'),p,width=12,height=7,units='in')



## Energy charged by scenario
metrics <- all.loads[!is.na(kw)&name%in%scenarioNames][,.(gw=sum(kw)/1e6,gwh=sum(kw)/4e6),by=.(name,hour.bin2,severity)][,.(gw.peak=max(gw),gwh=sum(gwh)),by=.(name,severity)]
xfc.metric <- all.loads[!is.na(kw)&name%in%scenarioNames][!grepl('<1MW',severity),.(xfc.hours=.N/4),by=.(name,type,severity,taz)][,.(xfc.hours=mean(xfc.hours)),by=.(name,type,severity)]

toplot <- melt(metrics,id.vars=c('name','severity'))
toplot[name%in%scenarioNames,panel:=revalue(factor(variable),c('gw.peak'='Regional Charging Peak (GW)','gwh'='Total Energy Charged (GWh)'))]
p <- ggplot(toplot,aes(x=factor(name,scenarioNames),y=value,fill=factor(severity, levels=severity_order)))+
  geom_bar(stat='identity')+
  facet_wrap(~panel,scales='free_y')+
  labs(y='',x='Scenario',fill='Severity')+
  theme_marain()+
  scale_fill_manual(values = c(brewer.pal(3, "Blues"), brewer.pal(3, "Reds"))) +
  theme(axis.text.x = element_text(angle=0, hjust=0.5), strip.text = element_text(size=rel(1.2)))
  #theme(axis.text.x = element_text(angle = 30, hjust=1), strip.text = element_text(size=rel(1.2)))
ggsave(pp(plotsDir,'/energy-charged-by-scenario.png'),p,width=8,height=3,units='in')



## XFC hours per site per day
xfc.metric[,panel:='XFC-Hours per Site per Day']
p <- ggplot(xfc.metric,aes(x=factor(name,scenarioNames),y=xfc.hours,fill=factor(severity, levels=severity_order)))+
  geom_bar(stat='identity',position='dodge')+
  facet_wrap(~panel,scales='free_y')+
  labs(y='',x='Scenario',fill='Severity')+
  theme_marain()+
  scale_fill_manual(values = c(brewer.pal(3, "Blues")[c(1,3)], brewer.pal(3, "Reds")[c(1,3)])) +
  theme(axis.text.x = element_text(angle=0, hjust=0.5),strip.text = element_text(size=rel(1.2)))
  #theme(axis.text.x = element_text(angle = 30, hjust=1),strip.text = element_text(size=rel(1.2)))
ggsave(pp(plotsDir,'/xfc-hours-per-site-per-day.png'),p,width=5,height=3,units='in')



##########################################
# MOBILITY
##########################################

factor.remap <- c('walk'='Walk','bike'='Bike','rh'='Ridehail Solo','rhp'='Ridehail Pooled','rh_empty'='Ridehail (Empty)','cav'='Personal AV','cav_empty'='Personal AV (Empty)','car'='Car','transit'='Public Transit')
factor.colors <- c('walk'='#669900','bike'='#FFB164','rh'='#B30C0C','rhp'='#660099','rh_empty'=marain.light.grey,'cav'='#FFE664','cav_empty'=marain.dark.grey,'car'='#8A8A8A','transit'='#0066CC')
factor.colors.remapped <- factor.colors
names(factor.colors.remapped) <- factor.remap[names(factor.colors)]
mode <- data.table(read.csv(pp(mobilityDir,'/mode-split.csv'),row.names=NULL))
mode[,type:='Modal Shares (%)']
mode2 <- data.table(read.csv(pp(mobilityDir,'/vmt.csv'),row.names=NULL))
mode2[,type:='Daily Light-Duty VMT (10^6 miles)']
mode <- rbindlist(list(mode,mode2),fill=T)
mode[,X:=NULL]
mode <- melt(mode,id.vars=c('scenario','technology','label','type'))
mode <- mode[!variable%in%c('cav_shared','nm')]
mode <- mode[,variable:=as.character(variable)]
mode[type=='Modal Shares (%)',value:=value*100]
mode[,color:=factor(factor.colors[variable])]
mode[,variable:=factor(revalue(factor(variable),factor.remap),levels=factor.remap)]
mode[,label:=factor(label,scenarioNames)]
mode <- as.data.table(mode)
p <- ggplot(mode[label %in% scenarioNames],aes(x=label,y=value,fill=variable))+
  geom_bar(stat='identity')+
  labs(y='',x='Scenario',fill='Mode',title='Mobility Metrics')+
  theme_marain()+
  facet_wrap(~type,scales='free_y')+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),strip.text = element_text(size=rel(1.2)))+
  #theme(axis.text.x = element_text(angle = 30, hjust=1),strip.text = element_text(size=rel(1.2)))+
  scale_fill_manual(values = factor.colors.remapped)
ggsave(pp(plotsDir,'/metric-mobility.png'),p,width=8,height=3,units='in')
# ******************************************************


factor.colors <- c('diesel'=marain.dark.grey,'gas'='#8A8A8A','electricity'='#0066CC')
factor.remap <- c('diesel'='Diesel','gas'='Gasoline','electricity'='Electricity')
fuel <- data.table(read.csv(pp(mobilityDir,'/fuel-percapita.csv'),row.names=NULL))
fuel[,X:=NULL]
fuel <- melt(fuel,id.vars=c('technology','label'))
fuel[,color:=factor.colors[variable]]
fuel[,variable:=factor(revalue(factor(variable),factor.remap),levels=factor.remap)]
fuel[,type:='Light Duty Vehicle Energy per Capita (kWh)']
fuel[label=='High Fast Chargerh',label:='High Fast Charger']
fuel[,label:=factor(label,scenarioNames)]
p <- ggplot(fuel[label%in%scenarioNames],aes(x=label,y=value*100,fill=variable))+
  geom_bar(stat='identity')+
  labs(y='',x='Scenario',fill='Fuel Type',title='System Energy Consumption')+
  theme_marain()+
  facet_wrap(~type,scales='free_y')+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5), strip.text = element_text(size=rel(1.2)))+
  #theme(axis.text.x = element_text(angle = 30, hjust=1), strip.text = element_text(size=rel(1.2)))+
  scale_fill_manual(values = u(fuel$color))
ggsave(pp(plotsDir,'/metric-fuel.png'),p,width=6,height=3,units='in')


