#---
#title: "Scaling up Vehicles"
#author: "Julia Szinai"
#date: "February 18, 2017"
#output: html_document
#---

knitr::opts_chunk$set(echo = TRUE)

#Calculating the proportions to scale up the energy usage and constraints from detailed Bay Area data to the rest of California's utility planning areas, for input into PLEXOS

#Data from: Center for Sustainable Energy (2016). California Air Resources Board Clean Vehicle Rebate Project, Rebate Statistics. Data last updated 2/1/2017. Retrieved 2/18/2017 from https://cleanvehiclerebate.org/rebate-statistic

library(tidyverse)
#reading in the rebate data

setwd("D://PLEXOS//PLEXOS files//2014 LTPP//Input Data Processing")

#Read in vehicle sales data
Car_sales <- read_csv(file = "CVRPStats.csv")

#filtering for just BEVs and PHEVs 
Car_sales <- Car_sales %>% filter(`Vehicle Category`=="BEV" | `Vehicle Category`=="PHEV" )

#filtering for just the key utility planning areas in CA: PGE& SDG&E SCE SMUD LADWP IID TIDC
Car_sales_utility_areas <- Car_sales %>% filter(`Electric Utility`=="Pacific Gas & Electric Company" | `Electric Utility`=="Southern California Edison" |`Electric Utility`=="San Diego Gas & Electric" |`Electric Utility`=="Sacramento Municipal Utility District" |`Electric Utility`=="Los Angeles Department of Water & Power" |`Electric Utility`=="Turlock Irrigation District" |`Electric Utility`=="Imperial Irrigation District" )

#pulling out Bay area counties (which are the ones simulated in BEAM)
Bay_sales <- Car_sales_utility_areas %>% filter(County=="Alameda" | County=="Contra Costa" | County=="Marin" | County=="Napa" | County== "San Francisco" | County=="San Mateo" | County=="Santa Clara" | County=="Solano" | County=="Sonoma")

#counting the Bay area vehicle totals by vehicle type to create ratios
Bay_sales_totals <- Bay_sales %>% group_by(`Vehicle Category`)  %>% tally()

#count up the number of vehicles purchased in each utility area by vehicle type.
Car_sales_totals_utility_areas<- Car_sales_utility_areas  %>% group_by(`Vehicle Category`,`Electric Utility`) %>% tally()

#cleaning up the variable names for merging
Car_sales_totals_utility_areas$Vehicle_category <- Car_sales_totals_utility_areas$`Vehicle Category`
Car_sales_totals_utility_areas$`Vehicle Category` <- NULL
Car_sales_totals_utility_areas$Utility_Area_Sales <- Car_sales_totals_utility_areas$n
Car_sales_totals_utility_areas$n <- NULL

Bay_sales_totals$Vehicle_category <- Bay_sales_totals$`Vehicle Category`
Bay_sales_totals$`Vehicle Category` <- NULL
Bay_sales_totals$Bay_Area_Sales <- Bay_sales_totals$n
Bay_sales_totals$n <- NULL

#joining the Bay Area vehicle sales to the sales by utility area
Car_sales_totals_utility_areas_with_Bay <- left_join(Car_sales_totals_utility_areas, Bay_sales_totals, by = c("Vehicle_category"="Vehicle_category"))

#Ratios to scale up the kWh from BEAM -> kwh from BEAM for Bay Area * vehicles in utility area/vehicles in Bay Area
Car_sales_totals_utility_areas_with_Bay$Utility_Area_Proportion <- Car_sales_totals_utility_areas_with_Bay$Utility_Area_Sales/ Car_sales_totals_utility_areas_with_Bay$Bay_Area_Sales


#Scaling up the 2017 energy usage to 2024 based on the forecasted statewide 2024 vehicle counts, and the current ratio of vehicles in each utility area in CA

#The source for the 2024 forecasts for low, mid and high levels of EV adoption are here:
#California Energy Demand 2014-2024 Final Forecast Volume 1: Statewide Electricity Demand, End-User Natural Gas Demand, and Energy Efficiency. (California Energy Commission, 2014).
#http://www.energy.ca.gov/2013publications/CEC-200-2013-004/CEC-200-2013-004-V1-CMF.pdf


#summing up the 2017 vehicle sales for all of CA (in the relevant utility areas) to scale up with the total in 2024

CA_total_sales_2017 <- Car_sales_totals_utility_areas_with_Bay %>% group_by(Vehicle_category) %>%  summarise(Total_sales_2017 = sum(Utility_Area_Sales))


#joining the CA statewide vehicle sales to the sales by utility area
CA_car_sales_totals <- left_join(Car_sales_totals_utility_areas_with_Bay, CA_total_sales_2017, by = c("Vehicle_category"="Vehicle_category"))

#low forecast for the state: BEVs: 335,536 and PHEVs: 688,593
#mid forecast for the state: BEVs: 340,013 and PHEVs: 2,009,710
#high forecast for the state: BEVs: 344,489 and PHEVs: 3,330,826

#LOW Case
CA_car_sales_totals$Total_sales_2024_lowCED13[CA_car_sales_totals$Vehicle_category == "BEV"] <- 335536
CA_car_sales_totals$Total_sales_2024_lowCED13[CA_car_sales_totals$Vehicle_category == "PHEV"] <- 688593

#MID Case
CA_car_sales_totals$Total_sales_2024_midCED13[CA_car_sales_totals$Vehicle_category == "BEV"] <- 340013 
CA_car_sales_totals$Total_sales_2024_midCED13[CA_car_sales_totals$Vehicle_category == "PHEV"] <- 2009710

#High Case
CA_car_sales_totals$Total_sales_2024_highCED13[CA_car_sales_totals$Vehicle_category == "BEV"] <- 344489
CA_car_sales_totals$Total_sales_2024_highCED13[CA_car_sales_totals$Vehicle_category == "PHEV"] <- 3330826

#Ratios to scale up the 2016 vehicles to 2024 forecasts-> total vehicles forecasted in 2024/total vehicles by 2017

CA_car_sales_totals$Low_2024_Forecast_PropCED13 <- CA_car_sales_totals$Total_sales_2024_lowCED13/CA_car_sales_totals$Total_sales_2017

CA_car_sales_totals$Mid_2024_Forecast_PropCED13 <- CA_car_sales_totals$Total_sales_2024_midCED13/CA_car_sales_totals$Total_sales_2017

CA_car_sales_totals$High_2024_Forecast_PropCED13 <- CA_car_sales_totals$Total_sales_2024_highCED13/CA_car_sales_totals$Total_sales_2017

#Ratios to scale up the 2016 kWh from BEAM -> 2016 kwh from BEAM for Bay Area * vehicles in utility area/vehicles in Bay Area * total vehicles forecasted in 2024/total vehicles by 2017

CA_car_sales_totals$Low_2024_energy_propCED13 <- CA_car_sales_totals$Low_2024_Forecast_PropCED13 * CA_car_sales_totals$Utility_Area_Proportion

CA_car_sales_totals$Mid_2024_energy_propCED13 <- CA_car_sales_totals$Mid_2024_Forecast_PropCED13 * CA_car_sales_totals$Utility_Area_Proportion

CA_car_sales_totals$High_2024_energy_propCED13 <- CA_car_sales_totals$High_2024_Forecast_PropCED13 * CA_car_sales_totals$Utility_Area_Proportion


#Adding other forecasts for 2024, but that just have total numbers for BEV + PHEV

#California Energy Demand 2016-2026, Revised Electricity Forecast, Volume 1: Statewide Electricity Demand and Energy Efficiency. (2016).

#Total BEV + PHEV Low: 800
#Total BEV + PHEV Mid: 1,750
#Total BEV + PHEV High: 2,100

#Will scenarioize the split between BEV and PHEV: 20% BEV, 80% PHEV; 50% : 50%, and 80% BEV, 20% PHEV

#LOW Case
CA_car_sales_totals$Total_sales_2024_lowCED15_2080[CA_car_sales_totals$Vehicle_category == "BEV"] <- 800000 * 0.20
CA_car_sales_totals$Total_sales_2024_lowCED15_2080[CA_car_sales_totals$Vehicle_category == "PHEV"] <- 800000 * 0.80

CA_car_sales_totals$Total_sales_2024_lowCED15_5050[CA_car_sales_totals$Vehicle_category == "BEV"] <- 800000 * 0.50
CA_car_sales_totals$Total_sales_2024_lowCED15_5050[CA_car_sales_totals$Vehicle_category == "PHEV"] <- 800000 * 0.50

CA_car_sales_totals$Total_sales_2024_lowCED15_8020[CA_car_sales_totals$Vehicle_category == "BEV"] <- 800000 * 0.80
CA_car_sales_totals$Total_sales_2024_lowCED15_8020[CA_car_sales_totals$Vehicle_category == "PHEV"] <- 800000 * 0.20

#MID Case
CA_car_sales_totals$Total_sales_2024_midCED15_2080[CA_car_sales_totals$Vehicle_category == "BEV"] <- 1750000 * 0.20
CA_car_sales_totals$Total_sales_2024_midCED15_2080[CA_car_sales_totals$Vehicle_category == "PHEV"] <- 1750000 * 0.80

CA_car_sales_totals$Total_sales_2024_midCED15_5050[CA_car_sales_totals$Vehicle_category == "BEV"] <- 1750000 * 0.50
CA_car_sales_totals$Total_sales_2024_midCED15_5050[CA_car_sales_totals$Vehicle_category == "PHEV"] <- 1750000 * 0.50

CA_car_sales_totals$Total_sales_2024_midCED15_8020[CA_car_sales_totals$Vehicle_category == "BEV"] <- 1750000 * 0.80
CA_car_sales_totals$Total_sales_2024_midCED15_8020[CA_car_sales_totals$Vehicle_category == "PHEV"] <- 1750000 * 0.20

#High Case
CA_car_sales_totals$Total_sales_2024_highCED15_2080[CA_car_sales_totals$Vehicle_category == "BEV"] <- 2100000 * 0.20
CA_car_sales_totals$Total_sales_2024_highCED15_2080[CA_car_sales_totals$Vehicle_category == "PHEV"] <-  2100000 * 0.80

CA_car_sales_totals$Total_sales_2024_highCED15_5050[CA_car_sales_totals$Vehicle_category == "BEV"] <-  2100000 * 0.50
CA_car_sales_totals$Total_sales_2024_highCED15_5050[CA_car_sales_totals$Vehicle_category == "PHEV"] <-  2100000 * 0.50

CA_car_sales_totals$Total_sales_2024_highCED15_8020[CA_car_sales_totals$Vehicle_category == "BEV"] <-  2100000 * 0.80
CA_car_sales_totals$Total_sales_2024_highCED15_8020[CA_car_sales_totals$Vehicle_category == "PHEV"] <-  2100000 * 0.20

#Ratios to scale up the 2016 vehicles to 2024 forecasts-> total vehicles forecasted in 2024/total vehicles by 2017
#low
CA_car_sales_totals$Low_2024_Forecast_PropCED15_2080 <- CA_car_sales_totals$Total_sales_2024_lowCED15_2080/CA_car_sales_totals$Total_sales_2017

CA_car_sales_totals$Low_2024_Forecast_PropCED15_5050 <- CA_car_sales_totals$Total_sales_2024_lowCED15_5050/CA_car_sales_totals$Total_sales_2017

CA_car_sales_totals$Low_2024_Forecast_PropCED15_8020 <- CA_car_sales_totals$Total_sales_2024_lowCED15_8020/CA_car_sales_totals$Total_sales_2017
#mid
CA_car_sales_totals$Mid_2024_Forecast_PropCED15_2080 <- CA_car_sales_totals$Total_sales_2024_midCED15_2080/CA_car_sales_totals$Total_sales_2017

CA_car_sales_totals$Mid_2024_Forecast_PropCED15_5050 <- CA_car_sales_totals$Total_sales_2024_midCED15_5050/CA_car_sales_totals$Total_sales_2017

CA_car_sales_totals$Mid_2024_Forecast_PropCED15_8020 <- CA_car_sales_totals$Total_sales_2024_midCED15_8020/CA_car_sales_totals$Total_sales_2017

#high
CA_car_sales_totals$High_2024_Forecast_PropCED15_2080 <- CA_car_sales_totals$Total_sales_2024_highCED15_2080/CA_car_sales_totals$Total_sales_2017

CA_car_sales_totals$High_2024_Forecast_PropCED15_5050 <- CA_car_sales_totals$Total_sales_2024_highCED15_5050/CA_car_sales_totals$Total_sales_2017

CA_car_sales_totals$High_2024_Forecast_PropCED15_8020 <- CA_car_sales_totals$Total_sales_2024_highCED15_8020/CA_car_sales_totals$Total_sales_2017

#Ratios to scale up the 2016 kWh from BEAM -> 2016 kwh from BEAM for Bay Area * vehicles in utility area/vehicles in Bay Area * total vehicles forecasted in 2024/total vehicles by 2017
#low
CA_car_sales_totals$Low_2024_energy_propCED15_2080 <- CA_car_sales_totals$Low_2024_Forecast_PropCED15_2080 * CA_car_sales_totals$Utility_Area_Proportion

CA_car_sales_totals$Low_2024_energy_propCED15_5050 <- CA_car_sales_totals$Low_2024_Forecast_PropCED15_5050 * CA_car_sales_totals$Utility_Area_Proportion

CA_car_sales_totals$Low_2024_energy_propCED15_8020 <- CA_car_sales_totals$Low_2024_Forecast_PropCED15_8020 * CA_car_sales_totals$Utility_Area_Proportion
#mid
CA_car_sales_totals$Mid_2024_energy_propCED15_2080 <- CA_car_sales_totals$Mid_2024_Forecast_PropCED15_2080 * CA_car_sales_totals$Utility_Area_Proportion

CA_car_sales_totals$Mid_2024_energy_propCED15_5050 <- CA_car_sales_totals$Mid_2024_Forecast_PropCED15_5050 * CA_car_sales_totals$Utility_Area_Proportion

CA_car_sales_totals$Mid_2024_energy_propCED15_8020 <- CA_car_sales_totals$Mid_2024_Forecast_PropCED15_8020 * CA_car_sales_totals$Utility_Area_Proportion
#high
CA_car_sales_totals$High_2024_energy_propCED15_2080 <- CA_car_sales_totals$High_2024_Forecast_PropCED15_2080 * CA_car_sales_totals$Utility_Area_Proportion

CA_car_sales_totals$High_2024_energy_propCED15_5050 <- CA_car_sales_totals$High_2024_Forecast_PropCED15_5050 * CA_car_sales_totals$Utility_Area_Proportion

CA_car_sales_totals$High_2024_energy_propCED15_8020 <- CA_car_sales_totals$High_2024_Forecast_PropCED15_8020 * CA_car_sales_totals$Utility_Area_Proportion

#saving and exporting file with scaling factors
setwd("D://PLEXOS//PLEXOS files//2014 LTPP//LoadProfile")
write.csv(file="Scaling_factors_for_forecast_and_EV_counts.csv", x=CA_car_sales_totals)
