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

###########
city <- "austin"
vehileTypesFreightTechFile <- "/2030_central/vehicle_types_scentral_y2030.csv"

##########
workDir <- normalizePath(pp("~/Workspace/Data/FREIGHT/",city))
scenariosDir <- pp(workDir, "/frism/vehtech-scenarios")

###########
vehicleTypesBaseFile <- pp(workDir,"/beam/vehicletypes-gpra-2030.csv")
vehicleTypesBase <- readCsv(vehicleTypesBaseFile)

vehileTypesFreightTech <- readCsv(pp(scenariosDir, vehileTypesFreightTechFile))
replace('Battery Electric', 'BE').replace('H2 Fuel Cell', 'H2FC')
df$address <- str_replace(df$address, "St", "Street")
vehileTypesTech$vehicleTypeId <- str_replace(vehileTypesTech$veh_type_id, 'Battery Electric', "BE")
vehileTypesTech$vehicleTypeId <- str_replace(vehileTypesTech$vehicleTypeId, 'H2 Fuel Cell', "H2FC")


vehicleTypesBaseFreightTech <- vehicleTypesBase
vehicleTypesBaseFreightTech$vehicleTypeId <- str_replace(vehileTypesTech$veh_type_id, 'Battery Electric', "BE")
vehicleTypesBaseFreightTech$vehicleTypeId <- str_replace(vehicleTypesBaseFreightTech$vehicleTypeId, 'Battery Electric', "BE")

