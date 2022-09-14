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

plans <- readCsv(pp(activitySimDir, "/2018/plans.csv.gz"))
#trips <- readCsv(pp(activitySimDir, "/2018/trips.csv.gz"))
persons <- readCsv(pp(activitySimDir, "/2018/persons.csv.gz"))
households <- readCsv(pp(activitySimDir, "/2018/households.csv.gz"))
blocks <- readCsv(pp(activitySimDir, "/2018/blocks.csv.gz"))

geminiDir <- normalizePath("~/Data/GEMINI")

#vehicles-8MaxEV-generated.csv
vehicles <- readCsv(pp(geminiDir, "/2022-07-05/_models/vehicles/", "vehicles-7Advanced-generated.csv"))