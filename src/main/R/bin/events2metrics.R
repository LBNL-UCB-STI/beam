#!/usr/local/bin/Rscript
##############################################################################################################################################
# BEAM Run to Metrics
#
# Transform the events file in csv.gz format to a set of metrics for analysis
##############################################################################################################################################

##############################################################################################################################################
# LOAD LIBRARIES NEED BY THIS SCRIPT
load.libraries(c('optparse'),quietly=T)

##############################################################################################################################################
# COMMAND LINE OPTIONS 
option_list <- list(
)
if(interactive()){
  #setwd('~/downs/')
  args <- '~/Documents/beam/beam-output/beamville_2017-09-25_00-44-07/ITERS/it.0/0.events.csv'
  args <- parse_args(OptionParser(option_list = option_list,usage = "events2metrics.R [file-to-convert]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "events2metrics.R [file-to-convert]"),positional_arguments=T)
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
  the.file.rdata <- pp(head(str_split(the.file,'csv')[[1]],-1),'Rdata')

  ev <- data.table(read.csv(file.path))
  save(ev,file=pp(the.dir,the.file.rdata))
}


###########################
# Plots 
###########################

ggplot(ev[type=='PathTraversal'],aes(x=start.x,y=start.y,xend=end.x,yend=end.y))+geom_segment(arrow= arrow(length = unit(0.03, "npc")))
