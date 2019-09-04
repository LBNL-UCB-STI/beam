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
option_list <- list(
)
if(interactive()){
  #setwd('~/downs/')
  args<-c('http://ec2-52-15-99-212.us-east-2.compute.amazonaws.com:8000/output/sfbay/sfbay-smart-base-pilates__2019-08-29_00-11-27/plans.csv.gz',
  'http://ec2-52-15-124-183.us-east-2.compute.amazonaws.com:8000/output/sfbay/sfbay-smart-a-lt-pilates__2019-08-28_20-58-15/plans.csv.gz',
  'http://ec2-3-16-24-65.us-east-2.compute.amazonaws.com:8000/output/sfbay/sfbay-smart-a-ht-pilates__2019-08-28_20-49-37/plans.csv.gz',
  'http://ec2-18-188-152-48.us-east-2.compute.amazonaws.com:8000/output/sfbay/sfbay-smart-b-lt-pilates__2019-08-29_07-32-44/plans.csv.gz',
  'http://ec2-3-14-87-243.us-east-2.compute.amazonaws.com:8000/output/sfbay/sfbay-smart-b-ht-pilates__2019-08-30_17-49-55/plans.csv.gz',
  'http://ec2-18-222-190-38.us-east-2.compute.amazonaws.com:8000/output/sfbay/sfbay-smart-c-lt-pilates__2019-08-28_23-28-15/plans.csv.gz',
  'http://ec2-18-217-26-244.us-east-2.compute.amazonaws.com:8000/output/sfbay/sfbay-smart-c-ht-pilates__2019-08-29_01-05-16/plans.csv.gz'
  )
  args <- parse_args(OptionParser(option_list = option_list,usage = "commute-distance.R [files-to-convert]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "commute-distance.R [files-to-convert]"),positional_arguments=T)
}

######################################################################################################
file.path <- args$args[1]
for(file.path in args$args){
  if(grepl("amazonaws.com:8000",file.path)){
    df <- data.table(read.csv(file.path))
  }else{
    if(substr(file.path,0,4)=='http'){
      if(substr(file.path,0,5)=='https'){
        http.str <- 'https'
      }else{
        http.str <- 'http'
      }
      bucket.name <- str_split(str_split(file.path,pp(http.str,"://"))[[1]][2],".s3.")[[1]][1]
      object.path <- str_split(str_split(file.path,pp(http.str,"://"))[[1]][2],"amazonaws.com/")[[1]][2]
      my.cat(str_split(object.path,"/sfbay/")[[1]][2])
      df <- data.table(s3read_using(read.csv,object=object.path,bucket=bucket.name))
    }
  }
  ds <- df[activityType%in%c('Home','Work'),.(x=c(activityLocationX[activityType=='Home'][1], activityLocationX[activityType=='Work'][1]),y=c(activityLocationY[activityType=='Home'][1], activityLocationY[activityType=='Work'][1])),by='personId'][,.(d=sqrt(diff(x)^2+diff(y)^2)/1609),by='personId']
  print(summary(ds$d))
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
ggplot(melt(plans[,.(year,config,frequency,generalizedTimeMinutes,generalizedCost,travelTimeMinutes,milesTraveled)],id.vars=c('year','config','frequency')),aes(x=year,y=value,colour=config,shape=config))+geom_line()+geom_point()+labs(x='Year',y='Value',colour='Scenario',shape='Scenario')+facet_grid(frequency~variable,scales='free_y')

# BAUS using
# workplace location
#   general TT CAR, WT, negative roughly same magnitude
#   general Cost CAR always interacted HH income, coefficient is negative, -0.4, -0.3, -0.15 (most coefficients are -2 to 2) taking log of cost
# mode choice
