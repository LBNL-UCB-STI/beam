setwd(dirname(rstudioapi::getSourceEditorContext()$path))
source("../common/helpers.R")
source("../common/theme.R")
#install.packages("remotes")
#remotes::install_github("colinsheppard/colinmisc")
library('colinmisc')
library(dplyr)
library(ggplot2)
library(rapport)
library(sjmisc)
library(ggmap)
library(sf)
library(stringr)

getHPMSAADT <- function(linkAADT) {
  linkAADT$Volume_hpms <- linkAADT$AADT_Combi+linkAADT$AADT_Singl
  linkAADT$VMT_hpms <- linkAADT$Volume_hpms * as.numeric(st_length(linkAADT))/1609.0
  return(data.table::as.data.table(linkAADT))
}

getTDoxAADT <- function(linkAADT) {
  linkAADT$Volume_hpms <- linkAADT$AADT_TRUCK
  linkAADT$VMT_hpms <- linkAADT$Volume_hpms * as.numeric(st_length(linkAADT))/1609.0
  return(data.table::as.data.table(linkAADT))
}

isCav <- function(x) {
  return(x >= 4)
}

### RouteE

work_folder <- normalizePath("~/Workspace/Data/Scenarios/sfbay/")
library(sf)
geojson_file_path <- pp(work_folder, "/input/beam_npmrds_network_map.geojson")
geo_data <- st_read(geojson_file_path)
library(data.table)
geo_data_dt <- as.data.table(geo_data)

ggplot(geo_data_dt, aes(x = F_System)) + 
  geom_histogram(binwidth = 1, fill = "blue", color = "black") +
  theme_minimal() 


linkstats <- readCsv(pp(work_folder, "/sfbay-simp-jdeq-0.07__2024-02-21_19-22-50_obb/10.linkstats.csv.gz"))
network <- readCsv(pp(work_folder, "/sfbay-simp-jdeq-0.07__2024-02-21_19-22-50_obb/network.csv.gz"))

network <- readCsv(pp(work_folder, "/sfbay-simp-jdeq-0.07__2024-02-21_19-22-50_obb/network.csv.gz"))

## test
work_folder <- normalizePath("~/Workspace/Data/FREIGHT/")
linkstats <- readCsv(pp(work_folder, "/sfbay/0.linkstats.csv.gz"))
network <- readCsv(pp(work_folder, "/sfbay/beam/network.csv.gz"))
##
debug_file <- readCsv(pp(work_folder, "/sfbay/beam/runs/baseline/2018_routeE_new/beamLog.filtered.csv"))
#
events_file <- readCsv(pp(work_folder, "/sfbay/beam/runs/baseline/2018_routeE_new/0.events.csv.gz"))
##
## test 2
#/Users/haitamlaarabi/Workspace/Data/FREIGHT/sfbay/vehicle-tech/2020
filename1 <- pp(work_folder, "/vehicle-tech/2020/Class_6_Box_truck_(Diesel,_2020,_no_program).csv")
filename2 <- pp(work_folder, "/vehicle-tech/2020/Class_8_Box_truck_(Diesel,_2020,_no_program).csv")
filename3 <- pp(work_folder, "/vehicle-tech/2020/Class_8_Sleeper_cab_high_roof_(Diesel,_2020,_no_program).csv")

filename4 <- pp(work_folder, "/vehicle-tech/2025/Class_6_Box_truck_(BEV,_2025,_no_program).csv")
filename5 <- pp(work_folder, "/vehicle-tech/2025/Class_6_Box_truck_(HEV,_2025,_no_program).csv")
filename6 <- pp(work_folder, "/vehicle-tech/2025/Class_8_Box_truck_(BEV,_2025,_no_program).csv")
filename7 <- pp(work_folder, "/vehicle-tech/2025/Class_8_Box_truck_(HEV,_2025,_no_program).csv")
filename8 <- pp(work_folder, "/vehicle-tech/2025/Class_8_Sleeper_cab_high_roof_(BEV,_2025,_no_program).csv")
filename9 <- pp(work_folder, "/vehicle-tech/2025/Class_8_Sleeper_cab_high_roof_(HEV,_2025,_no_program).csv")

filenameX <- filename9
class6Diesel <- readCsv(filenameX)
#class6Diesel <- cbind(index = 1:nrow(class6Diesel), class6Diesel)
write.csv(class6Diesel, file = filenameX, row.names=F, quote=T)

allClasses <- rbind(class6Diesel, class8vDiesel, class8tDiesel, class6BEV, class6HEV, class8vBEV, class8vHEV, class8tBEV, class8tHEV)
test <- allClasses[rate==0.6621670216912081]
allClasses[rate==0.6621670216912081]
####

linstatsPlus <- merge(linkstats, network, by.x="link", by.y="linkId")



#### Calibration
run_dir = '/sfbay/beam/runs'
network <- readCsv(pp(work_folder, run_dir, "/../network.csv.gz"))

linkstats_jd_200 <- readCsv(pp(work_folder, run_dir, "/calibration-jdeqsim/2018-200/15.linkstats.csv.gz"))
linkstats_bp_150 <- readCsv(pp(work_folder, run_dir, "/calibration-bprsim/2018-150/15.linkstats.csv.gz"))


linkstats_jd_200_merged <- linkstats_jd_200[network, on=c("link"="linkId")]
linkstats_bp_150_merged <- linkstats_bp_150[network, on=c("link"="linkId")]


res <- linkstats_bp_150_merged[attributeOrigType %in% c("motorway", "primary", "secondary", "motorway_link", "tertiary")][
  ,.(avgSpeedMPH=sum(volume*length)/sum(volume*traveltime)),by=.(hour,attributeOrigType)]

ggplot(res, aes(hour, avgSpeedMPH, color=attributeOrigType)) + 
  geom_line() + theme_marain() + xlim(0, 40) + 
  ggtitle("bprsim - 150% FC/Pop - min speed 0.5mps")

####


city <- "sfbay"
linkAADTFile <- "/hpms/sf_hpms_inventory_clipped_original.geojson"
# batch <- 5
# city <- "austin"
# linkAADTFile <- "/hpms/austin_hpms_inventory.geojson"
#batch <- "/Oct30"
batch <- ""
cityCRS <- 26910
#scenario <- "2030_low"
#batch2 <- "/Oct30"
# batch2 <- ""
# scenario2 <- "2040"
iteration <- 0
eventsPrefix <- ""
expansionFactor <- 1/0.1
scenario <- "price-sensitivity"

## PATHS
mainDir <- normalizePath("~/Workspace/Data")
activitySimDir <- pp(mainDir, "/ACTIVITYSIM")
workDir <- pp(mainDir, "/FREIGHT/", city)
validationDir <- pp(workDir,"/validation")
#freightDir <- pp(workDir,"/beam_freight/",scenario)
eventsFile <- pp(eventsPrefix,iteration,".events.csv.gz")
linkStatsFile <- pp(eventsPrefix,iteration,".linkstats.csv.gz")
runOutput <- pp(workDir,"/beam/runs/",scenario,"/output/")
dir.create(runOutput, showWarnings = FALSE)

runs <- c("2050_Ref_highp2", "2050_HOP_highp2", "2050_Ref_highp4", "2050_HOP_highp4", 
          "2050_Ref_highp6", "2050_HOP_highp6", "2050_Ref_highp8", "2050_HOP_highp8",
          "2050_Ref_highp10", "2050_HOP_highp10")
runs_label <- c("ROP-p2", "HOP-p2", "ROP-p4", "HOP-p4", 
                "ROP-p6", "HOP-p6", "ROP-p8", "HOP-p8",
                "ROP-p10", "HOP-p10")
events_filtered_all <- data.table::data.table()
linkStats_all <- data.table::data.table()
i<-0
for(r in runs) {
  i<-i+1
  print(runs[i])
  runDir <- pp(workDir,"/beam/runs/",scenario,"/",r,batch)
  events_filtered <- readCsv(pp(runDir, "/filtered.",eventsFile))
  events_filtered$run <- r
  events_filtered$runLabel <- runs_label[i]
  events_filtered_all <- rbind(events_filtered_all, events_filtered)
  linkStats <- readCsv(normalizePath(pp(runDir,"/",linkStatsFile)))
  linkStats$run <- r
  linkStats_all <- rbind(linkStats_all, linkStats)
}
write.csv(
  events_filtered_all,
  file = pp(runOutput,'/', pp(iteration,".events_filtered_all",eventsPrefix,".csv")),
  row.names=F,
  quote=T)
fwrite(
  linkStats_all,
  file = pp(runOutput,'/', pp(iteration,".linkStats_all",eventsPrefix,".csv")),
  row.names=F,
  quote=T)


# runDir2 <- pp(workDir,"/beam/runs/",scenario2,batch2)
# runOutput2 <- pp(runDir2,"/output")

# eventsFile2 <- pp(run,iteration,".events.csv.gz")
# linkStatsFile2 <- pp(run,iteration,".linkstats.csv.gz")



## READING
linkAADT <- st_read(pp(validationDir, linkAADTFile))
# caltransTruckAADTT <- data.table::fread(
#   normalizePath(pp(validationDir,"/caltrans/2017_truck_aadtt_geocoded.csv")), 
#   header=T, 
#   sep=",")
# screelines <- readCsv(pp(validationDir,"/screenlines.csv"))

#events <- readCsv(pp(runDir, "/",eventsFile))
#events[type=="PathTraversal"&!startsWith(vehicle, "body")&grepl("freight",vehicle),.N,by=.(vehicle)]
#unique(events[!startsWith(vehicle, "body")&!startsWith(vehicle, "rideHail")&!grepl(":",vehicle)]$vehicle)

#events[type=="PathTraversal"&grepl("freight",vehicle),.N,by=.(vehicle)]


network <- readCsv(normalizePath(pp(workDir,"/beam/network.csv.gz")))
network$linkFreeSpeedTravelTime <- network$linkLength/network$linkFreeSpeed
# ggplot(network, aes(x=linkFreeSpeed*2.237)) + 
#   geom_histogram(color="black", fill="white") + 
#   labs(x="miles per hour")
# ggplot(network[linkFreeSpeedTravelTime<=5*60], aes(x=linkFreeSpeedTravelTime/60.0)) + 
#   geom_histogram(color="black", fill="white")

# events_filtered2 <- readCsv(pp(runDir2, "/filtered.",eventsFile))
# linkStats2 <- readCsv(normalizePath(pp(runDir2,"/",linkStatsFile)))


networkFiltered<- network[
  linkModes %in% c("car;bike", "car;walk;bike") & attributeOrigType %in% c("motorway","trunk","primary", "secondary")][
    ,-c("numberOfLanes", "attributeOrigId", "fromNodeId", "toNodeId", "toLocationX", "toLocationY")]

# networkWGS84 <- st_transform(st_as_sf(
#   networkFiltered,
#   coords = c("fromLocationX", "fromLocationY"),
#   crs = cityCRS,
#   agr = "constant"), 4326)
# networkWGS84$X <- st_coordinates(networkWGS84$geometry)[,1]
# networkWGS84$Y <- st_coordinates(networkWGS84$geometry)[,2]
# networkWGS84 <- data.table::as.data.table(networkWGS84)
#data.table::fwrite(networkWGS84, normalizePath(pp(workDir,"/beam/networkWGS84.csv")), quote=F)

# persons <- readCsv(pp(freightDir, "/austin/persons.csv.gz"))
# events <- readCsv(pp(freightDir, "/via/0.events.csv"))
# events[grepl("freight",vehicle)]
# events[mode=="car"][grepl("freight",vehicle)]
# pt <- events[type=="PathTraversal"]
# unique(pt[grepl("ridehail",vehicle)]$vehicle)
# pt[grepl("-b2b-",driver)]
# events[startsWith(person,"freightPerson")]
# unique(events[startsWith(vehicle,"freightVehicle")]$vehicle)
# test <- events[grepl("freight",vehicle)]
# 
# test2 <- events[startsWith(person, "freight-carrier")]

# events_filtered <- events[(actType %in% c("Warehouse", "Unloading", "Loading")) | (type=="PathTraversal" & startsWith(vehicle,"freight"))]
# write.csv(
#   events_filtered,
#   file = pp(freightWorkDir, "/filtered.0.events.csv"),
#   row.names=F,
#   quote=T)
columns <- c("time","type","vehicleType","vehicle","secondaryFuelLevel",
             "primaryFuelLevel","driver","mode","seatingCapacity","startX",
             "startY", "endX", "endY", "capacity", "arrivalTime", "departureTime",
             "secondaryFuel", "secondaryFuelType", "primaryFuelType",
             "numPassengers", "length", "primaryFuel", "run", "runLabel")
pt <- data.table::as.data.table(events_filtered_all[type=="PathTraversal"][startsWith(vehicle,"freight")][,..columns])
if (nrow(pt[grepl("-emergency-",vehicle)]) > 0) {
  println("This is a bug")
}

unique(pt$vehicleType)

pt$energyType <- "Diesel"
pt$energyTypeCode <- "Diesel"
pt[grepl("E-BE", toupper(vehicleType))]$energyType <- "Electric"
pt[grepl("E-BE", toupper(vehicleType))]$energyTypeCode <- "BEV"
pt[grepl("E-PHEV", toupper(vehicleType))]$energyType <- "Electric"
pt[grepl("E-PHEV", toupper(vehicleType))]$energyTypeCode <- "PHEV"
pt[grepl("H2FC", toupper(vehicleType))]$energyType <- "Hydrogen"
pt[grepl("H2FC", toupper(vehicleType))]$energyTypeCode <- "H2FC"
pt$vehicleCategory <- "Heady Duty"
pt$vehicleClass <- "Class 4-6 Vocational"
pt[grepl("-md-", vehicleType)]$vehicleCategory <- "Medium Duty"
pt[grepl("-hdt-", vehicleType)]$vehicleClass <- "Class 7&8 Tractor"
pt[grepl("-hdv-", vehicleType)]$vehicleClass <- "Class 7&8 Vocational"
#pt$scenario2 <- "Base"
#pt[scenario=="2050_central"]$scenario2 <- "Central tech"
#pt[scenario=="2050_high"]$scenario2 <- "High tech"

pt[,.N,by=.(vehicle,run)][,.(count=.N*2),by=.(run)]

## ***
energy_consumption <- pt[,.(fuelGWH=expansionFactor*sum(primaryFuel/3.6e+12)),by=.(energyType,runLabel)]
write.csv(
  energy_consumption,
  file = pp(runOutput,'/', pp(iteration,".freight-energy-consumption-by-powertrain",eventsPrefix,".csv")),
  row.names=F,
  quote=T)

p<-ggplot(energy_consumption, aes(factor(runLabel, level=runs_label), fuelGWH, fill=energyType)) +
  geom_bar(stat='identity') +
  labs(y='GWe',x='Scenario',fill='Powertrain', title='Freight Energy Consumptio - 2050 HighTechn')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),strip.text = element_text(size=rel(1.2)))+
  scale_fill_manual(values=c("#999999", "#56B4E9", "#66A61E"))
ggsave(pp(runOutput,'/', pp(iteration,".freight-energy-consumption-by-powertrain",eventsPrefix,".png")),p,width=10,height=4,units='in')

## ***
#
energy_vmt <- pt[,.(MVMT=expansionFactor*sum(length/1609.344)/1000000),by=.(energyType,runLabel)]
write.csv(
  energy_vmt,
  file = pp(runOutput,'/', pp(iteration,".freight-VMT-by-powertrain",eventsPrefix,".csv")),
  row.names=F,
  quote=T)

p <- ggplot(energy_vmt, aes(factor(runLabel, level=runs_label), MVMT, fill=energyType)) +
  geom_bar(stat='identity') +
  labs(y='Million VMT',x='Scenario',fill='Energy Type', title='Freight VMT - 2050 HighTech')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),strip.text = element_text(size=rel(1.2)))+
  scale_fill_manual(values=c("#999999", "#56B4E9", "#66A61E"))
ggsave(pp(runOutput,'/', pp(iteration,".freight-VMT-by-powertrain",eventsPrefix,".png")),p,width=10,height=4,units='in')

## ***

energy_vehType_vmt <- pt[,.(MVMT=expansionFactor*sum(length/1609.344)/1000000),by=.(energyTypeCode,vehicleClass,runLabel)]
energy_vehType_vmt[,totVMTByScenario:=sum(MVMT),by=.(runLabel)]
energy_vehType_vmt[,EnergyAndVehiclesTypes:=paste(energyTypeCode,vehicleClass,sep=" ")]
energy_vehType_levels <- c("BEV Class 4-6 Vocational", "BEV Class 7&8 Vocational", "BEV Class 7&8 Tractor",
                           "PHEV Class 4-6 Vocational", "PHEV Class 7&8 Vocational", "PHEV Class 7&8 Tractor",
                           "H2FC Class 4-6 Vocational", "H2FC Class 7&8 Vocational", "H2FC Class 7&8 Tractor",
                           "Diesel Class 4-6 Vocational", "Diesel Class 7&8 Vocational", "Diesel Class 7&8 Tractor")
energy_vehType_vmt$EnergyAndVehiclesTypes <- factor(energy_vehType_vmt$EnergyAndVehiclesTypes, levels = energy_vehType_levels)
write.csv(
  energy_vehType_vmt,
  file = pp(runOutput,'/', pp(iteration,".freight-VMT-by-powertrain-vehicletypes",eventsPrefix,".csv")),
  row.names=F,
  quote=T)

p<-ggplot(energy_vehType_vmt, aes(factor(runLabel, level=runs_label), MVMT/totVMTByScenario, fill=EnergyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  labs(y='Relative VMT Share',x='Scenario',fill='Energy-Vehicle Type', title='Freight Volume')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),strip.text = element_text(size=rel(1.2)))+
  scale_fill_manual(values=c("deepskyblue2","deepskyblue3", "deepskyblue4",
                             "goldenrod2", "goldenrod3", "goldenrod4",
                             "chartreuse2", "chartreuse3","chartreuse4",
                             "azure3","darkgray", "azure4"
                             ))
ggsave(pp(runOutput,'/', pp(iteration,".freight-VMT-by-powertrain-vehicletypes",eventsPrefix,".png")),p,width=10,height=4,units='in')

energy_vehType_vmt[,.(MVMT=sum(MVMT)),by=.(energyType2,totVMTByScenario,scenario2)]

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
# LDT vs HDT
# ***************************
ldt_pt <- freight_pt[vehicleType == "freight-MD-1"][,category:="Medium Duty"]
hdt_pt <- freight_pt[vehicleType == "freight-HD-2"][,category:="Heavy Duty"]

## FREIGHT ACTIVITY BY TRUCK CATEGORY
to_plot <- rbind(ldt_pt,hdt_pt)
p <- to_plot[,time24:=arrivalTime%%(24*3600),][,.N,by=.(timeBin=as.POSIXct(cut(toDateTime(time24),"1 hour")), category)] %>% 
  ggplot(aes(timeBin, N/1000, colour=category)) +
  geom_line() + 
  scale_x_datetime("Hour", 
                   breaks=scales::date_breaks("2 hour"), 
                   labels=scales::date_format("%H", tz = dateTZ)) +
  scale_y_continuous("Trip Rate (10^3)", breaks = scales::pretty_breaks()) +
  scale_colour_manual("Vehicle Category", values = c("#eca35b", "#20b2aa")) +
  theme_marain() +
  theme(legend.title = element_text(size = 10),
        legend.text = element_text(size = 10),
        axis.text.x = element_text(angle = 0, hjust = 1))
ggsave(pp(runOutput,'/', pp(iteration,".freight-activity-by-category",run,".png")),p,width=6,height=3,units='in')

## FREIGHT AVG TRIP VMT BY TRUCK CATEGORY
to_plot <- rbind(ldt_pt,hdt_pt)[,.(VMT=mean(length)/1609.3),by=.(category)]
p <- ggplot(to_plot, aes(x=category,y=VMT,fill=category))+
  geom_bar(stat='identity')+
  labs(y='Miles',x='',title='Avg Trip VMT')+
  scale_fill_manual("Vehicle Category", values = c("#eca35b", "#20b2aa")) +
  theme_marain()+
  theme(strip.text = element_text(size = 9),
        axis.text.x = element_blank(),
        legend.title = element_text(size = 9),
        legend.text = element_text(size = 9))  + theme(legend.position = "none")
ggsave(pp(runOutput,'/', pp(iteration,".freight-avg-trip-vmt-by-category",run,".png")),p,width=3,height=2,units='in')

## FREIGHT TOUR TRIP VMT BY TRUCK CATEGORY
to_plot <- rbind(ldt_pt,hdt_pt)[,.(tourVMT=sum(length)/1609.3),by=.(vehicle,category)][,.(avgTourVMT=mean(tourVMT)),by=.(category)]
p <- ggplot(to_plot, aes(x=category,y=avgTourVMT,fill=category))+
  geom_bar(stat='identity')+
  labs(y='Miles',x='',title='Avg Tour VMT')+
  scale_fill_manual("Vehicle Category", values = c("#eca35b", "#20b2aa")) +
  theme_marain()+
  theme(strip.text = element_text(size = 9),
        axis.text.x = element_blank(),
        legend.title = element_text(size = 9),
        legend.text = element_text(size = 9))
ggsave(pp(runOutput,'/', pp(iteration,".freight-avg-tour-vmt-by-category",run,".png")),p,width=4,height=3,units='in')


################ ***************************
################ validation HPMS
################ ***************************
linkAADT_dt <- getHPMSAADT(linkAADT)
# linkAADT_dt <- getTDoxAADT(linkAADT)
Volume_hpms <- sum(linkAADT_dt$Volume_hpms)
VMT_hpms <- sum(linkAADT_dt$VMT_hpms)


##
Volume_HD_beam <- sum(linkStats$HDTruckVolume)
Volume_MD_beam <- sum(linkStats$TruckVolume) - Volume_HD_beam
Volume_beam <- Volume_HD_beam + Volume_MD_beam

linkStats$VMT_HD_beam <- linkStats$HDTruckVolume * linkStats$length/1609.0
linkStats$VMT_MD_beam <- (linkStats$TruckVolume * linkStats$length/1609.0) - linkStats$VMT_HD_beam 
VMT_HD_beam <- sum(linkStats$VMT_HD_beam)
VMT_MD_beam <- sum(linkStats$VMT_MD_beam)
VMT_beam <- VMT_HD_beam + VMT_MD_beam

freight1 <- freight_pt[,.(avgTripVMT=mean(length)/1609.0),by=.(vehicleType)]
freight2 <- freight_pt[,.(tourMT=sum(length)/1609.0),by=.(vehicle,vehicleType)][,.(avgTourMT=mean(tourMT),numVehicles=.N),by=.(vehicleType)]
freightSummary <- data.table::data.table(
  label = c("HDT", "LDT", "TOT"), 
  vehicleType = c("freight-HD-2", "freight-MD-1", NA),
  volume = c(Volume_HD_beam, Volume_MD_beam, Volume_HD_beam+Volume_MD_beam),
  vmt = c(VMT_HD_beam, VMT_MD_beam, VMT_HD_beam+VMT_MD_beam),
  hpmsVolume = c(NA, NA, Volume_hpms),
  hpmsVMT = c(NA, NA, VMT_hpms),
  shareVolume = c(NA, NA, (Volume_HD_beam+Volume_MD_beam)/Volume_hpms),
  shareVMT = c(NA, NA, (VMT_HD_beam+VMT_MD_beam)/VMT_hpms)
)
freightSummary <- merge(freightSummary, freight1, by=c("vehicleType"), all=T)
freightSummary <- merge(freightSummary, freight2, by=c("vehicleType"), all=T)
freightSummary[is.na(vehicleType)]$avgTripVMT <- mean(freightSummary$avgTripVMT, na.rm=T)
freightSummary[is.na(vehicleType)]$avgTourMT <- mean(freightSummary$avgTourMT, na.rm=T)
freightSummary[is.na(vehicleType)]$numVehicles <- sum(freightSummary$numVehicles, na.rm=T)
freightSummary
write.csv(
  freightSummary,
  file = pp(runOutput, "/", pp(iteration,".summary-per-vehicle-type",run,".csv")),
  row.names=F,
  quote=T)


###
screelines_hpms <- screelines[sf_hpms_dt, on=c("Route_ID","Begin_Poin","End_Point")][!is.na(linkId)]
linkStatsAADT <- linkStats[,.(volume=sum(volume),
                              TruckVolume=sum(TruckVolume),
                              HDTruckVolume=sum(HDTruckVolume),
                              VMT_HD_beam=sum(VMT_HD_beam),
                              VMT_MD_beam=sum(VMT_MD_beam),
                              traveltime=mean(traveltime))
                           ,by=.(link,from,to,length,freespeed,capacity)]
screelines_hpms_network <- screelines_hpms[linkStatsAADT, on=c("linkId"="link")][!is.na(Route_ID)]
screelines_hpms_network_counts <- screelines_hpms_network[,c("Route_ID","Begin_Poin","End_Point","linkId","HDTruckVolume","TruckVolume","Volume_hpms")]
screelines_hpms_network_counts$VolumeDifference <- screelines_hpms_network_counts$TruckVolume - screelines_hpms_network_counts$Volume_hpms
sum(screelines_hpms_network_counts[Volume_hpms==0]$VolumeDifference)
sum(screelines_hpms_network_counts$VolumeDifference)
screelines_hpms_network_counts

write.csv(
  screelines_hpms_network_counts,
  file = pp(runOutput, "/", pp(iteration,".screelines_hpms_network_counts",run,".csv")),
  row.names=F,
  quote=T)

ggplot(screelines_hpms_network_counts) +
  geom_bar(aes(as.character(linkId), VolumeDifference), stat = 'identity') +
  theme(axis.text.x = element_text(angle = 45, hjust=0.5), strip.text = element_text(size=rel(1.2)))

################ ***************************
################ validation CALTrans
################ ***************************

##### PREPARING NETWORK AND MATCH IT WITH POSTMILE AND TRUCK AADTT DATA
#"primary","secondary","tertiary"
counties <- data.table::data.table(
  COUNTY = c("Alameda", "Contra Costa", "Marin", "Napa", "Santa Clara", 
             "San Francisco", "San Mateo", "Solano", "Sonoma"),
  CNTY = c("ALA", "CC", "MRN", "NAP", "SCL", "SF", "SM", "SOL", "SON")
)

#data.table::fwrite(network_cleaned, pp(freightDir,"/validation/network_cleaned.csv"), quote=F)

truck_aadtt_2017 <- readCsv(paste(validationDir,"/caltrans/2017_truck_aadtt.csv",sep=""))
# truck_aadtt_2017_sfbay <- assignPostMilesGeometries(truck_aadtt_2017[counties, on=c("CNTY"="CNTY")],
#                           pp(freightDir, "/validation/ds1901_shp/ds1901.shp"))

#truck_aadtt_with_linkId <- assignLinkIdToTruckAADTT(networkFiltered, 26910, caltransTruckAADTT, 500)
#data.table::fwrite(truck_aadtt_with_linkId, pp(runOutput,'/', pp(iteration,".truck-aadtt-with-linkId.",scenario,".csv"), quote=F))
truck_aadtt_with_linkId <- readCsv(pp(runOutput,'/', pp(iteration,".truck-aadtt-with-linkId.",scenario,".csv")))
truck_aadtt_with_linkData <- merge(data.table::as.data.table(truck_aadtt_with_linkId), networkFiltered, by="linkId")

##### MERGING
truck_aadtt_with_linkStats <- merge(truck_aadtt_with_linkData, linkStats, by.x="linkId", by.y="link")

sum(truck_aadtt_with_linkStats$TRUCK_AADT)
sum(truck_aadtt_with_linkStats$TruckVolume)

LinkStatsWithLocation <- linkStats[networkFiltered, on=c("link"="linkId")]

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


sfBayTAZs <- st_read(pp(validationDir, "/TAZs/Transportation_Analysis_Zones.shp"))











###########################################################################

## ***************************
#FRISM
## ***************************
freightDir <- pp(work_folder,"/sfbay/runs/baseline/")
carriers <- readCsv(pp(freightDir, "/freight-merged-carriers.csv"))
payload <- readCsv(pp(freightDir, "/freight-merged-payload-plans.csv"))
tours <- readCsv(pp(freightDir, "/freight-merged-tours.csv"))
vehiclesTypes <- readCsv(pp(freightDir, "/freight-vehicles-types.csv"))

tours_carriers <- tours[carriers, on="tourId"]
tours_carriers[departureTimeInSec <= 3*3600][,.N,by=.(vehicleTypeId)]

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
ggsave(pp(freightWorkDir,'/output/b2b-stops.png'),p,width=4,height=5,units='in')


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