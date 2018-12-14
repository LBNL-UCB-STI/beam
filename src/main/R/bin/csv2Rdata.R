#!/usr/local/bin/Rscript
##############################################################################################################################################
# Script to convert CSV files to an R data table and then save as Rdata. This is intended to be run from the project root directory.
##############################################################################################################################################
library(colinmisc)
setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')

##############################################################################################################################################
# LOAD LIBRARIES NEED BY THIS SCRIPT
load.libraries(c('optparse','stringr'),quietly=T)

##############################################################################################################################################
# COMMAND LINE OPTIONS 
option_list <- list(
)
if(interactive()){
  #setwd('~/downs/')
  args<-'/Users/critter/Downloads/output 2/application-sfbay/RH2Transit_Plus10__2018-09-26_04-55-53/ITERS/it.10/10.events.csv'
  args <- parse_args(OptionParser(option_list = option_list,usage = "csv2Rdata.R [file-to-convert]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "csv2Rdata.R [file-to-convert]"),positional_arguments=T)
}

######################################################################################################
file.path <- args$args[1]
for(file.path in args$args){
  path <- str_split(file.path,"/")[[1]]
  if(length(path)>1){
    the.file <- tail(path,1)
    the.dir <- pp(pp(head(path,-1),collapse='/'),'/')
  }else{
    the.file <- path
    the.dir <- './'
  }
  df <- csv2rdata(pp(the.dir,the.file))
}
