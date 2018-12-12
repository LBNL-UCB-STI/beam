#!/usr/local/bin/Rscript
##############################################################################################################################################
# Script to convert XML plan file to an R data table and then save as Rdata 
##############################################################################################################################################

##############################################################################################################################################
# LOAD LIBRARIES NEED BY THIS SCRIPT
library(colinmisc)
load.libraries(c('optparse','XML','stringr','rgdal','sp'),quietly=T)

##############################################################################################################################################
# COMMAND LINE OPTIONS 
option_list <- list(
)
if(interactive()){
  #setwd('~/downs/')
  args<-'/Users/critter/Documents/beam/beam-output/beamville__2018-12-11_15-08-55/ITERS/it.0/0.experiencedPlans.xml.gz'
  args<-'/Users/critter/Documents/beam/beam-output/beamville__2018-12-11_15-08-55/outputPlans.xml.gz'
  args <- parse_args(OptionParser(option_list = option_list,usage = "matsimNetwork2csv.R [file-to-convert]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "matsimNetwork2csv.R [file-to-convert]"),positional_arguments=T)
}

######################################################################################################
file.path <- args$args[1]

path <- str_split(file.path,"/")[[1]]
if(length(path)>1){
  the.file <- tail(path,1)
  the.dir <- pp(pp(head(path,-1),collapse='/'),'/')
}else{
  the.file <- path
  the.dir <- './'
}
the.file.rdata <- pp(head(str_split(the.file,'xml')[[1]],-1),'Rdata')
the.file.csv <- pp(head(str_split(the.file,'xml')[[1]],-1),'csv')

dat <- xmlParse(file.path)

parse.time <- function(time.str){
  suppressWarnings(as.numeric(to.posix(pp('2020-01-01 ',time.str),'%Y-%m-%d %H:%M:%S',tz='UTC') - to.posix(pp('2020-01-01 ',"00:00:00"),'%Y-%m-%d %H:%M:%S',tz='UTC'))*3600)
}

plans <- rbindlist(lapply(getNodeSet(dat,'/population/person'),function(person){ 
   person.id <- xmlGetAttr(person,'id') 
   sub.table <- rbindlist(lapply(getNodeSet(person,'./plan'),function(plan){
    plan.score <-  xmlGetAttr(plan,'score')
    is.selected <-  xmlGetAttr(plan,'selected')
    sub.sub.table <- rbindlist(lapply(getNodeSet(plan,'./*'),function(plan.elem){
      if(xmlName(plan.elem)=='activity'){
        data.table('plan.element'='activity','type'=xmlGetAttr(plan.elem,'type'),'link'=xmlGetAttr(plan.elem,'link'),'end_time'=ifelse(is.null(xmlGetAttr(plan.elem,'end_time')),'',xmlGetAttr(plan.elem,'end_time')))
      }else if(xmlName(plan.elem)=='leg'){
        route <- ifelse(length(getNodeSet(plan.elem,'route'))>0,xmlValue(getNodeSet(plan.elem,'route')[[1]]),"")
        data.table('plan.element'='leg','mode'=ifelse(is.null(xmlGetAttr(plan.elem,'mode')),'',xmlGetAttr(plan.elem,'mode')),'dep_time'=parse.time(xmlGetAttr(plan.elem,'dep_time')),'trav_time'=parse.time(xmlGetAttr(plan.elem,'trav_time')))
      }else{
        data.table()
      }
    }),use.names=T,fill=T)
    data.table('score'=plan.score,'selected'=is.selected,sub.sub.table)
  }),use.names=T,fill=T,idcol=T)
  data.table(person=person.id,sub.table)
}),use.names=T,fill=T)

save(plans,file=pp(the.dir,the.file.rdata))
write.csv(plans,file=pp(the.dir,the.file.csv))

