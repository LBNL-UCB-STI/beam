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

expansionFactor <- 1/0.5
city <- "austin"
workDir <- pp(normalizePath("~/Workspace/Data/"), "/FREIGHT/", city, "/")



# ***************************************
# ************ DEMAND GROWTH ************
# ***************************************
demand_growth_runs_dir <- pp(workDir, "beam/runs/demand-growth/")
demand_growth_output_dir <- pp(demand_growth_runs_dir, "output/")
dir.create(demand_growth_output_dir, showWarnings = FALSE)

# ************ DEMAND GROWTH - B2B
dgb2b_runs_labels <- c("2018",  "2030", "2040", "2050")
dgb2b_runs <- 
  read_freight_events(
    c("2018_base",  "2030_b2b_growth", "2040_b2b_growth",  "2050_b2b_growth"), 
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
dgb2b_summary_levels <- c("BEV Class 7&8 Vocational", "BEV Class 7&8 Tractor", 
                           "Diesel Class 4-6 Vocational", "Diesel Class 7&8 Vocational", "Diesel Class 7&8 Tractor")
dgb2b_summary$energyAndVehiclesTypes <- factor(dgb2b_summary$energyAndVehiclesTypes, levels = dgb2b_summary_levels)
write.csv(
  dgb2b_summary,
  file = pp(demand_growth_output_dir, pp("demand-growth-b2b_VMT-and-GWH-by-powertrain-class.csv")),
  row.names=F,
  quote=T)

# ****** DEMAND GROWTH - B2B - VMT
p<-ggplot(dgb2b_summary[business!="B2C"], aes(factor(runLabel, level=dgb2b_runs_labels), MVMT, fill=energyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  facet_grid(. ~ business) +
  labs(y='VMT',x='Scenario',fill='Powertrain - Class', title='Total Truck Travel - B2B Demand Growth')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10))+
  scale_fill_manual(values=c("deepskyblue3", "deepskyblue4", "azure3", "darkgray", "azure4"))
ggsave(pp(demand_growth_output_dir, pp("demand-growth-b2b_VMT-by-powertrain-class.png")),p,width=5,height=4,units='in')

# ****** DEMAND GROWTH - B2B - Energy
p<-ggplot(dgb2b_summary[business!="B2C"], aes(factor(runLabel, level=dgb2b_runs_labels), GWH, fill=energyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  facet_grid(. ~ business) +
  labs(y='GWh',x='Scenario',fill='Powertrain - Class', title='Energy Consumption - B2B Demand Growth')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10))+
  scale_fill_manual(values=c("deepskyblue3", "deepskyblue4", "azure3", "darkgray", "azure4"))
ggsave(pp(demand_growth_output_dir, pp("demand-growth-b2b_GWH-by-powertrain-class.png")),p,width=5,height=4,units='in')



# ************ DEMAND GROWTH - B2C
dgb2c_runs_labels <- c("Base",  "120%", "140%", "160%", "180%")
dgb2c_runs <- 
  read_freight_events(
    c("2040_b2c_growth_base",  "2040_b2c_growth_120p", "2040_b2c_growth_140p",  "2040_b2c_growth_160p", "2040_b2c_growth_180p"), 
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
dgb2c_summary_levels <- c("BEV Class 7&8 Tractor", 
                           "Diesel Class 4-6 Vocational", "Diesel Class 7&8 Vocational", "Diesel Class 7&8 Tractor")
dgb2c_summary$energyAndVehiclesTypes <- factor(dgb2c_summary$energyAndVehiclesTypes, levels = dgb2c_summary_levels)
write.csv(
  dgb2c_summary,
  file = pp(demand_growth_output_dir, pp("demand-growth-b2c_VMT-and-GWH-by-powertrain-class.csv")),
  row.names=F,
  quote=T)

# ****** DEMAND GROWTH - B2B - VMT
p<-ggplot(dgb2c_summary[business=="B2C"], aes(factor(runLabel, level=dgb2c_runs_labels), MVMT, fill=energyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  facet_grid(. ~ business) +
  labs(y='VMT',x='Scenario',fill='Powertrain - Class', title='Total Truck Travel - 2040 B2C Demand Growth')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10))+  
  scale_fill_manual(values=c("azure3"))
ggsave(pp(demand_growth_output_dir, pp("demand-growth-b2c_VMT-by-powertrain-class.png")),p,width=6,height=4,units='in')

# ****** DEMAND GROWTH - B2B - Energy
p<-ggplot(dgb2c_summary[business=="B2C"], aes(factor(runLabel, level=dgb2c_runs_labels), GWH, fill=energyAndVehiclesTypes)) +
  geom_bar(stat='identity') +
  facet_grid(. ~ business) +
  labs(y='GWh',x='Scenario',fill='Powertrain - Class', title='Energy Consumption - 2040 B2C Demand Growth')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10))+
  scale_fill_manual(values=c("azure3"))
ggsave(pp(demand_growth_output_dir, pp("demand-growth-b2c_GWH-by-powertrain-class.png")),p,width=6,height=4,units='in')




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




# ****************************************
# ************ COST SENSITIVITY **********
# ****************************************

cost_sensitivity_runs_dir <- pp(workDir, "beam/runs/cost-sensitivity/")
cost_sensitivity_output_dir <- pp(cost_sensitivity_runs_dir, "output/")
dir.create(cost_sensitivity_output_dir, showWarnings = FALSE)


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
  labs(y='VMT',x='Scenario',fill='Powertrain - Class', title='Total Truck Travel - Grocery Stores')+
  theme_marain()+
  theme(axis.text.x = element_text(angle = 0, hjust=0.5),
        strip.text = element_text(size=rel(1.2)),
        plot.title = element_text(size=10))+
  scale_fill_manual(values=c("deepskyblue2","deepskyblue3", "deepskyblue4",
                             "mediumpurple1" , "purple1", "purple4",
                             "chartreuse2", "chartreuse3","chartreuse4",
                             "azure3","darkgray", "azure4"
  ))
ggsave(pp(locker_delivery_output_dir, pp("locker-delivery-service_VMT-by-powertrain-class.png")),p,width=5,height=4,units='in')

# ****** COST SENSITIVITY - REF & HOP - Energy
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


