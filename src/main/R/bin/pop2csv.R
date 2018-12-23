#!/usr/local/bin/Rscript
##############################################################################################################################################
# Script to convert XML files to csv format assuming they are "flat"
##############################################################################################################################################

##############################################################################################################################################
# LOAD LIBRARIES NEED BY THIS SCRIPT
library(colinmisc)
load.libraries(c('optparse','XML'),quietly=T)

##############################################################################################################################################
# COMMAND LINE OPTIONS 
option_list <- list(
)
if(interactive()){
  #setwd('~/downs/')
  args<-'/Users/critter/Dropbox/ucb/vto/beam-all/beam/production/application-sfbay/population.xml'
  args<-'/Users/critter/Dropbox/ucb/vto/beam-all/beam/production/sfbay-1k/population.xml'
  args <- parse_args(OptionParser(option_list = option_list,usage = "xml2csv.R [file-to-convert]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "xml2csv.R [file-to-convert]"),positional_arguments=T)
}

get.activities <- function(plan){
           rbindlist(lapply(xmlChildren(xmlChildren(plan)[[1]]),function(plan.elem){ 
                  name <- xmlName(plan.elem)
                  if(name=='leg'){
                    data.table()
                  }else{
                    data.table(name='activity',
                               type=xmlGetAttr(plan.elem,'type'),
                               x=as.numeric(xmlGetAttr(plan.elem,'x')),
                               y=as.numeric(xmlGetAttr(plan.elem,'y')),
                               end.time=ifelse(is.null(xmlGetAttr(plan.elem,'end_time')),NA,
                                               as.numeric(to.posix(xmlGetAttr(plan.elem,'end_time'),"%H:%M:%S")-to.posix("00:00:00","%H:%M:%S")))
                               )
                  }
            }))
         }

######################################################################################################
file.path <- args$args[1]
#for(file.path in args$args){
  path <- str_split(file.path,"/")[[1]]
  if(length(path)>1){
    the.file <- tail(path,1)
    the.dir <- pp(pp(head(path,-1),collapse='/'),'/')
  }else{
    the.file <- path
    the.dir <- './'
  }
  the.file.rdata <- pp(head(str_split(the.file,'xml')[[1]],-1),'Rdata')

  dat <- xmlParse(file.path)

  #ids <- sapply(xpathApply(dat,'//population/person'),xmlGetAttr,'id')

  plans <- rbindlist(lapply(xpathApply(dat,'//population/person'),function(x){ 
         id <- xmlGetAttr(x,'id')
         cbind(data.table(id=id),get.activities(x))
  }))
  #write.csv(df,file=pp(the.dir,the.file.csv),row.names=F)
  save(plans,file=pp(the.dir,the.file.rdata))
}
