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

convert_joules_to_co2_in_kg <- function(joules, fuel_type) {
  
  btu_joules <- 0.000947817
  energy_content <- 1 #Btu/gal | (higher heating value)
  co2_coef <- 0 #Kilograms CO2
  
  if(tolower(fuel_type) == "gasoline") {
    energy_content <- (120388 + 124340)/2 # 120,388–124,340 Btu/gal | Gasoline/E10
    co2_coef <- 8.78
  } else if (tolower(fuel_type) == "diesel") {
    energy_content <- 138490 # 138,490 Btu/gal | Low Sulfur Diesel
    co2_coef <- 10.19
  } else if (tolower(fuel_type) == "biodiesel") {
    energy_content <- 127960 # 127,960 Btu/gal for B100 | Biodiesel
    co2_coef <- 10.19
  } else if (tolower(fuel_type) == "electricity") {
    energy_content <- 3414 # 3,414 Btu/kWh | Electricity
    co2_coef <- 0
  }
  
  return(((joules * btu_joules) / energy_content) * co2_coef )
}

assign_beam_vehicle_types_to_emfac <- function(emfac_category, emfac_fuel) {
  # Class 8 Vocational
  if (grepl("Delivery Class 7", emfac_category) && tolower(emfac_fuel) == "diesel") {
    return("freight-hdv-D-Diesel")  
  } else if(grepl("Delivery Class 7", emfac_category) && tolower(emfac_fuel) == "electricity") {
    return("freight-hdv-E-BE")
  } else if(grepl("Delivery Class 7", emfac_category) && tolower(emfac_fuel) == "hydrogen") {
    return("freight-hdv-E-H2FC")
  } else if(grepl("Delivery Class 7", emfac_category) && tolower(emfac_fuel) == "phev") {
    return("freight-hdv-E-PHEV")
  } 
  # Class 8 Tractor
  else if (grepl("Tractor Class 8", emfac_category) && tolower(emfac_fuel) == "diesel") {
    return("freight-hdt-D-Diesel")  
  } else if(grepl("Tractor Class 8", emfac_category) && tolower(emfac_fuel) == "electricity") {
    return("freight-hdt-E-BE")
  } else if(grepl("Tractor Class 8", emfac_category) && tolower(emfac_fuel) == "hydrogen") {
    return("freight-hdt-E-H2FC")
  } else if(grepl("Tractor Class 8", emfac_category) && tolower(emfac_fuel) == "phev") {
    return("freight-hdt-E-PHEV")
  }
  # Class 6 Vocational
  else if (grepl("Delivery Class 6", emfac_category) && tolower(emfac_fuel) == "diesel") {
    return("freight-md-D-Diesel")  
  } else if(grepl("Delivery Class 6", emfac_category) && tolower(emfac_fuel) == "electricity") {
    return("freight-md-E-BE")
  } else if(grepl("Delivery Class 6", emfac_category) && tolower(emfac_fuel) == "hydrogen") {
    return("freight-md-E-H2FC")
  } else if(grepl("Delivery Class 6", emfac_category) && tolower(emfac_fuel) == "phev") {
    return("freight-md-E-PHEV")
  } else {
    return(NA)  
  }
}


expansionFactor <- 1/0.1
city <- "sfbay"
workDir <- pp(normalizePath("~/Workspace/Data/"), "/FREIGHT/", city, "/beam/runs")
scenario <- "scenarios-23Jan2024"
batch <- "Base"
scenario_folder <- paste(workDir, scenario, sep="/")

#events <- readCsv(paste(scenario_folder, "Base/0.events.csv.gz", sep="/"))
#pt <- events[type=="PathTraversal"]
# fwrite(pt, file = paste(scenario_folder, "Base/0.events.pt.csv.gz", sep="/"), compress = "gzip")
# fuel <- pt[,
#            .(primaryFuel=expansionFactor*sum(primaryFuel),
#              primaryFuelType=first(primaryFuelType),
#              secondaryFuel=expansionFactor*sum(secondaryFuel),
#              secondaryFuelType=first(secondaryFuelType)),
#            by=.(vehicleType)]
# fwrite(fuel, file = paste(scenario_folder, "Base/0.events.fuel.csv.gz", sep="/"), compress = "gzip")
fuel <- readCsv(paste(scenario_folder, "Base/0.events.fuel.csv.gz", sep="/"))

fuel[, primaryFuelEmission := mapply(convert_joules_to_co2_in_kg, primaryFuel, primaryFuelType)]
fuel[, secondaryFuelEmission := mapply(convert_joules_to_co2_in_kg, secondaryFuel, secondaryFuelType)]

CO2E_MMT <- ((sum(fuel$primaryFuelEmission) + sum(fuel$secondaryFuelEmission))/1E9)*365
CO2E_MMT_Freight <- ((sum(fuel[startsWith(vehicleType, "freight")]$primaryFuelEmission) + 
  sum(fuel[startsWith(vehicleType, "freight")]$secondaryFuelEmission))/1E9)*365

####

# EMFAC
# HDT_emission_rate_per_mile <- readCsv(paste(scenario_folder, "HDT_emission_rate_per_mile_noheader.csv", sep="/"))
# MDT_emission_rate_per_mile <- readCsv(paste(scenario_folder, "MDT_emission_rate_per_mile_noheader.csv", sep="/"))
# truck_emission_rate_per_mile <- rbind(HDT_emission_rate_per_mile, MDT_emission_rate_per_mile)
# truck_emission_rate_per_mile[, vehicleType := mapply(assign_beam_vehicle_types_to_emfac, `Vehicle Category`, Fuel)]
# beam_vehicle_emission_rate_per_mile <- truck_emission_rate_per_mile[!is.na(vehicleType)]

# NEED to automate the following, so far I'm doing it manually
# new_rows_list <- list(c("ExtrapolatedFromBEV", 2050, "T6 Instate Delivery Class 6", NA, NA, "Hydrogen",NA,NA,NA,NA,NA,NA,0,0.003000001,0.008323513,0,0.012000003,0.023781467,0,"freight-md-E-H2FC"),
#                       c("ExtrapolatedFromBEV", 2050, "T7 Tractor Class 8", NA, NA, "Hydrogen",NA,NA,NA,NA,NA,NA,0,0.009000003,0.015153855,0,0.03600001,0.043296729,0,"freight-hdt-E-H2FC"),
#                       c("ExtrapolatedFromBEV", 2050, "T6 Instate Delivery Class 7", NA, NA, "Hydrogen",NA,NA,NA,NA,NA,NA,0,0.003000001,0.008323513,0,0.012000003,0.023781467,0,"freight-hdv-E-H2FC"),
#                       c("ExtrapolatedFromBEV", 2050, "T6 Instate Delivery Class 6", NA, NA, "PHEV",NA,NA,NA,NA,NA,NA,0,0.003000001,0.008323513,0,0.012000003,0.023781467,0,"freight-md-E-PHEV"),
#                       c("ExtrapolatedFromBEV", 2050, "T7 Tractor Class 8", NA, NA, "PHEV",NA,NA,NA,NA,NA,NA,0,0.009000003,0.015153855,0,0.03600001,0.043296729,0,"freight-hdt-E-PHEV"),
#                       c("ExtrapolatedFromBEV", 2050, "T6 Instate Delivery Class 7", NA, NA, "PHEV",NA,NA,NA,NA,NA,NA,0,0.003000001,0.008323513,0,0.012000003,0.023781467,0,"freight-hdv-E-PHEV"))
# new_rows_dt <- rbindlist(lapply(new_rows_list, function(row) as.data.table(t(row))), fill = TRUE)
# dt <- rbind(new_rows_dt, beam_vehicle_emission_rate_per_mile, fill = TRUE)

#fwrite(beam_vehicle_emission_rate_per_mile, file = paste(scenario_folder, "beam_vehicle_emission_rate_per_mile.csv", sep="/"))

beam_vehicle_emission_rate_per_mile <- readCsv(paste(scenario_folder, "beam_vehicle_emission_rate_per_mile.csv", sep="/"))


#vmt <- pt[,.(MVMT=expansionFactor*sum(length/1609.344)/1e+6),by=.(vehicleType)]
#fwrite(vmt, file = paste(scenario_folder, "Base/0.events.vmt.csv.gz", sep="/"), compress = "gzip")
vmt <- readCsv(paste(scenario_folder, "Base/0.events.vmt.csv.gz", sep="/"))
vmt_freight <- vmt[startsWith(vehicleType,"freight")]

beam_vehicle_emission_rate_per_mile_2018 <- beam_vehicle_emission_rate_per_mile[
  !is.na(vehicleType)][`Calendar Year` == "2018" | (
    `Calendar Year` == "2050" & vehicleType %in% c("freight-hdv-E-BE", "freight-hdt-E-BE", "freight-md-E-BE"))
  ]

vmt_emfac <- vmt_freight[beam_vehicle_emission_rate_per_mile_2018, on="vehicleType"][!is.na(MVMT)]
vmt_emfac[, beam_pm2.5_emissions_t := (PM2.5_running_exhaust + PM2.5_brakewear + PM2.5_tirewear)*MVMT]
vmt_emfac[, beam_pm10_emissions_t := (PM10_running_exhaust + PM10_brakewear + PM10_tirewear)*MVMT]
vmt_emfac[, beam_nox_t := NOx_running_exhaust*MVMT]

print(pp("beam_pm2.5_emissions_t_yr: ", sum(vmt_emfac$beam_pm2.5_emissions_t)*365))
print(pp("beam_pm10_emissions_t_yr: ", sum(vmt_emfac$beam_pm10_emissions_t)*365))
print(pp("beam_nox_kt_yr: ", sum(vmt_emfac$beam_nox_t)*365/1000))
print(pp("beam_vmt_b_yr: ", sum(vmt_emfac$MVMT)*365/1000))




# events2050 <- readCsv(paste(scenario_folder, "Ref_highp6/0.events.csv.gz", sep="/"))
# pt2050 <- events2050[type=="PathTraversal"]
# fwrite(pt2050, file = paste(scenario_folder, "Ref_highp6/0.events.pt.csv.gz", sep="/"), compress = "gzip")
# vmt2050 <- pt2050[,.(MVMT=expansionFactor*sum(length/1609.344)/1e+6),by=.(vehicleType)]
# fwrite(vmt2050, file = paste(scenario_folder, "Ref_highp6/0.events.vmt.csv.gz", sep="/"), compress = "gzip")
vmt2050 <- readCsv(paste(scenario_folder, "Ref_highp6/0.events.vmt.csv.gz", sep="/"))
vmt2050_freight <- vmt2050[startsWith(vehicleType,"freight")]

beam_vehicle_emission_rate_per_mile_2050 <- beam_vehicle_emission_rate_per_mile[
  !is.na(vehicleType)][`Calendar Year` == "2050"]

vmt2050_emfac <- vmt2050_freight[beam_vehicle_emission_rate_per_mile_2050, on="vehicleType"][!is.na(MVMT)]
vmt2050_emfac[, beam_pm2.5_emissions_t := (PM2.5_running_exhaust + PM2.5_brakewear + PM2.5_tirewear)*MVMT]
vmt2050_emfac[, beam_pm10_emissions_t := (PM10_running_exhaust + PM10_brakewear + PM10_tirewear)*MVMT]
vmt2050_emfac[, beam_nox_t := NOx_running_exhaust*MVMT]

print(pp("beam_pm2.5_emissions_t_yr: ", sum(vmt2050_emfac$beam_pm2.5_emissions_t)*365))
print(pp("beam_pm10_emissions_t_yr: ", sum(vmt2050_emfac$beam_pm10_emissions_t)*365))
print(pp("beam_nox_kt_yr: ", sum(vmt2050_emfac$beam_nox_t)*365/1000))
print(pp("beam_vmt_b_yr: ", sum(vmt2050_emfac$MVMT)*365/1000))

refp6_pm2.5_emissions_t <- sum(vmt2050_emfac$beam_pm2.5_emissions_t)
refp6_pm2.5_emissions_t
sum(vmt2050_emfac$MVMT)


sum(vmt2050_emfac[!grepl("Diesel", vehicleType)]$MVMT)
sum(vmt_emfac[grepl("Diesel", vehicleType)]$beam_pm2.5_emissions_t)
sum(vmt2050_emfac[grepl("Diesel", vehicleType)]$beam_pm2.5_emissions_t)


##
## ********** ##
## ********** ##
## ********** ##
##
famos_emfac_freight_population_mapping_file = normalizePath("~/Workspace/Data/FREIGHT/sfbay/beam_freight/2024-01-23/Baseline/famos-emfac-freight-population-mapping.csv")
emfac_mapping <- readCsv(famos_emfac_freight_population_mapping_file)

tot_vehicles <- nrow(emfac_mapping)
emfac_mapping[,.(count=.N),by=.(vehicleClass,fuelType)] %>% 
  ggplot(aes(vehicleClass, count/tot_vehicles, fill=fuelType)) +
  geom_bar(stat='identity') + 
  scale_fill_manual(values=c("#999999", "#56B4E9")) +
  scale_y_continuous(labels = scales::percent) +
  theme_marain() +
  labs(y='Count',x='Vehicle Class',fill='Powertrain', title='FAMOS Vehicle Types Distribution')

emfac_fuel_type <- emfac_mapping[, .(percentage = .N/tot_vehicles), by = .(vehicleClass, emfacFuelType)][order(vehicleClass,emfacFuelType)]
ggplot(emfac_fuel_type, aes(vehicleClass, percentage, fill=emfacFuelType)) +
  geom_bar(stat='identity') + 
  scale_fill_manual(values=c("#999999", "#56B4E9", "#66A61E", "goldenrod3")) +
  scale_y_continuous(labels = scales::percent) +
  theme_marain() +
  labs(y='Count',x='Vehicle Class',fill='Powertrain', title='FAMOS Mapped Population')

# ***************
# ***************
# ***************

# Calculate total vehicles and counts
emfac_summary <- emfac_mapping[, .(count = .N, vehicleClass = first(vehicleClass)), by = .(emfacClass, emfacFuelType)] %>%
  group_by(vehicleClass) %>%
  mutate(tot_vehicles = sum(count)) %>%
  ungroup() %>%
  mutate(percentage = count / tot_vehicles)
# Plotting
ggplot(emfac_summary, aes(x = emfacClass, y = percentage, fill = emfacFuelType)) +
  geom_bar(stat = 'identity') +
  scale_y_continuous(labels = percent_format()) +
  scale_fill_manual(values=c("#999999", "#56B4E9", "#66A61E", "goldenrod3")) +
  labs(y = 'Percentage', x = 'EMFAC Class', fill = 'Fuel Type', title = 'FAMOS-EMFAC Vehicle Types Distribution') +
  theme_minimal() +  # Assuming theme_marain was a typo or custom theme not provided
  theme(axis.text.x = element_text(angle = 90, hjust = 1),
        strip.text.x = element_text(size = rel(1.2))) +
  facet_wrap(~ vehicleClass, scales = "free_x")

# ***************
# ***************
# ***************

emfac_freight_population_file = normalizePath("~/Workspace/Data/FREIGHT/sfbay/beam_freight/2024-01-23/Baseline/CA-emfac-freight-population.csv")
#emfac_population <- readCsv(emfac_freight_population_file)[mappedVehicleClass!="Class 2b&3 Vocational"]
emfac_population <- readCsv(emfac_freight_population_file)
tot_population <- sum(emfac_population$population)
emfac_fuel_type_raw <- emfac_population[, .(percentage = sum(population)/tot_population), by = .(mappedVehicleClass, fuel)][order(mappedVehicleClass,fuel)]
ggplot(emfac_fuel_type_raw, aes(mappedVehicleClass, percentage, fill=fuel)) +
  geom_bar(stat='identity') + 
  scale_fill_manual(values=c("#999999", "#66A61E", "goldenrod3")) +
  scale_y_continuous(labels = scales::percent) +
  theme_marain() +
  labs(y='Count',x='Vehicle Class',fill='Powertrain', title='EMFAC Population')


emfac_class_to_regulatory_class_map <- c(
  'LHD1' = 'Class 2b',
  'LHD2' = 'Class 3',
  'T6 Instate Delivery Class 4' = 'Class 4',
  'T6 Instate Delivery Class 5' = 'Class 5',
  'T6 Instate Delivery Class 6' = 'Class 6',
  'T6 Instate Other Class 4' = 'Class 4',
  'T6 Instate Other Class 5' = 'Class 5',
  'T6 Instate Other Class 6' = 'Class 6',
  'T6 CAIRP Class 4' = 'Class 4',
  'T6 CAIRP Class 5' = 'Class 5',
  'T6 CAIRP Class 6' = 'Class 6',
  'T6 OOS Class 4' = 'Class 4',
  'T6 OOS Class 5' = 'Class 5',
  'T6 OOS Class 6' = 'Class 6',
  'T6 Instate Tractor Class 6' = 'Class 6',
  'T6 Instate Delivery Class 7' = 'Class 7',
  'T6 Instate Other Class 7' = 'Class 7',
  'T7 Single Concrete/Transit Mix Class 8' = 'Class 8',
  'T7 Single Dump Class 8' = 'Class 8',
  'T7 Single Other Class 8' = 'Class 8',
  'T7IS' = 'Class 7',
  'T6 Instate Tractor Class 7' = 'Class 7',
  'T7 Tractor Class 8' = 'Class 8',
  'T6 CAIRP Class 7' = 'Class 7',
  'T6 OOS Class 7' = 'Class 7',
  'T7 CAIRP Class 8' = 'Class 8',
  'T7 NNOOS Class 8' = 'Class 8',
  'T7 NOOS Class 8' = 'Class 8'
)

emfac_population$regulatoryClass <- emfac_class_to_regulatory_class_map[emfac_population$emfacClass]
emfac_population_summary <- emfac_population[,.(tot_population=sum(population)),by=.(regulatoryClass)][order(regulatoryClass)]
fwrite(emfac_population_summary, file = normalizePath("~/Workspace/Data/FREIGHT/sfbay/beam_freight/2024-01-23/Baseline/CA-emfac-freight-population-bis.csv"))

