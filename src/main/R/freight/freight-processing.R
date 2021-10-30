setwd(dirname(rstudioapi::getSourceEditorContext()$path))
source("../common/helpers.R")
source("../common/theme.R")
library('colinmisc')
library(dplyr)
library(ggplot2)
library(rapport)
library(sjmisc)
library(ggmap)
library(sf)
library(stringr)

workDir <- normalizePath("~/Data/SMART")
activitySimDir <- normalizePath("~/Data/ACTIVITYSIM")

events <- readCsv(pp(workDir, "/5.events.csv.gz"))
unloading <- events[actType=="Unloading"]
nrow(unloading[type=="actstart"])
nrow(unloading[type=="actend"])
warehouse <- events[actType=="Warehouse"]
nrow(warehouse[type=="actstart"])
nrow(warehouse[type=="actend"])
pt <- events[type=="PathTraversal"][,c("time","type","vehicleType","vehicle","secondaryFuelLevel",
                                       "primaryFuelLevel","driver","mode","seatingCapacity","startX",
                                       "startY", "endX", "endY", "capacity", "arrivalTime", "departureTime",
                                       "secondaryFuel", "secondaryFuelType", "primaryFuelType",
                                       "numPassengers", "length", "primaryFuel")]
freight_pt <- pt[startsWith(vehicle,"freight")]
nrow(freight_pt)
# pt$mode2 <- "Transit"
# pt[mode=="car"]$mode2 <- "Car"
# pt[mode=="car"&startsWith(vehicle,"rideHailVehicle")]$mode2 <- "Ridehail"
# pt[mode=="car"&startsWith(vehicle,"freight")]$mode2 <- "Freight"
# pt[mode=="walk"]$mode2 <- "Walk"
# pt[mode=="bike"]$mode2 <- "Bike"
# summary <- pt[,.(VMTInMiles=mean(length)/1609.34,fuelInKW=(mean(primaryFuel+secondaryFuel))*2.77778e-7),by=.(mode2)]
# 


factor.remap <- c('Walk'='Walk','Bike'='Bike','Ridehail'='Ridehail','Car'='Car','Transit'='Public Transit', 'Freight'='Freight')
factor.colors <- c('Walk'='#669900','Bike'='#FFB164','Ridehail'='#B30C0C','Car'='#8A8A8A','Transit'='#0066CC','Freight'="#660099")
factor.colors.remapped <- factor.colors
ggplot(summary, aes(x="",y=VMTInMiles,fill=mode2))+
  geom_bar(stat='identity')+
  labs(y='',x='Scenario',fill='Mode',title='Mobility Metrics')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),strip.text = element_text(size=rel(1.2)))+
  scale_fill_manual(values = factor.colors.remapped)


source("~/Documents/Workspace/scripts/common/keys.R")
register_google(key = google_api_key_1)
oaklandMap <- ggmap::get_googlemap("oakland california", zoom = 13, maptype = "roadmap")
shpFile <- pp(activitySimDir, "/__San_Francisco_Bay_Region_2010_Census_Block_Groups-shp/641aa0d4-ce5b-4a81-9c30-8790c4ab8cfb202047-1-wkkklf.j5ouj.shp")
sfBayCbg <- st_read(shpFile)
# ggplot(data = oaklandCbg) + geom_sf()+
#   coord_sf( xlim = c(-130, -60),ylim = c(20, 50))



freight_pt[,hour:=as.integer(arrivalTime/3600)%%24]
##1
# freight_pt_withCBG <- data.table::as.data.table(st_intersection(freight_pt_asSf,sfBayCbg))
# freight_pt_summary <- freight_pt_withCBG[,.(count=.N),by=.(blkgrpid)]
# freight_pt_withCBG_asSf <- st_join(sfBayCbg, freight_pt_asSf)
# data <- freight_pt_withCBG_asSf %>%
#   group_by(blkgrpid) %>% 
#   summarize(geometry = st_union(geometry))
# ggplot() + 
#   geom_sf(data = data, aes(fill = blkgrpid)) +
#   theme(legend.position = "none")

##2
#countyNames <- c('Alameda County','Contra Costa County','Marin County','Napa County','Santa Clara County','San Francisco County','San Mateo County','Sonoma County','Solano County')
source("~/Documents/Workspace/scripts/common/keys.R")
register_google(key = google_api_key_1)
oakland_map <- ggmap::get_googlemap("alameda california", zoom = 9, maptype = "roadmap",color = "bw", style = c(feature = "all", element = "labels", visibility = "off"))

clusteringFreightBy <- function(data,cols,dataCbg,numClusters,labelData) {
  data_asSf <- st_as_sf(data,coords=cols,crs=4326,agr="constant")
  data_withCBG_asSf <- st_intersection(data_asSf,dataCbg)
  data_withCBG_asSf$X <- st_coordinates(data_withCBG_asSf$geometry)[,1]
  data_withCBG_asSf$Y <- st_coordinates(data_withCBG_asSf$geometry)[,2]
  data_withCBG <- data.table::as.data.table(data_withCBG_asSf)
  data_withCBG[,cluster:=kmeans(data_withCBG[,.(X,Y)],numClusters)$cluster]
  result <- data_withCBG[,.(count=.N,x2=mean(X),y2=mean(Y)),by=.(hour,cluster)]
  result$label <- labelData
  result
}

b2b_pt <- freight_pt[grepl("b2b",vehicle)][,label:="B2B"]
b2c_pt <- freight_pt[grepl("b2c",vehicle)][,label:="B2C"]

b2b_pt_stops <- clusteringFreightBy(b2b_pt,c("endX","endY"),sfBayCbg,50,"B2B")
b2c_pt_stops <- clusteringFreightBy(b2c_pt,c("endX","endY"),sfBayCbg,50,"B2C")

# Plot it
to_plot <- rbind(b2c_pt_stops)
hours_to_show <- c(8, 12, 16)
toplot <- to_plot[hour %in% hours_to_show]
toplot$hour.label <- ""
toplot[hour==8]$hour.label <- "8am"
toplot[hour==12]$hour.label <- "12pm"
toplot[hour==16]$hour.label <- "4pm"
#toplot[hour==20]$hour.label <- "8pm"
hour.label_order <- c("8am", "12pm", "4pm")
# counties <- data.table(urbnmapr::counties)[county_name%in%countyNames]
#,xlim=c(-122.36,-122.20),ylim=c(37.70,37.81)
p <- oakland_map %>% 
  ggmap() +
  theme_marain() +
  coord_map(projection = 'albers', lat0 = 39, lat1 = 45, xlim=c(-122.78,-121.7),ylim=c(37.21,38.45))+
  geom_point(dat=toplot,aes(x=x2,y=y2,size=count,stroke=0.5),alpha=.5,colour="#20b2aa") +
  scale_size_continuous(name = "#Stops", breaks=c(25,75,125)) +
  labs(title="Hourly B2C",x="",y="")+
  theme(panel.background = element_rect(fill = "#d4e6f2"),
        legend.title = element_text(size = 10),
        legend.text = element_text(size = 10),
        axis.text.x = element_blank(),
        axis.text.y = element_blank(),
        axis.ticks.x = element_blank(),
        axis.ticks.y = element_blank(),
        strip.text.x = element_text(size = 10)) +
  facet_wrap(~factor(hour.label, levels=hour.label_order)) +
  guides(color= guide_legend(), size=guide_legend())
ggsave(pp(workDir,'/b2c-stops.png'),p,width=9,height=5,units='in')


toplot <- rbind(b2b_pt_stops)
p <- oakland_map %>% 
  ggmap() +
  theme_marain() +
  coord_map(projection = 'albers', lat0 = 39, lat1 = 45, xlim=c(-122.78,-121.7),ylim=c(37.21,38.45))+
  geom_point(dat=toplot,aes(x=x2,y=y2,size=count,stroke=0.5,colour=label),alpha=.7,colour="#eca35b") +
  scale_size_continuous(name = "#Stops", breaks=c(2,4,6)) +
  labs(title="Daily B2B",x="",y="")+
  theme(panel.background = element_rect(fill = "#d4e6f2"),
        legend.title = element_text(size = 10),
        legend.text = element_text(size = 10),
        axis.text.x = element_blank(),
        axis.text.y = element_blank(),
        axis.ticks.x = element_blank(),
        axis.ticks.y = element_blank(),
        strip.text.x = element_text(size = 10))
ggsave(pp(workDir,'/b2b-stops.png'),p,width=4,height=5,units='in')


to_plot <- rbind(b2c_pt_stops,b2b_pt_stops)[,.(stopsPerHour=sum(cluster)),by=.(label,hour)]
ggplot(to_plot) + geom_line(aes(hour, stopsPerHour, colour=label))

to_plot <- rbind(b2b_pt,b2c_pt)
to_plot$timeBin <- as.integer(to_plot$time/1800)


to_plot <- rbind(b2b_pt,b2c_pt)
p <- to_plot[,.N,by=.(timeBin=as.POSIXct(cut(toDateTime(arrivalTime),"15 min")), label)] %>% 
  ggplot(aes(timeBin, N, colour=label)) +
  geom_line() + 
  scale_x_datetime("Hour", 
                   breaks=scales::date_breaks("2 hour"), 
                   labels=scales::date_format("%H", tz = dateTZ)) +
  scale_y_continuous("Activity", breaks = scales::pretty_breaks()) +
  scale_colour_manual("Supply Chain", values = c("#eca35b", "#20b2aa")) +
  theme_marain() +
  theme(legend.title = element_text(size = 10),
        legend.text = element_text(size = 10),
        axis.text.x = element_text(angle = 0, hjust = 1))
ggsave(pp(workDir,'/freight-activity.png'),p,width=6,height=3,units='in')


to_plot <- rbind(b2b_pt,b2c_pt)[,.(VMT=mean(length)/1609.3),by=.(label)]
ggplot(to_plot, )

p <- ggplot(to_plot, aes(x=label,y=VMT,fill=label))+
  geom_bar(stat='identity')+
  labs(y='Miles',x='',title='Avg VMT')+
  scale_fill_manual("Supply Chain", values = c("#eca35b", "#20b2aa")) +
  theme_marain()+
  theme(strip.text = element_text(size = 10),
        axis.text.x = element_blank(),
        legend.title = element_text(size = 10),
        legend.text = element_text(size = 10))
ggsave(pp(workDir,'/freight-avg-vmt.png'),p,width=4,height=3,units='in')


to_plot <- rbind(b2b_pt,b2c_pt)[,.(VMT=mean(primaryFuel+secondaryFuel)*2.77778e-7),by=.(label)]

all_pt_x <- data.table::as.data.table(rbind(b2b_pt,b2c_pt)[,c("time","vehicle","departureTime","arrivalTime","label")])
all_pt_1 <- all_pt_x[,-c("arrivalTime")][order(time),`:=`(IDX = 1:.N),by=vehicle]
all_pt_2 <- all_pt_x[,-c("departureTime")][order(time),`:=`(IDX = 1+1:.N),by=vehicle]

all_pt <- all_pt_1[all_pt_2, on=c("vehicle", "IDX", "label")][!is.na(arrivalTime)&!is.na(departureTime)]
all_pt[,`:=`(stopDuration = departureTime - arrivalTime)]
all_pt[,.(mean(stopDuration)),by=.(label)]

all_pt_1 <- all_pt[order(time),`:=`(IDX = 1:.N),by=vehicle]
all_pt_2 <- all_pt[order(time),`:=`(IDX = 1:.N),by=vehicle]

all_pt[,,by=.(vehicle)]

all_pt_x[vehicle=="freight-vehicle-freightVehicle-b2b-1640"]








