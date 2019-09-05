#!/usr/local/bin/Rscript
##############################################################################################################################################
# Script to convert CSV files to an R data table and then save as Rdata. This is intended to be run from the project root directory.
##############################################################################################################################################
library(colinmisc)
setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')

##############################################################################################################################################
# LOAD LIBRARIES NEED BY THIS SCRIPT
load.libraries(c('optparse','RCurl','stringr','aws.s3'),quietly=T)

##############################################################################################################################################
# COMMAND LINE OPTIONS 
option_list <- list()
if(interactive()){
  #setwd('~/downs/')
  args<-c('/Users/critter/Dropbox/ucb/vto/beam-colin/analysis/activity/plans.csv')
  args <- parse_args(OptionParser(option_list = option_list,usage = "commute-distance.R [files-to-convert]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "commute-distance.R [files-to-convert]"),positional_arguments=T)
}

local.dir <- '/Users/critter/Documents/beam/beam-output/commutes/'
make.dir(local.dir)

plans <- data.table(read.csv('/Users/critter/Dropbox/ucb/vto/beam-colin/analysis/activity/plans.csv',stringsAsFactors=F))
plans[,plan.localfile:=tempfile()]
plans[,skim.localfile:=tempfile()]
for(i in 1:nrow(plans)){
  print(plans[i,.(year,config)])
  local.subdir <-  pp(local.dir,head(tail(str_split(plans$url[i],"\\/")[[1]],ifelse(substrRight(plans$url[1],1)=="/",2,1)),1),"/")
  make.dir(local.subdir)
  local.path <- pp(local.subdir,"plans.csv.gz")
  plans[i,plan.localfile:=local.path]
  if(!file.exists(plans$plan.localfile[i])){
    if(grepl("beam-outputs.s3",plans$url[i])){
      if(substr(plans$url[i],0,5)=='https'){
        http.str <- 'https'
      }else{
        http.str <- 'http'
      }
      bucket.name <- str_split(str_split(plans$url[i],pp(http.str,"://"))[[1]][2],".s3.")[[1]][1]
      object.path <- pp(str_split(str_split(plans$url[i],pp(http.str,"://"))[[1]][2],"amazonaws.com/")[[1]][2],"plans.csv.gz")
      df <- NULL
      tryCatch(df <- data.table(s3read_using(read.csv,object=object.path,bucket=bucket.name)),error=function(e){})
      if(!is.null(df)){
        write.csv(df,file=plans$plan.localfile[i])
        save(df,file=pp(str_split(plans$plan.localfile[i],".csv.gz")[[1]][1],".Rdata"))
      }
    }else{
      tryCatch(download.file(pp(plans$url[i],'/plans.csv.gz'),plans[i]$plan.localfile),error=function(e){})
    }
  }
  if(file.exists(plans$plan.localfile[i])){
    df <- csv2rdata(plans[i]$plan.localfile)
    ds <- df[activityType%in%c('Home','Work'),.(x=c(activityLocationX[activityType=='Home'][1], activityLocationX[activityType=='Work'][1]),y=c(activityLocationY[activityType=='Home'][1], activityLocationY[activityType=='Work'][1])),by='personId'][,.(d=sqrt(diff(x)^2+diff(y)^2)/1609),by='personId']
    print(summary(ds$d))
    plans[i,min.dist:=min(ds$d)]
    plans[i,max.dist:=max(ds$d)]
    plans[i,mean.dist:=mean(ds$d)]
    plans[i,median.dist:=median(ds$d)]
  }
  it <- as.numeric(substr(plans$config[i],4,4))
  local.path <- pp(local.subdir,"skims.csv.gz")
  plans[i,skim.localfile:=local.path]
  if(!file.exists(plans$skim.localfile[i])){
    if(grepl("beam-outputs.s3",plans$url[i])){
      if(substr(plans$url[i],0,5)=='https'){
        http.str <- 'https'
      }else{
        http.str <- 'http'
      }
      bucket.name <- str_split(str_split(plans$url[i],pp(http.str,"://"))[[1]][2],".s3.")[[1]][1]
      object.path <- pp(str_split(str_split(plans$url[i],pp(http.str,"://"))[[1]][2],"amazonaws.com/")[[1]][2],'ITERS/it.',it,'/',it,'.skims.csv.gz')
      df <- NULL
      tryCatch(df <- data.table(s3read_using(read.csv,object=object.path,bucket=bucket.name)),error=function(e){})
      if(!is.null(df)){
        write.csv(df,file=plans$skim.localfile[i])
        save(df,file=pp(str_split(plans$skim.localfile[i],".csv.gz")[[1]][1],".Rdata"))
      }
    }else{
      tryCatch(download.file(pp(plans$url[i],'/ITERS/it.',it,'/',it,'.skims.csv.gz'),plans$skim.localfile[i]),error=function(e){})
    }
  }
  if(file.exists(plans$skim.localfile[i])){
    df <- csv2rdata(plans$skim.localfile[i])[mode=='CAR']
    plans[i,generalizedCost:=weighted.mean(df$generalizedCost,df$numObservations)]
    plans[i,generalizedTimeMinutes:=weighted.mean(df$generalizedTimeInS/60,df$numObservations)]
    plans[i,travelTimeMinutes:=weighted.mean(df$travelTimeInS/60,df$numObservations)]
    plans[i,milesTraveled:=weighted.mean(df$distanceInM/1609,df$numObservations)]
  }
  print(plans[i])
}
write.csv(plans,'/Users/critter/Dropbox/ucb/vto/beam-colin/analysis/activity/plans-out.csv')
ggplot(melt(plans[,.(year,config,frequency,mean.dist)],id.vars=c('year','config','frequency')),aes(x=year,y=value,colour=config,shape=config))+geom_line()+geom_point()+labs(x='Year',y='Mean Home--Work Distance (mi)',colour='Scenario',shape='Scenario')+facet_wrap(~frequency)
ggplot(melt(plans[,-3,with=F],id.vars=c('year','config')),aes(x=year,y=value,colour=variable))+geom_line()+facet_wrap(~config)

# Skims
ggplot(melt(plans[,.(year,config,frequency,generalizedTimeMinutes,generalizedCost,travelTimeMinutes,milesTraveled)],id.vars=c('year','config','frequency')),aes(x=year,y=value,colour=config,shape=config))+geom_line()+geom_point()+labs(x='Year',y='Value',colour='Scenario',shape='Scenario')+facet_grid(variable~frequency,scales='free_y')

# BAUS using
# workplace location
#   general TT CAR, WT, negative roughly same magnitude
#   general Cost CAR always interacted HH income, coefficient is negative, -0.4, -0.3, -0.15 (most coefficients are -2 to 2) taking log of cost
# mode choice
