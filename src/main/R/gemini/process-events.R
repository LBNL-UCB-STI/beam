setwd(dirname(rstudioapi::getSourceEditorContext()$path))
source("/Users/haitamlaarabi/Documents/Workspace/scripts/helpers.R")
source("/Users/haitamlaarabi/Documents/Workspace/scripts/theme_marain.R")
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

scaleup <- TRUE
expFactor <- (7.75/0.315) * 27.0 / 21.3
loadInfo <- new("loadInfo", timebinInSec=900, siteXFCInKW=1000, plugXFCInKW=250)
severity_order <- c("Public <1MW", "Public 1-5MW", "Public >5MW", "Ridehail Depot <1MW", "Ridehail Depot 1-5MW", "Ridehail Depot >5MW") 
extreme_lab_order <- c("<1MW", "1-5MW", ">5MW")

dataDir <- "/Users/haitamlaarabi/Data/GEMINI/2021March22/370k-warmstart"
#events <- readCsv(pp(dataDir, "/events/0.events.BASE.csv.gz"))
#eventsDir <- paste(dataDir, "/events",sep="")
resultsDir <- paste(dataDir, "/results",sep="")
plotsDir <- paste(dataDir, "/plots",sep="")
dir.create(resultsDir, showWarnings = FALSE)
dir.create(plotsDir, showWarnings = FALSE)

scenarioNames <- c('Baseline')
countyNames <- c('Alameda County','Contra Costa County','Marin County','Napa County','Santa Clara County','San Francisco County','San Mateo County','Sonoma County','Solano County')
loadTypes <- data.table::data.table(
  chargingType = c("evi_public_dcfast(150.0|DC)", "evi_public_dcfast(250.0|DC)", "evi_public_dcfast(50.0|DC)", "fcs_fast(50.0|DC)", "fcs_fast(150.0|DC)", "fcs_fast(250.0|DC)", "evi_public_level2(7.2|AC)", "evi_work_level2(7.2|AC)", "homelevel1(1.8|AC)", "homelevel2(7.2|AC)"),
  loadType = c("DCFC", "XFC", "DCFC", "DCFC", "DCFC", "XFC", "Public-L2", "Work-L2", "Home-L1", "Home-L2")
)

processEventsFileAndScaleUp(dataDir, scaleup, expFactor)

if (!file.exists(pp(resultsDir,'/ready-to-plot.Rdata'))) {
  generateReadyToPlot(resultsDir, loadTypes, loadInfo, countyNames)
}

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
all.loads <- all.loads[scens, on="code", mult="all"]


toplot <- all.loads[name=='Baseline']
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



toplot[,panel:=revalue(factor(site),c('public'='Public','depot'='Ridehail CAV Depot'))]
p <- toplot[,.(kw=sum(kw)),by=c('loadType','hour.bin2','name')] %>%
  ggplot(aes(x=hour.bin2,y=kw/1e6,fill=factor(loadType, levels = names(chargingTypes.colors))))+
  theme_marain() +
  geom_area(colour="black", size=0.3) +
  scale_fill_manual(values = chargingTypes.colors, name = "") +
  labs(x = "hour", y = "GW", fill="load severity", title="Public Charging") + 
  theme(strip.text = element_text(size=rel(1.2)))
ggsave(pp(plotsDir,'/baseline-public-charging.png'),p,width=6,height=4,units='in')



toplot <- all.loads[name=='Baseline'&hour.bin2 %in% c(6, 9, 18, 0)]
toplot$hour.bin2.label <- "12am"
toplot[hour.bin2==6]$hour.bin2.label <- "6am"
toplot[hour.bin2==9]$hour.bin2.label <- "9am"
toplot[hour.bin2==18]$hour.bin2.label <- "6pm"
counties <- data.table(urbnmapr::counties)[county_name%in%countyNames]
setkey(toplot,xfc)
p <- ggplot() +
  theme_marain() +
  geom_polygon(data = counties, mapping = aes(x = long, y = lat, group = group), fill="white", size=.2) +
  coord_map(projection = 'albers', lat0 = 39, lat1 = 45,xlim=c(-122.78,-121.86),ylim=c(37.37,38.17))+
  geom_point(dat=toplot[hour.bin2 %in% c(6, 9, 18, 0)],aes(x=x2,y=y2,size=kw,stroke=0.5,group=grp,colour=factor(extreme.lab, levels=extreme_lab_order)),alpha=.3)+
  scale_colour_manual(values=c('darkgrey','orange','red'))+
  scale_size_continuous(range=c(0.5,35),breaks=c(500,1000,2000,4000))+
  labs(title="EV Charging Loads",colour='Load Severity',size='Charging Site Power (kW)')+
  theme(panel.background = element_rect(fill = "#d4e6f2")) +
  facet_wrap(~hour.bin2.label)
ggsave(pp(plotsDir,'/baseline-ev-charging-loads-by-space-time.png'),p,width=14,height=10,units='in')







