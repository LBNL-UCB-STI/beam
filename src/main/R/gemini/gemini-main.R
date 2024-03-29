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
severity_order <- c("Public <1MW", "Public 1-5MW", "Public >5MW", "Ridehail Depot <1MW", "Ridehail Depot 1-5MW", "Ridehail Depot >5MW")
extreme_lab_order <- c("<1MW", "1-5MW", ">5MW")

dataDir <- normalizePath("~/Workspace/Data/GEMINI/2021-22-Oakland/BATCH3Bis")
#events <- readCsv(pp(dataDir, "/events/0.events.BASE.csv.gz"))
#eventsDir <- paste(dataDir, "/events",sep="")
resultsDir <- paste(dataDir, "/results",sep="")
plotsDir <- paste(dataDir, "/plots",sep="")
mobilityDir <- paste(dataDir, "/mobility",sep="")
dir.create(resultsDir, showWarnings = FALSE)
dir.create(plotsDir, showWarnings = FALSE)

# MAIN
processEventsFileAndScaleUp(dataDir, scaleup, expFactor)

countyNames <- c('Alameda County','Contra Costa County','Marin County',
                 'Napa County','Santa Clara County','San Francisco County',
                 'San Mateo County','Sonoma County','Solano County')
# PLOTS
if (!file.exists(pp(resultsDir,'/ready-to-plot.Rdata'))) {
  generateReadyToPlot(resultsDir, loadTypes, countyNames)
}

## Energy Share Per Load Type
load(file = pp(resultsDir,'/ready-to-plot.Rdata'))
publicLoads <- all.loads[site=='public',.(fuel=sum(fuel)),by=.(code)]
publicLoads.ByChargeType <- all.loads[site=='public',.(fuel=sum(fuel)),by=.(loadType,code)]
for(j in 1:nrow(publicLoads)){
  temp <- publicLoads.ByChargeType[code==publicLoads[j,]$code]
  temp$fuelShare <- temp$fuel/publicLoads[j,]$fuel
  print(publicLoads[j,]$code)
  charging <- temp[,.(loadType,fuelShare)][order(factor(loadType,levels=names(chargingTypes.colors)))]
  print(charging)
  print(pp("FC: ", charging[loadType=="XFC"]$fuelShare+charging[loadType=="DCFC"]$fuelShare))
  print(pp("Home: ", charging[loadType=="Home-L1"]$fuelShare+charging[loadType=="Home-L2"]$fuelShare))
  print(pp("tot fuel: ", sum(temp$fuel)))
}
scens <- as.data.table(readCsv(pp(resultsDir,'/../scenarios.csv')))
all.loads <- as.data.table(all.loads[scens, on="code", mult="all"])
sum(all.loads[code=="7Advanced"][chargingPointType=="depotxfc(300.0|DC)"]$fuel)/3.6e+12
# let’s squeeze a short date some time this weeklet’s squeeze a short date some time this week
#####
# scenarioNames <- c('Scenario2', 'Scenario2-010', 'Scenario2-025', 'Scenario2-050')
# scenarioNames <- c('Scenario4', 'Scenario4Bis', 'Scenario4Bis2', 'Scenario4Bis3', 'Scenario4Bis4', 'Scenario4Bis5')
# scenarioNames <- c('Scenario4a-Base', 'Scenario4b-Base', 'Scenario6-HighEV')
# scenarioNames <- c('5b1', '5b2', '5b3', '5b4', '5b5', '5b6', '5b7')
# scenarioNames <- c('5b1', '5b2', '5b3', '5b4', '5b5')
# scenarioNames <- c('5b1', '5b2')
# scenarioNames <- c('5b3', '5b4', '5b5', '5b6', '5b7')

#scenarioNames <- c('BaseXFC', 'HighEV', 'Advanced')
#scenarioNames <- c('BaseXFC', 'HighEV', 'Advanced', 'MaxEV')
#scenarioNames <- c('BaseXFC', 'HighEV', '5b1', '5b2', '5b3')
#scenarioNames <- c('5b1', '5b2', '5b3')
scenarioNames <- c('Scenario2', 'Scenario3')

#scenarioBaselineLabel <- 'BaseXFC'
scenarioBaselineLabel <- 'Scenario2'
#all.loads <- all.loads[!is.na(loadType)]
##########################################
# LOADS & ENERGY
##########################################

## **************************************
##  public charging by scenario
#scenarioNames <- c('5b1', '5b2', '5b3', '5b4', '5b5', '5b6', '5b7')
#scenarioNames <- c('5b1', '5b2', '5b3', '5b4', '5b5', '5b6', '5b7')
p <- all.loads[name%in%scenarioNames][,.(kw=sum(kw)),by=c('loadType','hour.bin2','name')] %>%
  ggplot(aes(x=hour.bin2,y=kw/1e6,fill=factor(loadType, levels = names(chargingTypes.colors))))+
  theme_marain() +
  geom_area(colour="black", size=0.3) +
  scale_fill_manual(values = chargingTypes.colors, name = "") +
  labs(x = "hour", y = "GW", fill="load severity", title="Public Charging") +
  theme(strip.text = element_text(size=rel(1.2)), axis.text = element_text(size = 16)) +
  facet_wrap(~factor(name,scenarioNames),ncol = 2)
ggsave(pp(plotsDir,'/public-charging-by-scenario-2.png'),p,width=8,height=4,units='in')

###############################
## Baseline XFC hours per site per day
#scenarioBaselineLabel <- 'BaseXFC'
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
ggsave(pp(plotsDir,'/baseline-xfc-hours-per-site-per-day.png'),p,width=6,height=4,units='in')

## Baseline public charging
#scenarioBaselineLabel <- 'HighEV'
title_label <- paste("Public Charging - ", scenarioBaselineLabel, sep="")
file_name <- paste('/baseline-public-charging-', scenarioBaselineLabel, ".png", sep="")
toplot <- all.loads[name==scenarioBaselineLabel]
toplot[,panel:=revalue(factor(site),c('public'='Public','depot'='Ridehail CAV Depot'))]
p <- toplot[,.(kw=sum(kw)),by=c('loadType','hour.bin2','name')] %>%
  ggplot(aes(x=hour.bin2,y=kw/1e6,fill=factor(loadType, levels = names(chargingTypes.colors))))+
  theme_marain() +
  geom_area(colour="black", size=0.3) +
  scale_fill_manual(values = chargingTypes.colors, name = "") +
  labs(x = "hour", y = "GW", fill="load severity", title=title_label) +
  theme(strip.text = element_text(size=rel(1.2)))
ggsave(pp(plotsDir,file_name),p,width=6,height=4,units='in')


## Baseline ev charging loads by space time
#scenarioBaselineLabel <- 'BaseXFC'
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
  facet_wrap(~factor(hour.bin2.label, levels=hour.bin2.label_order)) +
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
source("~/Workspace/Models/scripts/common/keys.R")
register_google(key = google_api_key_1)
oakland_map <- ggmap::get_googlemap("oakland california", zoom = 14, maptype = "roadmap")

# Plot it
hours_to_show <- c(0, 8, 12, 18)
toplot <- all.loads[name==scenarioBaselineLabel&hour.bin2 %in% hours_to_show]
toplot$hour.bin2.label <- "12am"
toplot[hour.bin2==8]$hour.bin2.label <- "8am"
toplot[hour.bin2==12]$hour.bin2.label <- "12pm"
toplot[hour.bin2==18]$hour.bin2.label <- "6pm"
counties <- data.table(urbnmapr::counties)[county_name%in%countyNames]
setkey(toplot,xfc)
p <- ggmap(oakland_map) +
  theme_marain() +
  geom_polygon(data = counties, mapping = aes(x = long, y = lat, group = group), fill="white", size=.2) +
  coord_map(projection = 'albers', lat0 = 39, lat1 = 45,xlim=c(-122.2890,-122.2447),ylim=c(37.7915,37.8170))+
  geom_point(dat=toplot[hour.bin2 %in% hours_to_show],aes(x=x2,y=y2,size=kw,stroke=0.5,group=grp,colour=factor(extreme.lab, levels=extreme_lab_order)),alpha=.3)+
  scale_colour_manual(values=c('darkgrey','orange','red'))+
  scale_size_continuous(range=c(0.5,35),breaks=c(500,1000,2000,4000))+
  labs(title="EV Charging Loads in Downtown Oakland",colour='Load Severity',size='Charging Site Power (kW)')+
  theme(panel.background = element_rect(fill = "#d4e6f2"),
        legend.title = element_text(size = 20),
        legend.text = element_text(size = 20),
        axis.text.x = element_blank(),
        axis.text.y = element_blank(),
        axis.ticks.x = element_blank(),
        axis.ticks.y = element_blank(),
        axis.text = element_text(size = 16))+
  facet_wrap(~hour.bin2.label) +
  guides(color= guide_legend(), size=guide_legend())
ggsave(pp(plotsDir,'/baseline-ev-charging-loads-by-space-time-in-oakland.png'),p,width=16,height=8,units='in')


## **************************************
##  public charging by scenario
# thelabeller <- c("Scenario2" = "Scenario2 (100% Population)", "Scenario2-010" = "Scenario2 (10% sample)", "Scenario2-025" = "Scenario2 (25% sample)", "Scenario2-050" = "Scenario2 (50% sample)")
# p <- all.loads[region=="Oakland-Alameda"&site=='public'&name%in%scenarioNames][,.(kw=sum(kw)),by=c('loadType','hour.bin2','name')] %>%
#   ggplot(aes(x=hour.bin2,y=kw/1e6,fill=factor(loadType, levels = names(chargingTypes.colors))))+
#   theme_marain() +
#   geom_area(colour="black", size=0.3) +
#   scale_fill_manual(values = chargingTypes.colors, name = "") +
#   labs(x = "hour", y = "GW", fill="load severity", title="Public Charging") +
#   theme(strip.text = element_text(size=rel(1.2))) +
#   facet_wrap(~factor(name,scenarioNames),ncol = 2,labeller = labeller(.cols = thelabeller))
# ggsave(pp(plotsDir,'/public-charging-by-scenario.png'),p,width=8,height=5,units='in')




## public  daily charging by scenario
toplot <- join.on(all.loads[site=='public'&name%in%scenarioNames][,.(kw=sum(kw)),by=c('loadType','name')],all.loads[site=='public'&name%in%scenarioNames][,.(tot.kw=sum(kw)),by=c('name')],'name','name')
p <- ggplot(toplot,aes(x=factor(name,scenarioNames),y=kw/tot.kw*100,fill=factor(loadType, levels = names(chargingTypes.colors))))+
  theme_marain() +
  geom_bar(stat="identity",colour='black',size=0.3) +
  scale_fill_manual(values = chargingTypes.colors, name = "") +
  labs(x = "", y = "Share of Charging (%)", fill="load severity", title="Public Charging") +
  theme(axis.text.x = element_text(angle = 0, hjust=0.5), strip.text = element_text(size=rel(1.2)))
  #theme(axis.text.x = element_text(angle = 30, hjust=1), strip.text = element_text(size=rel(1.2)))
ggsave(pp(plotsDir,'/public-daily-charging-by-scenario.png'),p,width=4,height=3,units='in')



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
  theme(strip.text = element_text(size=rel(1.2)), axis.text.x = element_text(angle = 0, hjust=0.5)) +
  facet_wrap(~factor(name,scenarioNames),ncol = 3)
ggsave(pp(plotsDir,'/xfc-loads-by-scenario.png'),p,width=12,height=5,units='in')



## Energy charged by scenario
metrics <- all.loads[!is.na(kw)&name%in%scenarioNames][,.(gw=sum(kw)/1e6,gwh=sum(kw)/4e6),by=.(name,hour.bin2,severity)][,.(gw.peak=max(gw),gwh=sum(gwh)),by=.(name,severity)]
toplot <- melt(metrics,id.vars=c('name','severity'))
toplot[name%in%scenarioNames,panel:=revalue(factor(variable),c('gw.peak'='Regional Charging Peak (GW)','gwh'='Total Energy Charged (GWh)'))]
p <- ggplot(toplot,aes(x=factor(name,scenarioNames),y=value,fill=factor(severity,levels=severity_order)))+
  geom_bar(stat='identity')+
  facet_wrap(~panel,scales='free_y')+
  labs(y='',x='Scenario',fill='Severity')+
  theme_marain()+
  scale_fill_manual(values = c(brewer.pal(3, "Blues"), brewer.pal(3, "Reds"))) +
  theme(axis.text.x = element_text(angle=0, hjust=0.5), strip.text = element_text(size=rel(1.2)))
  #theme(axis.text.x = element_text(angle = 30, hjust=1), strip.text = element_text(size=rel(1.2)))
ggsave(pp(plotsDir,'/energy-charged-by-scenario.png'),p,width=8,height=3,units='in')



## XFC hours per site per day
xfc.metric <- all.loads[!is.na(kw)&name%in%scenarioNames][!grepl('<1MW',severity),.(xfc.hours=.N/4),by=.(name,type,severity,taz)][,.(xfc.hours=mean(xfc.hours)),by=.(name,type,severity)]
xfc.metric[,panel:='XFC-Hours per Site per Day']
p <- ggplot(xfc.metric,aes(x=factor(name,scenarioNames),y=xfc.hours,fill=factor(severity, levels=severity_order)))+
  geom_bar(stat='identity',position='dodge')+
  facet_wrap(~panel,scales='free_y')+
  labs(y='',x='Scenario',fill='Severity')+
  theme_marain()+
  scale_fill_manual(values = c(brewer.pal(3, "Blues")[c(2,3)], brewer.pal(3, "Reds")[c(2,3)])) +
  theme(axis.text = element_text(size = 16), 
        strip.text = element_text(size=rel(1.2)),
        legend.title = element_text(size = 12),
        legend.text = element_text(size = 12))
  #theme(axis.text.x = element_text(angle = 30, hjust=1),strip.text = element_text(size=rel(1.2)))
ggsave(pp(plotsDir,'/xfc-hours-per-site-per-day.png'),p,width=5,height=4,units='in')



##########################################
# MOBILITY
##########################################

# events <- readCsv(paste(dataDir, "/events-raw", "/0.events.SC2.csv.gz", sep=""))
# pt <- events[type=="PathTraversal"]
# pt2 <- pt[,c("time","type","vehicleType","vehicle","secondaryFuelLevel",
#              "primaryFuelLevel","driver","mode","seatingCapacity","startX",
#              "startY", "endX", "endY", "capacity", "arrivalTime", "departureTime",
#              "secondaryFuel", "secondaryFuelType", "primaryFuelType",
#              "numPassengers", "length", "primaryFuel")]
# pt2$name <- 'Scenario2'
# pt2$mode2 <- "Transit"
# pt2[mode=="car"]$mode2 <- "Car"
# pt2[mode=="car"&startsWith(vehicle,"rideHailVehicle")]$mode2 <- "Ridehail"
# pt2[mode=="walk"]$mode2 <- "Walk"
# pt2[mode=="bike"]$mode2 <- "Bike"
# write.csv(
#   pt2,
#   file = paste(dataDir, "/events-path", "/path.0.events.SC2.csv.gz", sep=""),
#   row.names=FALSE,
#   quote=FALSE,
#   na="0")
pt2 <- readCsv(paste(dataDir, "/events-path", "/path.0.events.SC2.csv.gz", sep=""))
pt3 <- readCsv(paste(dataDir, "/events-path", "/path.0.events.SC3.csv.gz", sep=""))
pt3$name <- 'Scenario3'
pt <- rbind(pt2, pt3)
pt$fuelType <- "Diesel"
pt[startsWith(vehicleType,"conv-")]$fuelType <- "Gasoline"
pt[startsWith(vehicleType,"hev-")]$fuelType <- "Gasoline"
pt[startsWith(vehicleType,"ev-")]$fuelType <- "Electric"
pt[startsWith(vehicleType,"phev-")]$fuelType <- "Electric"



summary <- pt[mode2%in%c("Car","Ridehail","Transit"),.(VMT=1e-6*sum(length)/1609.34,energy=(sum(primaryFuel+secondaryFuel))*2.77778e-13),by=.(fuelType,name)]
# factor.remap <- c('Walk'='Walk','Bike'='Bike','Ridehail'='Ridehail','Car'='Car','Transit'='Public Transit')
# factor.colors <- c('Walk'='#669900','Bike'='#FFB164','Ridehail'='#B30C0C','Car'='#8A8A8A','Transit'='#0066CC')
factor.colors <- c('Diesel'=marain.dark.grey,'Gasoline'='#8A8A8A','Electric'='#8A8A8A')
factor.remap <- c('Diesel'='Diesel','Gasoline'='Gasoline','Electric'='Electricity')
factor.colors.remapped <- factor.colors
names(factor.colors.remapped) <- factor.remap[names(factor.colors)]
p <- summary[fuelType=="Electric"] %>% ggplot(aes(x=name,y=energy,fill=fuelType))+
  geom_bar(stat='identity')+
  labs(y='',x='Scenario',fill='Mode',title='Mobility Metrics')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),strip.text = element_text(size=rel(1.2)))+
  scale_fill_manual(values = factor.colors.remapped)
ggsave(pp(plotsDir,'/metric-mobility.png'),p,width=8,height=3,units='in')


#################################################
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


