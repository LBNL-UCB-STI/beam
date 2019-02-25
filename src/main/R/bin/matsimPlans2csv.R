#!/usr/local/bin/Rscript
##############################################################################################################################################
# Script to convert XML plan file to an R data table and then save as Rdata 
##############################################################################################################################################
# Example of using this on all experienced plans from a BEAM run:
#  ls /Users/critter/Documents/beam/beam-output/beamville__2018-12-11_15-08-55/ITERS/*/*.experiencedPlans.xml.gz | xargs matsimPlans2csv.R

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
all.plans <- list()
for(file.path in args$args){

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

  if(file.exists(pp(the.dir,the.file.rdata))){
    load(pp(the.dir,the.file.rdata))
  }else{
    dat <- xmlParse(file.path)
    parse.time <- function(time.str){
      if(time.str=='')return(NA)
      the.time <- suppressWarnings(as.numeric(difftime(to.posix(pp('2020-01-01 ',time.str),'%Y-%m-%d %H:%M:%S',tz='UTC'),to.posix(pp('2020-01-01 ',"00:00:00"),'%Y-%m-%d %H:%M:%S',tz='UTC'),units='secs')))
      if(is.na(the.time)){
        the.split <- str_split(time.str,":")[[1]]
        new.str <- pp(as.numeric(the.split[1])-24,':',pp(the.split[2:3],collapse=':'))
        parse.time(new.str) + 24*3600
      }else{
        the.time
      }
    }

    my.get <- function(node,attr){ ifelse(is.null(xmlGetAttr(node,attr)),"",xmlGetAttr(node,attr)) }

    plans <- rbindlist(lapply(getNodeSet(dat,'/population/person'),function(person){ 
       person.id <- xmlGetAttr(person,'id') 
       sub.table <- rbindlist(lapply(getNodeSet(person,'./plan'),function(plan){
        plan.score <-  my.get(plan,'score')
        is.selected <-  my.get(plan,'selected')
        sub.sub.table <- rbindlist(lapply(getNodeSet(plan,'./*'),function(plan.elem){
          if(xmlName(plan.elem)=='activity'){
            data.table('plan.element'='activity','type'=xmlGetAttr(plan.elem,'type'),'link'=my.get(plan.elem,'link'),'x'=my.get(plan.elem,'x'),'y'=my.get(plan.elem,'y'),'end_time'=parse.time(my.get(plan.elem,'end_time')))
          }else if(xmlName(plan.elem)=='leg'){
            route <- ifelse(length(getNodeSet(plan.elem,'route'))>0,xmlValue(getNodeSet(plan.elem,'route')[[1]]),"")
            data.table('plan.element'='leg','mode'=my.get(plan.elem,'mode'),'',my.get(plan.elem,'mode'),'dep_time'=parse.time(my.get(plan.elem,'dep_time')),'trav_time'=parse.time(my.get(plan.elem,'trav_time')),'route'=route)
          }else{
            data.table()
          }
        }),use.names=T,fill=T)
        data.table('score'=plan.score,'selected'=is.selected,sub.sub.table)
      }),use.names=T,fill=T,idcol=T)
      data.table(person=person.id,sub.table)
    }),use.names=T,fill=T)

    plans[,':='(plan.id=.id,.id=NULL)]

    save(plans,file=pp(the.dir,the.file.rdata))
    write.csv(plans,file=pp(the.dir,the.file.csv),na='',row.names=F)
  }
  plans[,file:=file.path]
  all.plans[[length(all.plans)+1]] <- plans
}
if(length(all.plans)>1){
  all.plans <- rbindlist(all.plans,use.names=T,fill=T)
  all.plans[,iter:=unlist(lapply(str_split(file,"it\\."),function(ll){ as.numeric(str_split(ll[2],"\\/")[[1]][1])}))]
  all.plans[,file:=NULL]
  save(all.plans,file=pp(the.dir,'all-plans.Rdata'))
}
