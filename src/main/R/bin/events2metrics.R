#!/usr/local/bin/Rscript
##############################################################################################################################################
# BEAM Run to Metrics
#
# Transform the events file in csv.gz format to a set of metrics for analysis
##############################################################################################################################################

##############################################################################################################################################
# LOAD LIBRARIES NEED BY THIS SCRIPT
load.libraries(c('optparse'),quietly=T)

###############################################################################################################################################
## COMMAND LINE OPTIONS 
#option_list <- list(
#)
#if(interactive()){
  ##setwd('~/downs/')
  ##args <- '~/Documents/beam/beam-output/beamville_2017-09-25_20-37-41/ITERS/it.0/0.events.csv'
  #args <- parse_args(OptionParser(option_list = option_list,usage = "events2metrics.R [file-to-convert]"),positional_arguments=T,args=args)
#}else{
  #args <- parse_args(OptionParser(option_list = option_list,usage = "events2metrics.R [file-to-convert]"),positional_arguments=T)
#}

exp.file <- '~/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/scenarios/experiment.csv'
outs.dir <- '/Users/critter/Documents/beam/beam-output/experiments'

######################################################################################################
# Load the exp config
exp <- data.table(read.csv(exp.file))

evs <- list()
out.dir <-  list.files(outs.dir)[1]
for(out.dir in list.files(outs.dir)){
  file.path <- pp(outs.dir,'/',out.dir,'/ITERS/it.0/0.events.csv.gz')
  path <- str_split(file.path,"/")[[1]]
  if(length(path)>1){
    the.file <- tail(path,1)
    the.dir <- pp(pp(head(path,-1),collapse='/'),'/')
  }else{
    the.file <- path
    the.dir <- './'
  }
  the.file.rdata <- pp(head(str_split(the.file,'csv')[[1]],-1),'Rdata')
  if(file.exists(the.file.rdata)){
    load(the.file.rdata)
  }else{
    if(!file.exists(file.path) & file.exists(pp(file.path,'.gz'))){
      ev <- data.table(read.csv(gzfile(pp(file.path,'.gz'))))
    }else if(file.exists(pp(the.dir,the.file))){
      ev <- data.table(read.csv(file.path))
    }
    save(ev,file=pp(the.dir,the.file.rdata))
  }

  exp.to.add <- exp[which(sapply(as.character(exp$name),function(str){grepl(str,file.path)}))[1]]
  if(nrow(exp.to.add)==0 & grepl("base",file.path)){
    # just pick one, must be treated as special case below
    exp.to.add <- exp[which(exp$level=='base')]
  }
  ev[,links:=NULL]
  evs[[length(evs)+1]] <- cbind(ev,exp.to.add)
}
ev <- rbindlist(evs)

###########################
# Clean and relabel
###########################
ev[vehicle_type=="bus",vehicle_type:="Bus"]
ev[vehicle_type=="CAR",vehicle_type:="TNC"]
ev[vehicle_type=="subway",vehicle_type:="BART"]
ev[vehicle_type=="SUV",vehicle_type:="Car"]
ev[,tripmode:=ifelse(mode%in%c('subway','bus','rail'),'transit',mode)]
ev[,hour:=time/3600]

###########################
# Plots 
###########################

# From / to arrows
ggplot(ev[type=='PathTraversal'],aes(x=start.x,y=start.y,xend=end.x,yend=end.y,colour=vehicle_type))+geom_curve(arrow= arrow(length = unit(0.03, "npc")),curvature=0.1)
# BART tracks
ggplot(ev[type=='PathTraversal' & vehicle_type=='Bus' & substr(vehicle_id,1,2)=='BA'][1:2000],aes(x=start.x,y=start.y,xend=end.x,yend=end.y,colour=vehicle_id))+geom_curve(arrow= arrow(length = unit(0.01, "npc")),curvature=0.1)

# Num by time and mode
ggplot(ev[type=='PathTraversal'],aes(x=time/3600))+geom_histogram()+facet_grid(level~vehicle_type)

# Transit use
ggplot(ev[tripmode=='transit',],aes(x=length,y= num_passengers))+geom_point()
ggplot(ev[tripmode=='transit',],aes(x=length,y= num_passengers/capacity))+geom_point()

###########################
# Tables
###########################
ev[,.(fuel=sum(fuel,na.rm=T)),by='vehicle_type']
