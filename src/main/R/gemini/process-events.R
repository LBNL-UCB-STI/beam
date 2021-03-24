setwd(dirname(rstudioapi::getSourceEditorContext()$path))
source("/Users/haitamlaarabi/Documents/Workspace/scripts/helpers.R")
source("gemini-utils.R")

library(dplyr)
library(ggplot2)
library(ggrepel)

events.docs <- "/Users/haitamlaarabi/Data/GEMINI/2021March22/370k-warmstart"

loadInfo <- new("loadInfo", timebinInSec=900, siteXFCInKW=1000, plugXFCInKW=250)

events.file <- "/Users/haitamlaarabi/Data/GEMINI/2021March22/370k-warmstart/0.events.csv.gz"
events <- readCsv(events.file)


workdir <- "runs-2021-03-23/"
results.dir <- paste(workdir, "results", sep="")
if (!file.exists(pp(results.dir,'/ready-to-plot.Rdata'))) {
  generateReadyToPlot(results.dir)
}

