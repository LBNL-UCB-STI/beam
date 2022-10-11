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


activitySimDir <- normalizePath("~/Data/ACTIVITYSIM")
freightDir <- normalizePath("~/Data/FREIGHT")
validationDir <- normalizePath("~/Data/FREIGHT/validation")
freightWorkDir <- normalizePath(paste(validationDir,"/beam",sep=""))

# events <- readCsv(pp(freightWorkDir, "/0.events.csv"))
# events_filtered <- events[(actType %in% c("Warehouse", "Unloading", "Loading")) | (type=="PathTraversal" & startsWith(vehicle,"freight"))]
# write.csv(
#   events_filtered,
#   file = pp(freightWorkDir, "/filtered.0.events.csv"),
#   row.names=F,
#   quote=T)
events_filtered <- readCsv(pp(freightWorkDir, "/filtered.0.events.new.csv"))
pt <- events_filtered[type=="PathTraversal"][,c("time","type","vehicleType","vehicle","secondaryFuelLevel",
                                       "primaryFuelLevel","driver","mode","seatingCapacity","startX",
                                       "startY", "endX", "endY", "capacity", "arrivalTime", "departureTime",
                                       "secondaryFuel", "secondaryFuelType", "primaryFuelType",
                                       "numPassengers", "length", "primaryFuel")]
freight_pt <- pt[startsWith(vehicle,"freight")]

if (nrow(freight_pt[grepl("-emergency-",vehicle)]) > 0) {
  println("This is a bug")
}
print(paste("# vehicles: ", length(unique(freight_pt$vehicle))))
print("By category: ")
freight_pt[,.N,by=.(vehicle, vehicleType)][,.(count=.N),by=.(vehicleType)]

# nrow(freight_pt)
# all_pt_x <- data.table::as.data.table(rbind(b2b_pt,b2c_pt)[,c("time","vehicle","departureTime","arrivalTime","label")])
# all_pt_1 <- all_pt_x[,-c("arrivalTime")][order(time),`:=`(IDX = 1:.N),by=vehicle]
# all_pt_2 <- all_pt_x[,-c("departureTime")][order(time),`:=`(IDX = 1+1:.N),by=vehicle]
# all_pt <- all_pt_1[all_pt_2, on=c("vehicle", "IDX", "label")][!is.na(arrivalTime)&!is.na(departureTime)]
# all_pt[,`:=`(stopDuration = departureTime - arrivalTime)]
# all_pt[,.(mean(stopDuration)),by=.(label)]
# unloading <- events[actType=="Unloading"]
# warehouse <- events[actType=="Warehouse"]
# nrow(unloading[type=="actstart"])
# nrow(unloading[type=="actend"])
# nrow(warehouse[type=="actstart"])
# nrow(warehouse[type=="actend"])
##

## ***************************
#FRISM
## ***************************
freightWorkDir <- normalizePath(paste(freightDir,"/Outputs(All SF)_0322_2022_merged",sep=""))
carriers <- readCsv(pp(freightWorkDir, "/freight-merged-carriers.csv"))
payload <- readCsv(pp(freightWorkDir, "/freight-merged-payload-plans.csv"))
tours <- readCsv(pp(freightWorkDir, "/freight-merged-tours.csv"))

print(paste("# carriers:", length(unique(carriers$carrierId))))
print(paste("# tours:", length(unique(carriers$tourId))))
print(paste("# vehicles:", length(unique(carriers$vehicleId))))
print("By category: ")
carriers[,.N,by=.(vehicleId, vehicleTypeId)][,.(count=.N),by=.(vehicleTypeId)]

plans <- rbind(plansb2b,plansb2c)[,c("payloadId","sequenceRank","tourId","payloadType","estimatedTimeOfArrivalInSec","arrivalTimeWindowInSec_lower","business")]
tours <- rbind(toursb2b,toursb2c)[,c("tourId","departureTimeInSec","business")]

p <- plans[tours, on=c("tourId","business")]
pRank2 <- p[sequenceRank==2]
pRank2$negativeTravelTime <- pRank2$estimatedTimeOfArrivalInSec - pRank2$departureTimeInSec - pRank2$arrivalTimeWindowInSec_lower
p2 <- p[,diff:=estimatedTimeOfArrivalInSec-arrivalTimeWindowInSec_lower-departureTimeInSec]

write.csv(
  pRank2[negativeTravelTime<0],
  file = pp(freightDir, "/10percent/negativeTravelTime.csv"),
  row.names=FALSE,
  quote=FALSE,
  na="0")

toursb2$hour <- toursb2c$departureTimeInSec/3600.0
ggplot(toursb2b[,.N,by=hour]) + geom_line(aes(hour, N))

payload$business <- "B2B"
payload[startsWith(payloadId,"b2c")]$business <- "B2C"
payload[,time24:=estimatedTimeOfArrivalInSec%%(24*3600),][,.N,by=.(timeBin=as.POSIXct(cut(toDateTime(time24),"30 min")), business)] %>%
  ggplot(aes(timeBin, N, colour=business)) +
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


payload_carriers <- payload[carriers, on="tourId"]
payload_carriers$vehicleCategory <- "LightDutyTruck"
payload_carriers[vehicleTypeId == "FREIGHT-2"]$vehicleCategory <- "HeavyDutyTruck"
p <- payload_carriers[,time24:=estimatedTimeOfArrivalInSec%%(24*3600),][,.N,by=.(timeBin=as.POSIXct(cut(toDateTime(time24),"30 min")), vehicleCategory)] %>%
  ggplot(aes(timeBin, N, colour=vehicleCategory)) +
  geom_line() +
  scale_x_datetime("Hour",
                   breaks=scales::date_breaks("2 hour"),
                   labels=scales::date_format("%H", tz = dateTZ)) +
  scale_y_continuous("Activity", breaks = scales::pretty_breaks()) +
  scale_colour_manual("Vehicle Category", values = c("#eca35b", "#20b2aa")) +
  theme_marain() +
  theme(legend.title = element_text(size = 10),
        legend.text = element_text(size = 10),
        axis.text.x = element_text(angle = 0, hjust = 1))
ggsave(pp(freightWorkDir,'/output/frism-activity-by-category.png'),p,width=6,height=3,units='in')


## MODE SPLIT
# pt$mode2 <- "Transit"
# pt[mode=="car"]$mode2 <- "Car"
# pt[mode=="car"&startsWith(vehicle,"rideHailVehicle")]$mode2 <- "Ridehail"
# pt[mode=="car"&startsWith(vehicle,"freight")]$mode2 <- "Freight"
# pt[mode=="walk"]$mode2 <- "Walk"
# pt[mode=="bike"]$mode2 <- "Bike"
# summary <- pt[,.(VMTInMiles=mean(length)/1609.34,fuelInKW=(mean(primaryFuel+secondaryFuel))*2.77778e-7),by=.(mode2)]
# factor.remap <- c('Walk'='Walk','Bike'='Bike','Ridehail'='Ridehail','Car'='Car','Transit'='Public Transit', 'Freight'='Freight')
# factor.colors <- c('Walk'='#669900','Bike'='#FFB164','Ridehail'='#B30C0C','Car'='#8A8A8A','Transit'='#0066CC','Freight'="#660099")
# factor.colors.remapped <- factor.colors
# ggplot(summary, aes(x="",y=VMTInMiles,fill=mode2))+
#   geom_bar(stat='identity')+
#   labs(y='',x='Scenario',fill='Mode',title='Mobility Metrics')+
#   theme_marain()+
#   theme(axis.text.x = element_text(angle = 0, hjust=0.5),strip.text = element_text(size=rel(1.2)))+
#   scale_fill_manual(values = factor.colors.remapped)


# ***************************
# B2B vs B2C
# ***************************
b2b_pt <- freight_pt[grepl("b2b",vehicle)][,label:="B2B"]
b2c_pt <- freight_pt[grepl("b2c",vehicle)][,label:="B2C"]

## FREIGHT B2C GEO DISTRIBUTION OF STOPS
#countyNames <- c('Alameda County','Contra Costa County','Marin County','Napa County','Santa Clara County','San Francisco County','San Mateo County','Sonoma County','Solano County')
source("~/Documents/Workspace/scripts/common/keys.R")
register_google(key = google_api_key_1)
oakland_map <- ggmap::get_googlemap("alameda california", zoom = 9, maptype = "roadmap",color = "bw", style = c(feature = "all", element = "labels", visibility = "off"))
shpFile <- pp(activitySimDir, "/__San_Francisco_Bay_Region_2010_Census_Block_Groups-shp/641aa0d4-ce5b-4a81-9c30-8790c4ab8cfb202047-1-wkkklf.j5ouj.shp")
sfBayCbg <- st_read(shpFile)
b2b_pt_stops <- clusteringFreightBy(b2b_pt,c("endX","endY"),sfBayCbg,50,"B2B")
b2c_pt_stops <- clusteringFreightBy(b2c_pt,c("endX","endY"),sfBayCbg,50,"B2C")
to_plot <- rbind(b2c_pt_stops)
hours_to_show <- c(8, 14, 20)
toplot <- to_plot[hour %in% hours_to_show]
toplot$hour.label <- ""
toplot[hour==8]$hour.label <- "8am"
toplot[hour==14]$hour.label <- "2pm"
toplot[hour==20]$hour.label <- "8pm"
#toplot[hour==20]$hour.label <- "8pm"
hour.label_order <- c("8am", "2pm", "8pm")
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
ggsave(pp(freightWorkDir,'/output/b2c-stops.png'),p,width=9,height=5,units='in')


## FREIGHT B2B GEO DISTRIBUTION OF STOPS
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
ggsave(pp(freightWorkDir,'/b2b-stops.png'),p,width=4,height=5,units='in')


## FREIGHT ACTIVITY
to_plot <- rbind(b2b_pt,b2c_pt)
p <- to_plot[,time24:=arrivalTime%%(24*3600),][,.N,by=.(timeBin=as.POSIXct(cut(toDateTime(time24),"30 min")), label)] %>%
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
ggsave(pp(freightWorkDir,'/freight-activity.png'),p,width=6,height=3,units='in')

## FREIGHT AVG VMT
to_plot <- rbind(b2b_pt,b2c_pt)[,.(VMT=mean(length)/1609.3),by=.(label)]
p <- ggplot(to_plot, aes(x=label,y=VMT,fill=label))+
  geom_bar(stat='identity')+
  labs(y='Miles',x='',title='Avg VMT')+
  scale_fill_manual("Supply Chain", values = c("#eca35b", "#20b2aa")) +
  theme_marain()+
  theme(strip.text = element_text(size = 10),
        axis.text.x = element_blank(),
        legend.title = element_text(size = 10),
        legend.text = element_text(size = 10))
ggsave(pp(freightWorkDir,'/freight-avg-vmt.png'),p,width=4,height=3,units='in')


# ***************************
# LDT vs HDT
# ***************************
ldt_pt <- freight_pt[vehicleType == "FREIGHT-1"][,category:="LightDutyTruck"]
hdt_pt <- freight_pt[vehicleType == "FREIGHT-2"][,category:="HeavyDutyTruck"]

## FREIGHT ACTIVITY BY TRUCK CATEGORY
to_plot <- rbind(ldt_pt,hdt_pt)
p <- to_plot[,time24:=arrivalTime%%(24*3600),][,.N,by=.(timeBin=as.POSIXct(cut(toDateTime(time24),"30 min")), category)] %>%
  ggplot(aes(timeBin, N, colour=category)) +
  geom_line() +
  scale_x_datetime("Hour",
                   breaks=scales::date_breaks("2 hour"),
                   labels=scales::date_format("%H", tz = dateTZ)) +
  scale_y_continuous("Activity", breaks = scales::pretty_breaks()) +
  scale_colour_manual("Vehicle Category", values = c("#eca35b", "#20b2aa")) +
  theme_marain() +
  theme(legend.title = element_text(size = 10),
        legend.text = element_text(size = 10),
        axis.text.x = element_text(angle = 0, hjust = 1))
ggsave(pp(freightWorkDir,'/freight-activity-by-category.png'),p,width=6,height=3,units='in')

## FREIGHT AVG VMT BY TRUCK CATEGORY
to_plot <- rbind(ldt_pt,hdt_pt)[,.(VMT=mean(length)/1609.3),by=.(category)]
p <- ggplot(to_plot, aes(x=category,y=VMT,fill=category))+
  geom_bar(stat='identity')+
  labs(y='Miles',x='',title='Avg VMT')+
  scale_fill_manual("Vehicle Category", values = c("#eca35b", "#20b2aa")) +
  theme_marain()+
  theme(strip.text = element_text(size = 10),
        axis.text.x = element_blank(),
        legend.title = element_text(size = 10),
        legend.text = element_text(size = 10))
ggsave(pp(freightWorkDir,'/freight-avg-vmt-by-category.png'),p,width=4,height=3,units='in')



################ ***************************
################ validation CALTrans
################ ***************************

##### PREPARING NETWORK AND MATCH IT WITH POSTMILE AND TRUCK AADTT DATA
#"primary","secondary","tertiary"
network <- readCsv(normalizePath(paste(freightDir,"/validation/beam/network.csv",sep="")))
network_cleaned <- network[
  linkModes %in% c("car;bike", "car;walk;bike") & attributeOrigType %in% c("motorway","trunk","primary", "secondary")][
    ,-c("numberOfLanes", "attributeOrigId", "fromNodeId", "toNodeId", "toLocationX", "toLocationY")]
counties <- data.table::data.table(
  COUNTY = c("Alameda", "Contra Costa", "Marin", "Napa", "Santa Clara",
             "San Francisco", "San Mateo", "Solano", "Sonoma"),
  CNTY=c("ALA", "CC", "MRN", "NAP", "SCL", "SF", "SM", "SOL", "SON")
)
linkStats <- readCsv(normalizePath(paste(freightDir,"/validation/beam/0.linkstats.new.csv.gz",sep="")))

#data.table::fwrite(network_cleaned, pp(freightDir,"/validation/network_cleaned.csv"), quote=F)

# truck_aadtt_2017 <- readCsv(normalizePath(paste(freightDir,"/validation/2017_truck_aadtt.csv",sep="")))
# truck_aadtt_2017_sfbay <- assignPostMilesGeometries(truck_aadtt_2017[counties, on=c("CNTY"="CNTY")],
#                           pp(freightDir, "/validation/ds1901_shp/ds1901.shp"))
truck_aadtt_2017_sfbay <- data.table::fread(
  normalizePath(paste(freightDir,"/validation/2017_truck_aadtt_geocoded.csv",sep="")),
  header=T,
  sep=",")

truck_aadtt_with_linkId <- assignLinkIdToTruckAADTT(network_cleaned, 26910, truck_aadtt_2017_sfbay, 500, 2)
ata.table::fwrite(truck_aadtt_with_linkId, pp(freightDir,"/validation/truck_aadtt_with_linkId.csv"), quote=F)
truck_aadtt_with_linkData <- merge(data.table::as.data.table(truck_aadtt_with_linkId), network_cleaned, by="linkId")


##### PREPARING BEAM/LINKSTAT DATA
#linkstats_noFreight <- readCsv(normalizePath(paste(freightDir,"/validation/beam/0.linkstats.nofreight.csv.gz",sep="")))
#linkstats_wFreight <- readCsv(normalizePath(paste(freightDir,"/validation/beam/0.linkstats.withfreight.csv.gz",sep="")))
#totVolume <- sum(linkstats_wFreight$volume) - sum(linkstats_noFreight$volume)
#totAADTT <- sum(truck_aadtt_2017_bay_area$TRUCK_AADT)
lsWFreight <- linkstats_wFreight[,.(volumeWithFreight=sum(volume),lengthInMeter=first(length)),by=.(link)]
lsNoFreight <- linkstats_noFreight[,.(volumeNoFreight=sum(volume)),by=.(link)]
linkStats <- lsWFreight[lsNoFreight, on=c("link")]
linkStats$truck_volume <- linkStats$volumeWithFreight - linkStats$volumeNoFreight
linkStats$vehicle_volume <- linkStats$volumeWithFreight
linkStats$truck_share <- linkStats$truck_volume/linkStats$vehicle_volume
linkStats <- linkStats[,-c("volumeWithFreight","volumeNoFreight")]
linkStats[is.na(truck_share)]$truck_share <- 0.0
linkStats[is.infinite(truck_share)]$truck_share <- 0.0


##### MERGING
truck_aadtt_with_linkStats <- merge(truck_aadtt_with_linkData, linkStats, by.x="linkId", by.y="link")

sum(truck_aadtt_with_linkStats$TRUCK_AADT)
sum(truck_aadtt_with_linkStats$truck_volume)

LinkStatsWithLocation <- linkStats[network_cleaned, on=c("link"="linkId")]

LinkStats_as_sf <- st_transform(st_as_sf(
  LinkStatsWithLocation,
  coords = c("fromLocationX", "fromLocationY"),
  crs = 26910,
  agr = "constant"), 4326)


LinkStats_withTaz <- data.table::as.data.table(st_intersection(LinkStats_as_sf, st_buffer(sfBayTAZs, 0)))
LinkStats_withTaz <- LinkStats_withTaz[counties, on="county"]

countyStats <- LinkStats_withTaz[,.(truck_volume=sum(truck_volume),vehicle_volume=sum(vehicle_volume)),by=.(county)][counties, on="county"]
unique_counties <- unique(countyStats$cnty)

SF_truck_aadtt_2017 <- truck_aadtt_2017[CNTY %in% unique_counties]
dist_truck_aadtt_2017 <- SF_truck_aadtt_2017[,.(TRUCK_AADT=mean(TRUCK_AADT),VEHICLE_AADT=mean(VEHICLE_AADT)),by=.(DIST)]

county_truck_aadtt_2017 <- SF_truck_aadtt_2017[,.(TRUCK_AADT=sum(TRUCK_AADT),VEHICLE_AADT=sum(VEHICLE_AADT)),by=.(CNTY)]

truckBEAM_truckAADTT <- countyStats[county_truck_aadtt_2017, on=c("cnty"="CNTY")]

truckBEAM_truckAADTT %>% ggplot(aes(county)) +
  geom_bar(truck_volume, )


sfBayTAZs <- st_read(pp(freightDir, "/validation/TAZs/Transportation_Analysis_Zones.shp"))


################ ***************************
################ validation HPMS
################ ***************************

sf_hpms <- st_read(pp(freightDir, "/validation/sf_hpms_inventory_clipped.geojson"))

Volume_beam <- sum(linkStats$TruckVolume)
Volume_hpms <- sum(sf_hpms$AADT_Combi+sf_hpms$AADT_Singl)
VMT_beam <- sum(linkStats$TruckVolume * linkStats$length/1609)
VMT_hpms <- (sum((sf_hpms$AADT_Combi+sf_hpms$AADT_Singl) * as.numeric(st_length(sf_hpms))/1609))

Volume_beam/Volume_hpms
VMT_beam/VMT_hpms

freight_pt[,.(VMT=sum(length)/1609.0),by=.(vehicleType)]
freight_pt[,.(tourMT=sum(length)/1609.0),by=.(vehicle,vehicleType)][,.(avgTourMT=mean(tourMT)),by=.(vehicleType)]

write.csv(
  freight_pt,
  file = pp(freightWorkDir, "/freight.pt.0.events.csv"),
  row.names=F,
  quote=T)


