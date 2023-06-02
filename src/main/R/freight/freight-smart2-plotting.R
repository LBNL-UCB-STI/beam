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

expansionFactor <- 1/0.5
city <- "austin"
#batch <- "/Oct30"
batch <- ""
cityCRS <- 26910
iteration <- 0
eventsPrefix <- ""
scenario <- "price-sensitivity"

## PATHS
mainDir <- normalizePath("~/Workspace/Data")
activitySimDir <- pp(mainDir, "/ACTIVITYSIM")
workDir <- pp(mainDir, "/FREIGHT/", city)
validationDir <- pp(workDir,"/validation")
eventsFile <- pp(eventsPrefix,iteration,".events.csv.gz")
#linkStatsFile <- pp(eventsPrefix,iteration,".linkstats.csv.gz")
runOutput <- pp(workDir,"/beam/runs/",scenario,"/output/")
dir.create(runOutput, showWarnings = FALSE)

runs <- c(
"2050_Ref_highp2", "2050_Ref_highp4", "2050_Ref_highp6", "2050_Ref_highp8", "2050_Ref_highp10",
"2050_HOP_highp2",  "2050_HOP_highp4", "2050_HOP_highp6",  "2050_HOP_highp8", "2050_HOP_highp10"
)
runs_label <- c(
"ROP-p2", "ROP-p4", "ROP-p6", "ROP-p8", "ROP-p10",
"HOP-p2",  "HOP-p4", "HOP-p6", "HOP-p8", "HOP-p10"
)
events_filtered_all <- data.table::data.table()
#linkStats_all <- data.table::data.table()
i<-0
for(r in runs) {
  i<-i+1
  print(runs[i])
  runDir <- pp(workDir,"/beam/runs/",scenario,"/",r,batch)
  events_filtered <- readCsv(pp(runDir, "/filtered.",eventsFile))
  events_filtered$run <- r
  events_filtered$runLabel <- runs_label[i]
  events_filtered_all <- rbind(events_filtered_all, events_filtered)
  #linkStats <- readCsv(normalizePath(pp(runDir,"/",linkStatsFile)))
  #linkStats$run <- r
  #linkStats_all <- rbind(linkStats_all, linkStats)
}
write.csv(
  events_filtered_all,
  file = pp(runOutput,'/', pp(iteration,".events_filtered_all",eventsPrefix,".csv")),
  row.names=F,
  quote=T)
# fwrite(
#   linkStats_all,
#   file = pp(runOutput,'/', pp(iteration,".linkStats_all",eventsPrefix,".csv")),
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

#unique(pt$vehicleType)
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

#pt[,.N,by=.(vehicle,run)][,.(count=.N*2),by=.(run)]
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
