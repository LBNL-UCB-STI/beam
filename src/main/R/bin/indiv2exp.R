#!/usr/local/bin/Rscript
##############################################################################################################################################
# Script to combined BEAM runs that were executed separately into the same form as if they were a part of an experiment. This allows the 
# runs to be processed by other scripts (e.g. exp2plots.R) that are designed to do comparative analysis.
# 
# Argument: name of experiment to be created plus space separated list of paths to the top level directory of run outputs that should be combined.
# 
# Result: a new directory with experient name will be created in the same directory as the first run directory processed. Data will be copied
# and not moved.
##############################################################################################################################################
library(colinmisc)
setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')
load.libraries(c('optparse'),quietly=T)
load.libraries(c('maptools','sp','stringr','ggplot2'))

##############################################################################################################################################
# COMMAND LINE OPTIONS 
option_list <- list(
)
if(interactive()){
  #setwd('~/downs/')
  #args<-c('pruning-1','/Users/critter/Documents/beam/beam-output/experiments/base_2018-01-19_21-23-36 2/','/Users/critter/Documents/beam/beam-output/experiments/pruned-transit_2018-01-19_06-13-51/')
  args<-c('rh2transit','/Users/critter/Downloads/output 2/application-sfbay/RH2Transit_Plus10__2018-09-26_04-55-53/')
  args <- parse_args(OptionParser(option_list = option_list,usage = "indiv2exp.R [exp-name] [run-directories]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "indiv2exp.R [exp-name] [run-directories]"),positional_arguments=T)
}
######################################################################################################

exp.name <- args$args[1]
run.dirs <- dir.slash(tail(args$args,-1))

######################################################################################################
# Load the exp config
run.dir <- run.dirs[1]
run.dir.parent <- pp(pp(head(str_split(run.dir,"/")[[1]],-2),collapse="/"),"/")
exp.dir <- pp(run.dir.parent,exp.name,"/")
make.dir(exp.dir)
make.dir(pp(exp.dir,'runs'))
all.runs <- list()
for(run.dir in run.dirs){
  run.name <- head(tail(str_split(run.dir,"/")[[1]],2),1)
  new.run.dir <- pp(exp.dir,'runs/run.runName_',run.name)
  make.dir(new.run.dir)
  file.copy(run.dir, new.run.dir, recursive=TRUE)
  file.rename(pp(new.run.dir,'/',run.name),pp(new.run.dir,'/output'))
  all.runs[[length(all.runs)+1]] <- data.table(experimentalGroup=run.name,runName=run.name)
}
all.runs <- rbindlist(all.runs)
all.runs[,experimentalGroup:=pp('runName_',experimentalGroup)]
write.csv(all.runs,file=pp(exp.dir,'runs/experiments.csv'),row.names=F)

