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
library(tidyr)

## Functions
read_freight_events <- function(RUNS_NAMES, RUNS_LABELS, RUNS_DIR, ALL_LABEL, ITER = 0) {
  events_filtered_all <- data.table::data.table()
  #linkStats_all <- data.table::data.table()
  i<-0
  for(r in RUNS_NAMES) {
    i<-i+1
    print(RUNS_NAMES[i])
    runDir <- pp(RUNS_DIR, r, "/")
    events_filtered <- readCsv(pp(runDir, "filtered.",pp(ITER,".events.csv.gz")))
    events_filtered$runName <- r
    events_filtered$runLabel <- RUNS_LABELS[i]
    events_filtered_all <- rbind(events_filtered_all, events_filtered)
    #linkStats <- readCsv(normalizePath(pp(runDir,"/",linkStatsFile)))
    #linkStats$runName <- r
    #linkStats_all <- rbind(linkStats_all, linkStats)
  }
  write.csv(
    events_filtered_all,
    file = pp(RUNS_DIR, pp(ALL_LABEL,".filtered.",ITER,".events.csv")),
    row.names=F,
    quote=T)
  # fwrite(
  #   linkStats_all,
  #   file = pp(runOutputDir, pp(iteration,".linkStats_all.csv")),
  #   row.names=F,
  #   quote=T)
  return(events_filtered_all)
}
###
format_path_traversals <- function(EVENTS) {
  columns <- c("time","type","vehicleType","vehicle","secondaryFuelLevel",
               "primaryFuelLevel","driver","mode","seatingCapacity","startX",
               "startY", "endX", "endY", "capacity", "arrivalTime", "departureTime",
               "secondaryFuel", "secondaryFuelType", "primaryFuelType",
               "numPassengers", "length", "primaryFuel", "runName", "runLabel")
  pt <- data.table::as.data.table(EVENTS[type=="PathTraversal"][startsWith(vehicle,"freight")][,..columns])
  if (nrow(pt[grepl("-emergency-",vehicle)]) > 0) {
    println("This is a bug")
  }
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
  pt$business <- "B2B"
  pt[startsWith(vehicle, "freightVehicle-b2c-")]$business <- "B2C"
  print("PT formatted")
  return(pt)
}
###
# Function to calculate average speed for vectors of distances and speeds
average_speed_vector <- function(distances, speeds) {
  
  # Check if speeds contain zero
  if(any(speeds == 0)){
    stop("Speeds must be non-zero.")
  }
  
  # Total distance and total time
  total_distance <- sum(distances)
  total_time <- sum(distances / speeds)
  
  # Average speed formula: total distance / total time
  average_speed <- total_distance / total_time
  
  return(average_speed)
}
####

expansionFactor <- 1/0.1
city <- "sfbay"
workDir <- pp(normalizePath("~/Workspace/Data/"), "/FREIGHT/", city, "/")

#test <- readCsv(pp(workDir, "/beam/runs/baseline/2018/filtered.0.events.csv.gz"))

# ***************************************
# *************** BASELINE **************
# ***************************************

baseline_runs_dir <- pp(workDir, "beam/runs/baseline/")
baseline_output_dir <- pp(baseline_runs_dir, "output/")
dir.create(baseline_output_dir, showWarnings = FALSE)

baseline_runs_labels <- c("2018_new", "2018_routeE_new")
baseline_runs_name <- "baseline_routeE_new"
baseline_runs <- 
  read_freight_events(
    c("2018", "2018_routeE"), 
    baseline_runs_labels, 
    baseline_runs_dir,
    baseline_runs_name
  )
baseline_runs <- format_path_traversals(baseline_runs)

baseline_summary <- baseline_runs[,
                            .(
                              MVMT=expansionFactor*sum(length/1609.344)/1e+6,
                              GWH=expansionFactor*sum(primaryFuel/3.6e+12)
                            ),
                            by=.(energyTypeCode,vehicleClass,business,runLabel)
]
#energy_vehType_vmt[,totVMTByScenario:=sum(MVMT),by=.(runLabel)]
baseline_summary[,energyAndVehiclesTypes:=paste(energyTypeCode,vehicleClass,sep=" ")]
baseline_summary_levels <- c("BEV Class 7&8 Vocational", "BEV Class 7&8 Tractor", "BEV Class 4-6 Vocational",
                             "Diesel Class 4-6 Vocational", "Diesel Class 7&8 Vocational", "Diesel Class 7&8 Tractor")
baseline_summary_colors <- c("deepskyblue2", "deepskyblue3", "deepskyblue4", 
                             "azure3", "darkgray", "azure4")
baseline_summary$energyAndVehiclesTypes <- factor(baseline_summary$energyAndVehiclesTypes, levels=baseline_summary_levels)
write.csv(
  baseline_summary,
  file = pp(baseline_output_dir, pp(baseline_runs_name,"_VMT-and-GWH-by-powertrain-class.csv")),
  row.names=F,
  quote=T)

baseline_summary[,.(MVMT=sum(MVMT)),by=.(energyAndVehiclesTypes,runLabel)]

# ****** BASELINE - VMT Validation
validation <- data.table::data.table(
  label = c("Baseline", "HPMS"),
  MVMT = c(sum(baseline_summary[runLabel=="2018"]$MVMT), 7006801*1E-6)
)
p<-ggplot(validation, aes(label, MVMT, fill=label)) +
  geom_bar(stat='identity') +
  labs(y='Million VMT',x='Source',title='Total VMT')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10)) + 
  theme(legend.position = "none")


# ****** BASELINE - VMT
p<-ggplot(baseline_summary, aes(factor(runLabel, level=baseline_runs_labels), MVMT, fill=energyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  facet_grid(. ~ business) +
  labs(y='VMT',x='Scenario',fill='Powertrain - Class', title='Total Truck Travel - Baseline')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10))+
  scale_fill_manual(values=baseline_summary_colors)
ggsave(pp(baseline_output_dir, pp(baseline_runs_name,"_VMT-by-powertrain-class.png")),p,width=7,height=4,units='in')

# ****** BASELINE - Energy
p<-ggplot(baseline_summary, aes(factor(runLabel, level=baseline_runs_labels), GWH, fill=energyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  facet_grid(. ~ business) +
  labs(y='GWh',x='Scenario',fill='Powertrain - Class', title='Energy Consumption - Baseline')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10))+
  scale_fill_manual(values=baseline_summary_colors)
ggsave(pp(baseline_output_dir, pp(baseline_runs_name,"_GWH-by-powertrain-class.png")),p,width=7,height=4,units='in')

# ******************************

## TOURS *****
baseline_runs[,.(numTrips=.N),by=.(vehicle,vehicleType,runLabel)][,.(numVehicle=.N),by=.(vehicleType,runLabel)]
baseline_runs[,.N,by=.(runLabel)]
baseline_tours <- baseline_runs[order(vehicle,time),.(tourTime=last(arrivalTime)-first(departureTime), tourVMT=sum(length/1609.344)),by=.(vehicle,runLabel,business,vehicleClass)]
# baseline_tours_summary <- baseline_tours[,.(avgTourTime=mean(tourTime), sdTourTime=sd(tourTime), avgTourVMT=mean(tourVMT), sdTourVMT=sd(tourVMT)), by=.(runLabel,business,vehicleClass)]
# baseline_tours_summary <- data.table::data.table(rbind(baseline_tours_summary[runLabel=="2018"], data.table::data.table(
#   runLabel=c("2018", "2018"),
#   business=c("B2C", "B2C"),
#   vehicleClass=c("Class 7&8 Vocational", "Class 7&8 Tractor"),
#   avgTourTime=c(0, 0),
#   avgTourVMT=c(0, 0),
#   sdTourTime=c(0, 0),
#   sdTourVMT=c(0, 0)
# )))

baseline_tours <- baseline_runs[order(vehicle,time),
                                .(tourTime=last(arrivalTime)-first(departureTime), 
                                  tourVMT=sum(length/1609.344)),
                                by=.(vehicle,runLabel,business,vehicleClass)]
# baseline_tours <- data.table::data.table(rbind(baseline_tours, data.table::data.table(
#   vehicle=c("",""),
#   runLabel=c("2018", "2018"),
#   business=c("B2C", "B2C"),
#   vehicleClass=c("Class 7&8 Vocational", "Class 7&8 Tractor"),
#   tourTime=c(0, 0),
#   tourVMT=c(0, 0)
# )))

# 


write.csv(
  baseline_tours[tourVMT>=350],
  file = pp(baseline_output_dir, pp("baseline-tours-350miles_", city, ".csv")),
  row.names=F,
  quote=T)

write.csv(
  test,
  file = pp(baseline_output_dir, pp("baseline-test_", city, ".csv")),
  row.names=F,
  quote=T)

ggplot(test, aes(time/3600, length/1609)) + 
  geom_line() +
  theme_marain() +
  labs(x="hour", y="VMT")


network <- readCsv(pp(baseline_runs_dir, "/../../network2.csv.gz"))
test2 <- readCsv(pp(baseline_runs_dir, "2018_dense/filtered.0.events.csv.gz"))
test2_veh <- test2[vehicle=="freightVehicle-b2b-all-1091441-1-26hdv-d-0-456"]
test2_veh_time <- test2_veh[time==74693]

#library(splitstackshape)
#test2_veh_time_solit <- cSplit(test2_veh_time, splitCols = "links", sep = ",", direction = "wide", drop = FALSE)
#gather(test2_veh_time_solit, "key", "value", x, y, z)

test2_veh_time_split <- test2_veh_time %>% separate_rows(links, sep = ",")
filtered_network <- network[linkId %in% test2_veh_time_split$links]

filtered_network[,wkt_test:=pp("LINESTRING (", fromLocationX, " ", fromLocationY, ", ", toLocationX, " ", toLocationY, ")")]
write.csv(
  filtered_network,
  file = pp(baseline_output_dir, pp("../filtered_network_2.csv")),
  row.names=F,
  quote=T)

network[,wkt_test:=pp("LINESTRING (", fromLocationX, " ", fromLocationY, ", ", toLocationX, " ", toLocationY, ")")]
write.csv(
  network,
  file = pp(baseline_output_dir, pp("../test_network_2.csv")),
  row.names=F,
  quote=T)





##
baseline_tours_levels <- c("Class 4-6 Vocational", "Class 7&8 Vocational", "Class 7&8 Tractor")
baseline_tours$vehicleClass <- factor(baseline_tours$vehicleClass, levels=baseline_tours_levels)

p<-ggplot(baseline_tours, aes(business, tourVMT, color=vehicleClass)) + 
  geom_boxplot() +
  #geom_boxplot(outlier.shape = NA, notch = T) +
  labs(y='VMT', x='Business Type', color='Vehicle Type', title='Tour VMT Distribution') +
  scale_color_manual(values=c("azure3", "darkgray", "azure4")) +
  theme_marain() +
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10)) +
  facet_grid(. ~ runLabel)
p


p<-ggplot(baseline_tours_summary[runLabel=="2018"], aes(business, avgTourTime/3600, color=vehicleClass)) +
  geom_bar(stat='identity', position = position_dodge(width = 0.7), alpha=0.2) +
  geom_errorbar(aes(ymin=(avgTourTime-sdTourTime)/3600, ymax=(avgTourTime+sdTourTime)/3600, color=vehicleClass), width=.2, position = position_dodge(width = 0.7)) +
  labs(y='Hours', x='', color='Vehicle Type', title='Average Tour Time') +
  scale_color_manual(values=c("azure3", "darkgray", "azure4")) +
  theme_marain() +
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10)) 
p


## TRIPS *****

baseline_trips <- baseline_runs[order(vehicle,time),.(
  avgTripsTime=mean(arrivalTime-departureTime), 
  avgTripsVMT=mean(length)/1609.344,
  sdTripsTime=sd(arrivalTime-departureTime),
  sdTripsVMT=sd(length)/1609.34),by=.(runLabel,business,vehicleClass)]
baseline_trips_summary <- data.table::data.table(rbind(baseline_trips, data.table::data.table(
  runLabel=c("2018", "2018"),
  business=c("B2C", "B2C"),
  vehicleClass=c("Class 7&8 Vocational", "Class 7&8 Tractor"),
  avgTripsTime=c(0, 0),
  avgTripsVMT=c(0, 0),
  sdTripsTime=c(0, 0),
  sdTripsVMT=c(0, 0)
)))


p<-ggplot(baseline_trips, aes(business, avgTripsVMT, color=vehicleClass)) +
  geom_bar(stat='identity', position = position_dodge(width = 0.7), alpha=0.2) +
  geom_errorbar(aes(ymin=avgTripsVMT-sdTripsVMT, ymax=avgTripsVMT+sdTripsVMT, color=vehicleClass), width=.2, position = position_dodge(width = 0.7)) +
  labs(y='VMT', x='Business Type', color='Vehicle Type', title='Average Trips VMT') +
  scale_color_manual(values=c("azure3", "darkgray", "azure4")) +
  theme_marain() +
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10))  +
  facet_grid(. ~ runLabel)
p

p<-ggplot(baseline_trips_summary, aes(business, avgTripsTime/60, color=vehicleClass)) +
  geom_bar(stat='identity', position = position_dodge(width = 0.7), alpha=0.2) +
  geom_errorbar(aes(ymin=(avgTripsTime-sdTripsTime)/60, ymax=(avgTripsTime+sdTripsTime)/60, color=vehicleClass), width=.2, position = position_dodge(width = 0.7)) +
  labs(y='Minutes', x='Business Type', color='Vehicle Type', title='Average Tour Time') +
  scale_color_manual(values=c("azure3", "darkgray", "azure4")) +
  theme_marain() +
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10)) 
p




write.csv(
  baseline_tours_summary[baseline_trips_summary, on=c("runLabel", "business", "vehicleClass")],
  file = pp(demand_growth_output_dir, pp("baseline_tours-trips-summary_", city, ".csv")),
  row.names=F,
  quote=T)






# ***************************************
# ********** VEHICLE TECH & COSTS *******
# ***************************************


baseline_runs_dir <- pp(workDir, "beam/runs/vehicle-cost-sensitivity/")
baseline_output_dir <- pp(baseline_runs_dir, "output/")
dir.create(baseline_output_dir, showWarnings = FALSE)

baseline_runs_labels <- c("HOP_highp2", "HOP_highp6", "Ref_highp2", "Ref_highp6")
baseline_runs <- 
  read_freight_events(
    c("2018", "2018_Old"), 
    baseline_runs_labels, 
    baseline_runs_dir,
    "all_b2b_baseline"
  )
baseline_runs <- format_path_traversals(baseline_runs)

baseline_summary <- baseline_runs[,
                                  .(
                                    MVMT=expansionFactor*sum(length/1609.344)/1e+6,
                                    GWH=expansionFactor*sum(primaryFuel/3.6e+12)
                                  ),
                                  by=.(energyTypeCode,vehicleClass,business,runLabel)
]







# ***************************************
# ************ DEMAND GROWTH ************
# ***************************************
demand_growth_runs_dir <- pp(workDir, "beam/runs/demand-growth/")
demand_growth_output_dir <- pp(demand_growth_runs_dir, "output/")
dir.create(demand_growth_output_dir, showWarnings = FALSE)

# ************ DEMAND GROWTH - B2B
dgb2b_runs_labels <- c("2018", "2040")
dgb2b_runs <- 
  read_freight_events(
    c("2018",  "2040_b2b_growth_b2c_growth"),
    dgb2b_runs_labels, 
    demand_growth_runs_dir,
    "all_b2b_growth"
  )
dgb2b_runs <- format_path_traversals(dgb2b_runs)

dgb2b_summary <- dgb2b_runs[,
                             .(
                               MVMT=expansionFactor*sum(length/1609.344)/1e+6,
                               GWH=expansionFactor*sum(primaryFuel/3.6e+12)
                               ),
                             by=.(energyTypeCode,vehicleClass,business,runLabel)
                             ]
#energy_vehType_vmt[,totVMTByScenario:=sum(MVMT),by=.(runLabel)]
dgb2b_summary[,energyAndVehiclesTypes:=paste(energyTypeCode,vehicleClass,sep=" ")]
dgb2b_summary_levels <- c("BEV Class 4-6 Vocational", "BEV Class 7&8 Vocational", "BEV Class 7&8 Tractor",
                           "Diesel Class 4-6 Vocational", "Diesel Class 7&8 Vocational", "Diesel Class 7&8 Tractor")
dgb2b_summary$energyAndVehiclesTypes <- factor(dgb2b_summary$energyAndVehiclesTypes, levels = dgb2b_summary_levels)
write.csv(
  dgb2b_summary,
  file = pp(demand_growth_output_dir, pp("demand-growth-b2b_VMT-and-GWH-by-powertrain-class_", city,".csv")),
  row.names=F,
  quote=T)




# ****** DEMAND GROWTH - B2B - VMT
p<-ggplot(dgb2b_summary[business=="B2B"], aes(factor(runLabel, level=dgb2b_runs_labels), MVMT, fill=energyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  facet_grid(. ~ business) +
  labs(y='VMT',x='Scenario',fill='Powertrain - Class', title='Total Truck Travel - Demand Growth')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10))+
  #scale_fill_manual(values=c("deepskyblue2", "azure3", "darkgray", "azure4"))
scale_fill_manual(values=c("deepskyblue2", "deepskyblue3", "deepskyblue4", "azure3", "darkgray", "azure4"))
ggsave(pp(demand_growth_output_dir, pp("demand-growth-b2b_VMT-by-powertrain-class_", city, ".png")),p,width=6,height=4,units='in')

# ****** DEMAND GROWTH - B2B - Energy
p<-ggplot(dgb2b_summary[business=="B2B"], aes(factor(runLabel, level=dgb2b_runs_labels), GWH, fill=energyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  facet_grid(. ~ business) +
  labs(y='GWh',x='Scenario',fill='Powertrain - Class', title='Energy Consumption - Demand Growth')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10))+
  scale_fill_manual(values=c("deepskyblue2", "azure3", "darkgray", "azure4"))
#  scale_fill_manual(values=c("deepskyblue2", "deepskyblue3", "deepskyblue4", "azure3", "darkgray", "azure4"))
ggsave(pp(demand_growth_output_dir, pp("demand-growth-b2b_GWH-by-powertrain-class_", city, ".png")),p,width=6,height=4,units='in')

# ****** DEMAND GROWTH - B2B - Processing
dgb2b_tours <- dgb2b_runs[business=="B2B"][order(vehicle,time),.(tourTime=last(arrivalTime)-first(departureTime), tourVMT=sum(length/1609.344), tourGWH=sum(primaryFuel/3.6e+12)),by=.(vehicle,runLabel)]
dgb2b_tours_summary <- dgb2b_tours[,.(avgTourTime=mean(tourTime), avgTourVMT=mean(tourVMT), totVMT=sum(tourVMT), totGWH=sum(tourGWH)), by=.(runLabel)]


dgb2b_all_tours <- dgb2b_runs[order(vehicle,time),.(tourTime=last(arrivalTime)-first(departureTime), tourVMT=sum(length/1609.344), tourGWH=sum(primaryFuel/3.6e+12)),by=.(vehicle,runLabel,business)]
dgb2b_all_tours_summary <- dgb2b_all_tours[,.(avgTourTime=mean(tourTime), avgTourVMT=mean(tourVMT), totVMT=sum(tourVMT), totGWH=sum(tourGWH)), by=.(runLabel, business)]
write.csv(
  dgb2b_all_tours_summary,
  file = pp(demand_growth_output_dir, pp("demand-growth_tours-summary_", city, ".csv")),
  row.names=F,
  quote=T)



# ************ DEMAND GROWTH - B2C
dgb2c_runs_labels <- c("Base",  "120%", "140%", "160%", "180%")
dgb2c_runs <- 
  read_freight_events(
    c("2040_b2b_growth_b2c_growth",  "2040_b2b_growth_b2c_growth_G120", 
      "2040_b2b_growth_b2c_growth_G140",  "2040_b2b_growth_b2c_growth_G160", 
      "2040_b2b_growth_b2c_growth_G180"), 
    dgb2c_runs_labels, 
    demand_growth_runs_dir,
    "all_b2c_growth"
  )
dgb2c_runs <- format_path_traversals(dgb2c_runs)

dgb2c_summary <- dgb2c_runs[,
                            .(
                              MVMT=expansionFactor*sum(length/1609.344)/1e+6,
                              GWH=expansionFactor*sum(primaryFuel/3.6e+12)
                              ),
                            by=.(energyTypeCode,vehicleClass,business,runLabel)]
#dgb2c_summary[,totVMTByScenario:=sum(MVMT),by=.(runLabel)]
dgb2c_summary[,energyAndVehiclesTypes:=paste(energyTypeCode,vehicleClass,sep=" ")]
dgb2c_summary_levels <- c("BEV Class 4-6 Vocational", "BEV Class 7&8 Vocational", "BEV Class 7&8 Tractor", 
                           "Diesel Class 4-6 Vocational", "Diesel Class 7&8 Vocational", "Diesel Class 7&8 Tractor")
dgb2c_summary$energyAndVehiclesTypes <- factor(dgb2c_summary$energyAndVehiclesTypes, levels = dgb2c_summary_levels)
write.csv(
  dgb2c_summary,
  file = pp(demand_growth_output_dir, pp("demand-growth-b2c_VMT-and-GWH-by-powertrain-class_", city, ".csv")),
  row.names=F,
  quote=T)

# ****** DEMAND GROWTH - B2C - VMT
p<-ggplot(dgb2c_summary[business=="B2C"], aes(factor(runLabel, level=dgb2c_runs_labels), MVMT, fill=energyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  #facet_grid(. ~ business) +
  labs(y='VMT',x='Scenario',fill='Powertrain - Class', title='Total Truck Travel - 2040 B2C Demand Growth')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10))+  
  #scale_fill_manual(values=c("azure3"))
  scale_fill_manual(values=c("deepskyblue2", "azure3"))
ggsave(pp(demand_growth_output_dir, pp("demand-growth-b2c_VMT-by-powertrain-class_", city, ".png")),p,width=6,height=4,units='in')

# ****** DEMAND GROWTH - B2C - Energy
p<-ggplot(dgb2c_summary[business=="B2C"], aes(factor(runLabel, level=dgb2c_runs_labels), GWH, fill=energyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  #facet_grid(. ~ business) +
  labs(y='GWh',x='Scenario',fill='Powertrain - Class', title='Energy Consumption - 2040 B2C Demand Growth')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10))+
  #scale_fill_manual(values=c("azure3"))
  scale_fill_manual(values=c("deepskyblue2", "azure3"))
ggsave(pp(demand_growth_output_dir, pp("demand-growth-b2c_GWH-by-powertrain-class_", city, ".png")),p,width=6,height=4,units='in')


# ****** DEMAND GROWTH - B2C - Processing
dgb2c_tours <- dgb2c_runs[business=="B2C"][order(vehicle,time),.(tourTime=last(arrivalTime)-first(departureTime), tourVMT=sum(length)/1609.344, tourGWH=sum(primaryFuel/3.6e+12)),by=.(vehicle,runLabel)]
dgb2c_tours_summary <- dgb2c_tours[,.(avgTourTime=mean(tourTime), avgTourVMT=mean(tourVMT), totVMT=sum(tourVMT), totGWH=sum(tourGWH)), by=.(runLabel)]

dgb2c_all_tours <- dgb2c_runs[order(vehicle,time),.(tourTime=last(arrivalTime)-first(departureTime), tourVMT=sum(length)/1609.344, tourGWH=sum(primaryFuel/3.6e+12)),by=.(vehicle,runLabel,business)]
dgb2c_all_tours_summary <- dgb2c_all_tours[,.(avgTourTime=mean(tourTime), avgTourVMT=mean(tourVMT), totVMT=sum(tourVMT), totGWH=sum(tourGWH)), by=.(runLabel,business)]
write.csv(
  dgb2c_all_tours_summary,
  file = pp(demand_growth_output_dir, pp("demand-growth-b2c_tours-summary_", city, ".csv")),
  row.names=F,
  quote=T)

### *************** ************** **************
### *************** B2C & B2B      **************
### *************** ************** **************
dg_runs_labels <- c("2018", "2040")
dg_runs <- 
  read_freight_events(
    c("2018",  "2040_b2b_growth_b2c_growth"),
    dg_runs_labels, 
    demand_growth_runs_dir,
    "all_demand_growth"
  )
dg_runs <- format_path_traversals(dg_runs)

dg_summary <- dg_runs[,
                            .(
                              MVMT=expansionFactor*sum(length/1609.344)/1e+6,
                              GWH=expansionFactor*sum(primaryFuel/3.6e+12)
                            ),
                            by=.(energyTypeCode,vehicleClass,business,runLabel)
]
#energy_vehType_vmt[,totVMTByScenario:=sum(MVMT),by=.(runLabel)]
dg_summary[,energyAndVehiclesTypes:=paste(energyTypeCode,vehicleClass,sep=" ")]
dg_summary_levels <- c("BEV Class 4-6 Vocational", "BEV Class 7&8 Vocational", "BEV Class 7&8 Tractor",
                          "Diesel Class 4-6 Vocational", "Diesel Class 7&8 Vocational", "Diesel Class 7&8 Tractor")
dg_summary$energyAndVehiclesTypes <- factor(dg_summary$energyAndVehiclesTypes, levels = dg_summary_levels)
write.csv(
  dg_summary,
  file = pp(demand_growth_output_dir, pp("demand-growth_VMT-and-GWH-by-powertrain-class_", city,".csv")),
  row.names=F,
  quote=T)


## TOURS *****
dg_tours <- dg_runs[order(vehicle,time),.(tourTime=last(arrivalTime)-first(departureTime), tourVMT=sum(length/1609.344)),by=.(vehicle,runLabel,business,vehicleClass)]
dg_tours_summary <- dg_tours[,.(avgTourTime=mean(tourTime), sdTourTime=sd(tourTime), avgTourVMT=mean(tourVMT), sdTourVMT=sd(tourVMT)), by=.(runLabel,business,vehicleClass)]
#baseline_tours_summary[order(business,runLabel)]

dg_tours_summary <- data.table::data.table(rbind(dg_tours_summary, data.table::data.table(
  runLabel=c("2018", "2018", "2040", "2040"),
  business=c("B2C", "B2C", "B2C", "B2C"),
  vehicleClass=c("Class 7&8 Vocational", "Class 7&8 Tractor", "Class 7&8 Vocational", "Class 7&8 Tractor"),
  avgTourTime=c(0, 0, 0, 0),
  avgTourVMT=c(0, 0, 0, 0),
  sdTourTime=c(0, 0, 0, 0),
  sdTourVMT=c(0, 0, 0, 0)
)))


dg_tours_summary_levels <- c("Class 4-6 Vocational", "Class 7&8 Vocational", "Class 7&8 Tractor")
dg_tours_summary$vehicleClass <- factor(dg_tours_summary$vehicleClass, levels = dg_tours_summary_levels)


p<-ggplot(dg_tours_summary, aes(business, avgTourVMT, color=vehicleClass)) +
  geom_bar(stat='identity', position = position_dodge(width = 0.7), alpha=.2) +
  geom_errorbar(aes(ymin=avgTourVMT-sdTourVMT, ymax=avgTourVMT+sdTourVMT, color=vehicleClass), width=.2, position = position_dodge(width = 0.7)) +
  labs(y='VMT', x='Business Type', color='Vehicle Type', title='Average Tour VMT') +
  scale_color_manual(values=c("azure3", "darkgray", "azure4")) +
  facet_grid(. ~ runLabel) +
  theme_marain() +
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10)) 
p

p<-ggplot(dg_tours_summary, aes(business, avgTourTime/3600, color=vehicleClass)) +
  geom_bar(stat='identity', position = position_dodge(width = 0.7), alpha=.2) +
  geom_errorbar(aes(ymin=(avgTourTime-sdTourTime)/3600, ymax=(avgTourTime+sdTourTime)/3600, color=vehicleClass), width=.2, position = position_dodge(width = 0.7)) +
  labs(y='Hours', x='Business Type', color='Vehicle Type', title='Average Tour Time') +
  scale_color_manual(values=c("azure3", "darkgray", "azure4")) +
  facet_grid(. ~ runLabel) +
  theme_marain() +
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10)) 
p


## TRIPS *****

dg_trips <- dg_runs[order(vehicle,time),.(
  avgTripsTime=mean(arrivalTime-departureTime), 
  avgTripsVMT=mean(length)/1609.344,
  sdTripsTime=sd(arrivalTime-departureTime),
  sdTripsVMT=sd(length)/1609.34)
  ,by=.(runLabel,business,vehicleClass)]

dg_trips_summary <- data.table::data.table(rbind(dg_trips, data.table::data.table(
  runLabel=c("2018", "2018", "2040", "2040"),
  business=c("B2C", "B2C", "B2C", "B2C"),
  vehicleClass=c("Class 7&8 Vocational", "Class 7&8 Tractor", "Class 7&8 Vocational", "Class 7&8 Tractor"),
  avgTripsTime=c(0, 0, 0, 0),
  avgTripsVMT=c(0, 0, 0, 0),
  sdTripsTime=c(0, 0, 0, 0),
  sdTripsVMT=c(0, 0, 0, 0)
)))


p<-ggplot(dg_trips_summary, aes(business, avgTripsVMT, color=vehicleClass)) +
  geom_bar(stat='identity', position = position_dodge(width = 0.7), alpha=.2) +
  geom_errorbar(aes(ymin=avgTripsVMT-sdTripsVMT, ymax=avgTripsVMT+sdTripsVMT, color=vehicleClass), width=.2, position = position_dodge(width = 0.7)) +
  labs(y='VMT', x='Business Type', color='Vehicle Type', title='Average Trips VMT') +
  scale_color_manual(values=c("azure3", "darkgray", "azure4")) +
  facet_grid(. ~ runLabel) +
  theme_marain() +
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10)) 
p

p<-ggplot(dg_trips_summary, aes(business, avgTripsTime/60, color=vehicleClass)) +
  geom_bar(stat='identity', position = position_dodge(width = 0.7), alpha=.2) +
  geom_errorbar(aes(ymin=(avgTripsTime-sdTripsTime)/60, ymax=(avgTripsTime+sdTripsTime)/60, color=vehicleClass), width=.2, position = position_dodge(width = 0.7)) +
  labs(y='Minutes', x='Business Type', color='Vehicle Type', title='Average Tour Time') +
  scale_color_manual(values=c("azure3", "darkgray", "azure4")) +
  facet_grid(. ~ runLabel) +
  theme_marain() +
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10)) 
p


write.csv(
  dg_tours_summary[dg_trips_summary, on=c("runLabel", "business", "vehicleClass")],
  file = pp(demand_growth_output_dir, pp("demand-growth_tours-trips-summary_", city, ".csv")),
  row.names=F,
  quote=T)

# ****************************************
# ************ LOCKER DELIVERY ***********
# ****************************************
locker_delivery_runs_dir <- pp(workDir, "beam/runs/locker-delivery/")
locker_delivery_output_dir <- pp(locker_delivery_runs_dir, "output/")
dir.create(locker_delivery_output_dir, showWarnings = FALSE)

# ************ LOCKER DELIVERY - ADOPTION
lda_runs_labels <- c("20%", "40%", "60%")
lda_runs <- 
  read_freight_events(
    c("2040_Con_p20_e0",  "2040_Con_p40_e0", "2040_Con_p60_e0"), 
    lda_runs_labels, 
    locker_delivery_runs_dir,
    "all_adoption"
  )
lda_runs <- format_path_traversals(lda_runs)

lda_summary <- lda_runs[,
                            .(
                              MVMT=expansionFactor*sum(length/1609.344)/1e+6,
                              GWH=expansionFactor*sum(primaryFuel/3.6e+12)
                            ),
                            by=.(energyTypeCode,vehicleClass,business,runLabel)][business=="B2C"]
#dgb2c_summary[,totVMTByScenario:=sum(MVMT),by=.(runLabel)]
lda_summary[,energyAndVehiclesTypes:=paste(energyTypeCode,vehicleClass,sep=" ")]
lda_summary_levels <- c("BEV Class 7&8 Tractor", 
                          "Diesel Class 4-6 Vocational", "Diesel Class 7&8 Vocational", "Diesel Class 7&8 Tractor")
lda_summary$energyAndVehiclesTypes <- factor(lda_summary$energyAndVehiclesTypes, levels = lda_summary_levels)
write.csv(
  lda_summary,
  file = pp(locker_delivery_output_dir, pp("locker-delivery-adoption_VMT-and-GWH-by-powertrain-class.csv")),
  row.names=F,
  quote=T)

# ****** LOCKER DELIVERT - ADOPTION - VMT
p<-ggplot(lda_summary, aes(factor(runLabel, level=lda_runs_labels), MVMT, fill=energyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  facet_grid(. ~ business) +
  labs(y='VMT',x='Scenario',fill='Powertrain - Class', title='Total Truck Travel - Adoption Of Amazon Lockers')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10))+
  scale_fill_manual(values=c("azure3"))
ggsave(pp(locker_delivery_output_dir, pp("locker-delivery-adoption_VMT-by-powertrain-class.png")),p,width=5,height=4,units='in')

# ****** LOCKER DELIVERT - ADOPTION - Energy
p<-ggplot(lda_summary, aes(factor(runLabel, level=lda_runs_labels), GWH, fill=energyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  facet_grid(. ~ business) +
  labs(y='GWh',x='Scenario',fill='Powertrain - Class', title='Energy Consumption - Adoption Of Amazon Lockers')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10))+
  scale_fill_manual(values=c("azure3"))
ggsave(pp(locker_delivery_output_dir, pp("locker-delivery-adoption_GWH-by-powertrain-class.png")),p,width=5,height=4,units='in')


# ****** LOCKER DELIVERT - ADOPTION - Processing
lda_tours <- lda_runs[business=="B2C"][order(vehicle,time),.(tourTime=last(arrivalTime)-first(departureTime), tourVMT=sum(length)/1609.344, tourGWH=sum(primaryFuel/3.6e+12)),by=.(vehicle,runLabel)]
dgb2c_tours <- dgb2c_runs[runName=="2040_b2c_growth_140p"][business=="B2C"][order(vehicle,time),.(tourTime=last(arrivalTime)-first(departureTime), tourVMT=sum(length)/1609.344, tourGWH=sum(primaryFuel/3.6e+12)),by=.(vehicle,runLabel)]

lda_tours_summary <- rbind(lda_tours, dgb2c_tours)[,.(avgTourTime=mean(tourTime), avgTourVMT=mean(tourVMT), totVMT=sum(tourVMT), totGWH=sum(tourGWH)), by=.(runLabel)]

# ************ LOCKER DELIVERY - SERVICE 
lds_runs_labels <- c("Base", "30%", "60%", "90%")
lds_runs <- 
  read_freight_events(
    c("2040_Con_p40_e0", "2040_Con_p40_e30", "2040_Con_p40_e60", "2040_Con_p40_e90"), 
    lds_runs_labels, 
    locker_delivery_runs_dir,
    "all_adoption"
  )
lds_runs <- format_path_traversals(lds_runs)

lds_summary <- lds_runs[,
                        .(
                          MVMT=expansionFactor*sum(length/1609.344)/1e+6,
                          GWH=expansionFactor*sum(primaryFuel/3.6e+12)
                        ),
                        by=.(energyTypeCode,vehicleClass,business,runLabel)][business=="B2C"]
#dgb2c_summary[,totVMTByScenario:=sum(MVMT),by=.(runLabel)]
lds_summary[,energyAndVehiclesTypes:=paste(energyTypeCode,vehicleClass,sep=" ")]
lds_summary_levels <- c("BEV Class 7&8 Tractor", 
                        "Diesel Class 4-6 Vocational", "Diesel Class 7&8 Vocational", "Diesel Class 7&8 Tractor")
lds_summary$energyAndVehiclesTypes <- factor(lds_summary$energyAndVehiclesTypes, levels = lds_summary_levels)
write.csv(
  lds_summary,
  file = pp(locker_delivery_output_dir, pp("locker-delivery-service_VMT-and-GWH-by-powertrain-class.csv")),
  row.names=F,
  quote=T)

# ****** LOCKER DELIVERT - SERVICE - VMT
p<-ggplot(lds_summary, aes(factor(runLabel, level=lds_runs_labels), MVMT, fill=energyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  facet_grid(. ~ business) +
  labs(y='VMT',x='Scenario',fill='Powertrain - Class', title='Total Truck Travel - Grocery Stores')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10))+
  scale_fill_manual(values=c("azure3"))
ggsave(pp(locker_delivery_output_dir, pp("locker-delivery-service_VMT-by-powertrain-class.png")),p,width=5,height=4,units='in')

# ****** LOCKER DELIVERT - SERVICE - Energy
p<-ggplot(lds_summary, aes(factor(runLabel, level=lds_runs_labels), GWH, fill=energyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  facet_grid(. ~ business) +
  labs(y='GWh',x='Scenario',fill='Powertrain - Class', title='Energy Consumption - Grocery Stores')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10))+
  scale_fill_manual(values=c("azure3"))
ggsave(pp(locker_delivery_output_dir, pp("locker-delivery-service_GWH-by-powertrain-class.png")),p,width=5,height=4,units='in')


# ****** LOCKER DELIVERT - SERVICE - Processing
lds_tours <- lds_runs[business=="B2C"][order(vehicle,time),.(tourTime=last(arrivalTime)-first(departureTime), tourVMT=sum(length)/1609.344, tourGWH=sum(primaryFuel/3.6e+12)),by=.(vehicle,runLabel)]
lds_tours_summary <- lds_tours[,.(avgTourTime=mean(tourTime), avgTourVMT=mean(tourVMT), totVMT=sum(tourVMT), totGWH=sum(tourGWH)), by=.(runLabel)]


dgb2b_tours <- dgb2b_runs[business=="B2B"][order(vehicle,time),.(tourTime=last(arrivalTime)-first(departureTime), tourVMT=sum(length/1609.344), tourGWH=sum(primaryFuel/3.6e+12)),by=.(vehicle,runLabel)]
dgb2b_tours_summary <- dgb2b_tours[,.(avgTourTime=mean(tourTime), avgTourVMT=mean(tourVMT), totVMT=sum(tourVMT), totGWH=sum(tourGWH)), by=.(runLabel)]


# ****************************************
# ************ COST SENSITIVITY **********
# ****************************************

cost_sensitivity_runs_dir <- pp(workDir, "beam/runs/cost-sensitivity/")
cost_sensitivity_output_dir <- pp(cost_sensitivity_runs_dir, "output/")
dir.create(cost_sensitivity_output_dir, showWarnings = FALSE)


# ************ COST SENSITIVITY - REF

csref_runs_labels <- c("High DL\n0.6x Elec.", 
                       "Ref. DL\n0.6x Elec.",
                       "High DL\n1.0x Elec.", 
                       "Ref. DL\n1.0x Elec.")
csref_runs <- 
  read_freight_events(
    c("HOP_highp2", "Ref_highp2", "HOP_highp6", "Ref_highp6"), 
    csref_runs_labels, 
    cost_sensitivity_runs_dir,
    "all_ref"
  )
csref_runs <- format_path_traversals(csref_runs)
csref_summary <- csref_runs[,
                      .(
                        MVMT=expansionFactor*sum(length/1609.344)/1e+6,
                        GWH=expansionFactor*sum(primaryFuel/3.6e+12)
                      ),
                      by=.(energyTypeCode,vehicleClass,runLabel)]
csref_summary[,`:=`(totMVMT=sum(MVMT),totMGWH=sum(GWH)),by=.(runLabel)]
csref_summary[,GWHByClass:=sum(GWH),by=.(vehicleClass,runLabel)]
csref_summary[,energyAndVehiclesTypes:=paste(energyTypeCode,vehicleClass,sep=" ")]
csref_summary$energyTypeCode2 <- "Diesel"
csref_summary[energyTypeCode=="BEV"]$energyTypeCode2 <- "EV"
csref_summary[energyTypeCode=="PHEV"]$energyTypeCode2 <- "EV"
csref_summary[energyTypeCode=="H2FC"]$energyTypeCode2 <- "H2FC"
csref_summary_levels <- c("BEV Class 4-6 Vocational", "BEV Class 7&8 Vocational", "BEV Class 7&8 Tractor",
                       "PHEV Class 4-6 Vocational", "PHEV Class 7&8 Vocational", "PHEV Class 7&8 Tractor",
                       "H2FC Class 4-6 Vocational", "H2FC Class 7&8 Vocational", "H2FC Class 7&8 Tractor",
                       "Diesel Class 4-6 Vocational", "Diesel Class 7&8 Vocational", "Diesel Class 7&8 Tractor")
csref_summary$energyAndVehiclesTypes <- factor(csref_summary$energyAndVehiclesTypes, levels = csref_summary_levels)
write.csv(
  csref_summary,
  file = pp(cost_sensitivity_output_dir, pp("cost-sensitivity-high-ref_VMT-and-GWH-by-powertrain-class.csv")),
  row.names=F,
  quote=T)

# ****** COST SENSITIVITY - REF - Energy
p<-ggplot(csref_summary, aes(factor(runLabel, level=csref_runs_labels), GWH/GWHByClass, fill=energyTypeCode2)) +
  geom_bar(stat='identity') +
  facet_grid(. ~ vehicleClass) +
  labs(y='% GWH',x='Scenario',fill='Vehicle Type', title='Austin - Energy Consumption - High/Reference Oil Price')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=12),
        legend.position = "top")+
  scale_fill_manual(values=c("darkgray","deepskyblue3", "chartreuse3"))
ggsave(pp(cost_sensitivity_output_dir, pp("cost-sensitivity-high-ref_normalized-GWH-by-powertrain-class.png")),p,width=10,height=4,units='in')




csref_trips_summary <- csref_runs[order(vehicle,time),.(
  avgTripsTime=mean(arrivalTime-departureTime), 
  avgTripsVMT=mean(length)/1609.344,
  sdTripsTime=sd(arrivalTime-departureTime),
  sdTripsVMT=sd(length)/1609.34),by=.(runLabel,business,vehicleClass)]
# csref_trips_summary <- data.table::data.table(rbind(csref_trips, data.table::data.table(
#   runLabel=c("2018", "2018"),
#   business=c("B2C", "B2C"),
#   vehicleClass=c("Class 7&8 Vocational", "Class 7&8 Tractor"),
#   avgTripsTime=c(0, 0),
#   avgTripsVMT=c(0, 0),
#   sdTripsTime=c(0, 0),
#   sdTripsVMT=c(0, 0)
# )))


csref_tours_summary <- csref_runs[
  order(vehicle,time),.(tourTime=last(arrivalTime)-first(departureTime), tourVMT=sum(length/1609.344)), by=.(vehicle,runLabel,business,vehicleClass)
  ][
    ,.(avgTourTime=mean(tourTime), sdTourTime=sd(tourTime), avgTourVMT=mean(tourVMT), sdTourVMT=sd(tourVMT)), by=.(runLabel,business,vehicleClass)
    ]
#baseline_tours_summary[order(business,runLabel)]

# baseline_tours_summary <- data.table::data.table(rbind(baseline_tours_summary, data.table::data.table(
#   runLabel=c("2018", "2018"),
#   business=c("B2C", "B2C"),
#   vehicleClass=c("Class 7&8 Vocational", "Class 7&8 Tractor"),
#   avgTourTime=c(0, 0),
#   avgTourVMT=c(0, 0),
#   sdTourTime=c(0, 0),
#   sdTourVMT=c(0, 0)
# )))


write.csv(
  csref_tours_summary[csref_trips_summary, on=c("runLabel", "business", "vehicleClass")],
  file = pp(cost_sensitivity_output_dir, pp("cost-sensitivity-high-ref_tours-trips-summary_", city, ".csv")),
  row.names=F,
  quote=T)





# ************ COST SENSITIVITY - HOP
cshop_runs_labels <- c("0.6x\nElec.\nPrice", "1.0x\nElec.\nPrice", "1.4x\nElec.\nPrice")
cshop_runs <- 
  read_freight_events(
    c("2050_HOP_highp2", "2050_HOP_highp6", "2050_HOP_highp10"), 
    cshop_runs_labels, 
    cost_sensitivity_runs_dir,
    "all_hop"
  )
cshop_runs <- format_path_traversals(cshop_runs)
cshop_summary <- cshop_runs[,
                            .(
                              MVMT=expansionFactor*sum(length/1609.344)/1e+6,
                              GWH=expansionFactor*sum(primaryFuel/3.6e+12)
                            ),
                            by=.(energyTypeCode,vehicleClass,runLabel)]
cshop_summary[,`:=`(totMVMT=sum(MVMT),totMGWH=sum(GWH)),by=.(runLabel)]
cshop_summary[,GWHByClass:=sum(GWH),by=.(vehicleClass,runLabel)]
cshop_summary[,energyAndVehiclesTypes:=paste(energyTypeCode,vehicleClass,sep=" ")]
cshop_summary$energyTypeCode2 <- "Diesel"
cshop_summary[energyTypeCode=="BEV"]$energyTypeCode2 <- "EV"
cshop_summary[energyTypeCode=="PHEV"]$energyTypeCode2 <- "EV"
cshop_summary[energyTypeCode=="H2FC"]$energyTypeCode2 <- "H2FC"
cshop_summary_levels <- c("BEV Class 4-6 Vocational", "BEV Class 7&8 Vocational", "BEV Class 7&8 Tractor",
                          "PHEV Class 4-6 Vocational", "PHEV Class 7&8 Vocational", "PHEV Class 7&8 Tractor",
                          "H2FC Class 4-6 Vocational", "H2FC Class 7&8 Vocational", "H2FC Class 7&8 Tractor",
                          "Diesel Class 4-6 Vocational", "Diesel Class 7&8 Vocational", "Diesel Class 7&8 Tractor")
cshop_summary$energyAndVehiclesTypes <- factor(cshop_summary$energyAndVehiclesTypes, levels = cshop_summary_levels)
write.csv(
  cshop_summary,
  file = pp(cost_sensitivity_output_dir, pp("cost-sensitivity-hop_VMT-and-GWH-by-powertrain-class.csv")),
  row.names=F,
  quote=T)

# ****** COST SENSITIVITY - HOP - Energy
p<-ggplot(cshop_summary, aes(factor(runLabel, level=cshop_runs_labels), GWH/GWHByClass, fill=energyTypeCode2)) +
  geom_bar(stat='identity') +
  facet_grid(. ~ vehicleClass) +
  labs(y='% GWH',x='Scenario',fill='Vehicle Type', title='Energy Consumption - High Oil Price')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=12),
        legend.position = "top")+
  scale_fill_manual(values=c("darkgray","deepskyblue3", "chartreuse3"))
ggsave(pp(cost_sensitivity_output_dir, pp("cost-sensitivity-hop_normalized-GWH-by-powertrain-class.png")),p,width=8,height=4,units='in')




# ************ COST SENSITIVITY - REF & HOP
cs_runs_labels <- c("60%", "80%", "100%", "120%", "140%", 
                    "60%", "80%", "100%", "120%", "140%")
cs_runs_labels2 <- c("60%", "80%", "100%", "120%", "140%")
cs_runs <- 
  read_freight_events(
    c("2050_Ref_highp2",  "2050_Ref_highp4", "2050_Ref_highp6", "2050_Ref_highp8", "2050_Ref_highp10",
      "2050_HOP_highp2",  "2050_HOP_highp4", "2050_HOP_highp6", "2050_HOP_highp8", "2050_HOP_highp10"), 
    cs_runs_labels, 
    cost_sensitivity_runs_dir,
    "all_ref_hop"
  )
cs_runs <- format_path_traversals(cs_runs)
cs_runs$scenario <- "Reference Oil Price"
cs_runs[startsWith(runName, "2050_HOP")]$scenario <- "High Oil Price"
cs_summary <- cs_runs[,
                        .(
                          MVMT=expansionFactor*sum(length/1609.344)/1e+6,
                          GWH=expansionFactor*sum(primaryFuel/3.6e+12)
                        ),
                        by=.(energyTypeCode,vehicleClass,scenario,runLabel)]
cs_summary[,`:=`(totMVMT=sum(MVMT),totMGWH=sum(GWH)),by=.(runLabel)]
cs_summary[,energyAndVehiclesTypes:=paste(energyTypeCode,vehicleClass,sep=" ")]
cs_summary_levels <- c("BEV Class 4-6 Vocational", "BEV Class 7&8 Vocational", "BEV Class 7&8 Tractor",
                       "PHEV Class 4-6 Vocational", "PHEV Class 7&8 Vocational", "PHEV Class 7&8 Tractor",
                       "H2FC Class 4-6 Vocational", "H2FC Class 7&8 Vocational", "H2FC Class 7&8 Tractor",
                       "Diesel Class 4-6 Vocational", "Diesel Class 7&8 Vocational", "Diesel Class 7&8 Tractor")
cs_summary$energyAndVehiclesTypes <- factor(cs_summary$energyAndVehiclesTypes, levels = cs_summary_levels)
write.csv(
  cs_summary,
  file = pp(cost_sensitivity_output_dir, pp("cost-sensitivity_VMT-and-GWH-by-powertrain-class.csv")),
  row.names=F,
  quote=T)

# ****** COST SENSITIVITY - REF & HOP - VMT
p<-ggplot(cs_summary, aes(factor(runLabel, level=cs_runs_labels2), MVMT/totMVMT, fill=energyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  facet_grid( ~ scenario) +
  labs(y='VMT',x='Scenario',fill='Powertrain - Class', title='Total Truck Travel - Cost Sensitivity')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10))+
  scale_fill_manual(values=c("deepskyblue2","deepskyblue3", "deepskyblue4",
                             "mediumpurple1" , "purple1", "purple4",
                             "chartreuse2", "chartreuse3","chartreuse4",
                             "azure3","darkgray", "azure4"
  ))
ggsave(pp(cost_sensitivity_output_dir, pp("cost-sensitivity_VMT-by-powertrain-class.png")),p,width=5,height=4,units='in')

# ****** COST SENSITIVITY - REF & HOP - Energy
p<-ggplot(cs_summary, aes(factor(runLabel, level=cs_runs_labels2), GWH/totMGWH, fill=energyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  facet_grid(. ~ scenario) +
  labs(y='GWh',x='Scenario',fill='Powertrain - Class', title='Energy Consumption - Cost Sensitivity')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=12))+
  scale_fill_manual(values=c("deepskyblue2","deepskyblue3", "deepskyblue4",
                             "mediumpurple1" , "purple1", "purple4",
                             "chartreuse2", "chartreuse3","chartreuse4",
                             "azure3","darkgray", "azure4"
  ))
ggsave(pp(cost_sensitivity_output_dir, pp("cost-sensitivity_GWH-by-powertrain-class.png")),p,width=10,height=5,units='in')





















# ********

expansionFactor <- 1/0.5
city <- "austin"
cityCRS <- 26910
iteration <- 0
scenario <- "cost-sensitivity"

## PATHS
mainDir <- normalizePath("~/Workspace/Data/")
workDir <- pp(mainDir, "FREIGHT/", city, "/")
eventsFile <- pp(iteration,".events.csv.gz")
#linkStatsFile <- pp(iteration,".linkstats.csv.gz")
runDir <- pp(workDir, "beam/runs/", scenario, "/")
runOutputDir <- pp(runDir, "output/")
dir.create(runOutputDir, showWarnings = FALSE)

# runs <- c(
# "2050_Ref_highp2", "2050_Ref_highp4", "2050_Ref_highp6", "2050_Ref_highp8", "2050_Ref_highp10",
# "2050_HOP_highp2",  "2050_HOP_highp4", "2050_HOP_highp6",  "2050_HOP_highp8", "2050_HOP_highp10"
# )
# runs_label <- c(
#   "ROP-p2", "ROP-p4", "ROP-p6", "ROP-p8", "ROP-p10",
#   "HOP-p2",  "HOP-p4", "HOP-p6", "HOP-p8", "HOP-p10"
# )
runs <- c(
  "2050_HOP_highp2",  "2050_HOP_highp4", "2050_HOP_highp6",  "2050_HOP_highp8", "2050_HOP_highp10"
)
runs_label <- c(
"HOP-p2",  "HOP-p4", "HOP-p6", "HOP-p8", "HOP-p10"
)


# HOP 
hop_runs <- 
  read_freight_events(
    c("2018_base",  "2030_b2b_growth", "2040_b2b_growth",  "2050_b2b_growth"), 
    c("2018\nBase",  "2030\nB2B\nGrowth", "2040\nB2B\nGrowth", "2050\nB2B\nGrowth"), 
    pp(workDir, "beam/runs/demand-growth/")
  )




#pt[,.N,by=.(vehicle,run)][,.(count=.N*2),by=.(run)]
## ***
energy_consumption <- pt[,.(fuelGWH=expansionFactor*sum(primaryFuel/3.6e+12)),by=.(energyType,runLabel)]
write.csv(
  energy_consumption,
  file = pp(runOutputDir, pp(iteration,".freight-energy-consumption-by-powertrain.csv")),
  row.names=F,
  quote=T)

p<-ggplot(energy_consumption, aes(factor(runLabel, level=runs_label), fuelGWH, fill=energyType)) +
  geom_bar(stat='identity') +
  labs(y='GWh',x='Scenario',fill='Powertrain', title='Freight Energy Consumptio - 2050 HighTechn')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),strip.text = element_text(size=rel(1.2)))+
  scale_fill_manual(values=c("#999999", "#56B4E9", "#66A61E"))
ggsave(pp(runOutputDir, pp(iteration,".freight-energy-consumption-by-powertrain.png")),p,width=10,height=4,units='in')

## ***
#
energy_vmt <- pt[,.(MVMT=expansionFactor*sum(length/1609.344)/1000000),by=.(energyType,runLabel)]
write.csv(
  energy_vmt,
  file = pp(runOutputDir, pp(iteration,".freight-VMT-by-powertrain.csv")),
  row.names=F,
  quote=T)

p <- ggplot(energy_vmt, aes(factor(runLabel, level=runs_label), MVMT, fill=energyType)) +
  geom_bar(stat='identity') +
  labs(y='Million VMT',x='Scenario',fill='Energy Type', title='Freight VMT - 2050 HighTech')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),strip.text = element_text(size=rel(1.2)))+
  scale_fill_manual(values=c("#999999", "#56B4E9", "#66A61E"))
ggsave(pp(runOutputDir, pp(iteration,".freight-VMT-by-powertrain.png")),p,width=10,height=4,units='in')

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
  file = pp(runOutputDir, pp(iteration,".freight-VMT-by-powertrain-vehicletypes.csv")),
  row.names=F,
  quote=T)

#"goldenrod2", "goldenrod3", "goldenrod4",

p<-ggplot(energy_vehType_vmt, aes(factor(runLabel, level=runs_label), MVMT/totVMTByScenario, fill=EnergyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  labs(y='Relative VMT Share',x='Scenario',fill='Energy-Vehicle Type', title='Freight Volume')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),strip.text = element_text(size=rel(1.2)))+
  scale_fill_manual(values=c("deepskyblue2","deepskyblue3", "deepskyblue4",
                             "mediumpurple1" , "purple1", "purple4",
                             "chartreuse2", "chartreuse3","chartreuse4",
                             "azure3","darkgray", "azure4"
                             ))
ggsave(pp(runOutputDir, pp(iteration,".freight-VMT-by-powertrain-vehicletypes.png")),p,width=10,height=4,units='in')

## *** Cost Sensitivity

opcost_sensitivity_analysis <- readCsv(pp(workDir, "/frism/cost-sensitivity/opcost_sensitivity_analysis.csv"))
runs_label_2 <- c("Ref_p2", "Ref_p4", "Ref_p6", "Ref_p8", "Ref_p10", "HOP_p2",  "HOP_p4", "HOP_p6", "HOP_p8", "HOP_p10")

df <- data.table::data.table(gather(opcost_sensitivity_analysis, "label_unit", "cost", -Scenario_ID, -Diesel_Scenario, -Elec_Scenario))

df_filtered <- df[!label_unit %in% c("Diesel truck OP cost ($/mile)", "Non-fuel OP cost ($/mile)", "Rail OP cost ($/tonmile)")]
ggplot(df_filtered, aes(factor(Scenario_ID, level=runs_label_2), cost, fill=label_unit)) + 
  geom_bar(stat='identity', position = "dodge2") +
  theme_marain() + 
  labs(x = "Scenarios", y = "Cost", fill="Label") +
  theme(axis.text.x = element_text(angle = 0, hjust=0.5), 
        strip.text = element_text(size=rel(1.2))
        )



##### TEST
#network <- readCsv(pp(workDir, "beam/network.csv.gz"))
linkstats_base2018_passenger <- readCsv(pp(workDir, "validation/austin-2010-base-20220301/0.linkstats.csv.gz"))
#linkstats_base2018_plus <- merge(linkstats_base2018, network, by.x="link", by.y="linkId", all=TRUE) 
linkstats_base2018_freight <- readCsv(pp(workDir, "beam/runs/demand-growth/2018_base/0.linkstats.csv.gz"))

linkstats_base2018_passenger[,speed2:=length/traveltime]
linkstats_base2018_freight[,speed2:=length/traveltime]

average_speed_vector(linkstats_base2018_passenger$length,linkstats_base2018_passenger$speed2)
average_speed_vector(linkstats_base2018_freight$length,linkstats_base2018_freight$speed2)


mean(linkstats_base2018_passenger$speed2)
mean(linkstats_base2018_freight$speed2)
