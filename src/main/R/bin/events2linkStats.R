#!/usr/local/bin/Rscript
##############################################################################################################################################
# 
# 
# Argument: the path to the run output directory.
##############################################################################################################################################
library(colinmisc)
setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')
load.libraries(c('optparse','stringr'),quietly=T)

##############################################################################################################################################
# COMMAND LINE OPTIONS 

option_list <- list(
)
if(interactive()){
  args<-'/Users/critter/Documents/beam/beam-output/beamville__2019-12-11_17-16-23/'
  args <- parse_args(OptionParser(option_list = option_list,usage = "events2linkStats.R [config-file]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "events2linkStats.R [config-file]"),positional_arguments=T)
}
######################################################################################################

######################################################################################################
# Load the exp config

run.dir <- dir.slash(args$args)
run.name <- tail(head(str_split(run.dir,"/")[[1]],-1),1)
iter.dir <- ifelse("ITERS"%in%list.dirs(run.dir,recur=F,full.names=F),pp(run.dir,'ITERS/'),pp(run.dir,'output/ITERS/'))
conf.file <- pp(iter.dir,'../beam.conf')
plots.dir <- pp(run.dir,'plots/')
make.dir(plots.dir)

# last iter only
iter <- tail(grep('tripHist',list.dirs(iter.dir,full.names=F),value=T,invert=T),1)
my.cat(iter)
iter.i <- as.numeric(tail(str_split(iter,'\\.')[[1]],1))

# grab linkStats as currently exist
links.csv <- pp(iter.dir,iter,'/',iter.i,'.linkstats.csv')
if(file.exists(links.csv) | file.exists(pp(links.csv,'.gz'))){
  stats <- csv2rdata(links.csv)
}

# load events
events.csv <- pp(iter.dir,iter,'/',iter.i,'.events.csv')
ev <- csv2rdata(events.csv)[mode%in%c('car','bus')]
ev[,iter:=iter.i]
ev <- ev[type%in%c('PathTraversal')]
ev[,row:=1:nrow(ev)]
linkStarts <- ev[,.(start=time,id=str_split(links,",")[[1]]),by='row']
linkStarts[,id:=as.numeric(id)]
linkStarts[,hour:=floor(start/3600)]

linkStarts <- join.on(linkStarts[!is.na(id)],stats,c('id','hour'),c('link','hour'),c('freespeed','traveltime','length','volume'))
linkStarts[,speed:=length/traveltime]

setkey(linkStarts,row,start)
linkStarts[,enter:=start+c(0,cumsum(head(traveltime,-1))),by='row']
linkStarts[,hour.new:=floor(enter/3600)]
volumes <- linkStarts[,.(volume=.N),by=c('id','hour.new')]

stats <- join.on(stats,volumes,c('link','hour'),c('id','hour.new'),'volume','new.')
stats[,volume:=ifelse(is.na(new.volume),0,new.volume)]
stats[,new.volume:=NULL]
stats[,traveltime:=apply(cbind(traveltime,length),1,function(x){ max(x[1],x[2]/1.3) })]

write.csv(stats,pp(iter.dir,iter,'/',iter.i,'.linkstats-corrected.csv'),row.names=F)

# The less generic version of this, process smart results
if(F){
  i<-10

  library(colinmisc)
  setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
  source('./src/main/R/beam-utilities.R')
  load.libraries(c('optparse','stringr'),quietly=T)
  res.dir <- '/Users/critter/Dropbox/ucb/vto/smart-mobility/final-results/'
  runs <- data.table(read.csv(pp(res.dir,'runs.csv'),stringsAsFactors=F))
  make.dir(pp(res.dir,'runs'))
  runs[,local.file:=pp(res.dir,'runs/',scen,'-events.csv.gz')]
  runs[,local.stats:=pp(res.dir,'runs/',scen,'-linkstats.csv.gz')]
  runs[,url.corrected:=as.character(url)]
  runs[grepl('html\\#',url),url.corrected:=unlist(lapply(str_split(runs[grepl('html\\#',url)]$url,'s3.us-east-2.amazonaws.com/beam-outputs/index.html#'),function(ll){ pp('https://beam-outputs.s3.amazonaws.com/',ll[2]) }))]
  #load('/Users/critter/Dropbox/ucb/vto/beam-colin/sf-bay-area/bay_area_simplified_tertiary_strongly_2_way_network/physsim-network.Rdata')

  all.stats <- list()
  all.ev <- list()
  for(i in 1:nrow(runs)){
    my.cat(pp(names(runs),":",runs[i],collapse=' , '))
    if(!file.exists(runs$local.file[i])){
      for(it in 15:0){
        tryCatch(download.file(pp(runs$url.corrected[i],'ITERS/it.',it,'/',it,'.events.csv.gz'),runs$local.file[i]),error=function(e){})
        tryCatch(download.file(pp(runs$url.corrected[i],'ITERS/it.',it,'/',it,'.linkstats.csv.gz'),runs$local.stats[i]),error=function(e){})
        if(file.exists(runs$local.file[i]))break
      }
    }
    stat.cor.csv <- pp(res.dir,'runs/',runs$scen[i],'-linkstats-corrected.csv')
    ev <- csv2rdata(runs$local.file[i])[type=='PathTraversal' & mode%in%c('car','bus')]
    if(!file.exists(stat.cor.csv)){
      stats <- csv2rdata(runs$local.stats[i])
      ev[,row:=1:nrow(ev)]
      splitLinks <- str_split(ev$links,",")
      splitTTs <- str_split(ev$linkTravelTime,",")
      linkStarts <- rbindlist(lapply(1:nrow(ev),function(ll){ 
                                       if(length(splitLinks[[ll]])>1){
                                         data.table(start=ev$time[ll],id=tail(splitLinks[[ll]],-1),tt=tail(as.numeric(splitTTs[[ll]]),-1),row=ll) 
                                       }else{
                                         data.table(start=ev$time[ll],id=splitLinks[[ll]],tt=as.numeric(splitTTs[[ll]]),row=ll) 
                                       }
      }))
      linkStarts[,id:=as.numeric(id)]
      linkStarts[,hour:=floor(start/3600)]
      linkStarts <- join.on(linkStarts,stats,c('id','hour'),c('link','hour'),c('freespeed','length'))
      linkStarts[,new.tt:=apply(cbind(tt,length,freespeed),1,function(x){ max(min(x[1],x[2]/1.3),x[2]/x[3]) })]

      setkey(linkStarts,row,start)
      linkStarts[,enter:=start+c(0,cumsum(head(new.tt,-1))),by='row']
      linkStarts[,hour.new:=floor(enter/3600)]
      save(linkStarts,file= pp(res.dir,'runs/',runs$scen[i],'-linkStarts.Rdata'))
      volumes <- linkStarts[,.(volume=.N,traveltime=mean(new.tt,na.rm=T)),by=c('id','hour.new')]

      stats <- join.on(stats,volumes,c('link','hour'),c('id','hour.new'),c('volume','traveltime'),'new.')
      stats[,volume:=ifelse(is.na(new.volume),0,new.volume)]
      stats[,traveltime:=ifelse(is.na(new.traveltime),length/freespeed,new.traveltime)]
      stats[,new.volume:=NULL]
      stats[,new.traveltime:=NULL]
      #stats[,traveltime:=apply(cbind(traveltime,length),1,function(x){ min(x[1],x[2]/1.3) })]

      write.csv(stats,stat.cor.csv,row.names=F)
    }
    stats <- csv2rdata(stat.cor.csv)
    stats[,scenario:=runs$scen[i]]
    all.stats[[length(all.stats)+1]] <- stats
    ev[,scenario:=runs$scen[i]]
    all.ev[[length(all.ev)+1]] <- ev
    rm(list=c('ev','stats'))
  }
  all.stats <- rbindlist(all.stats,use.names=T,fill=T)
  all.ev <- rbindlist(all.ev,use.names=T,fill=T)
  all.ev[,length:=as.numeric(as.character(length))]
  all.ev[,arrivalTime:=as.numeric(as.character(arrivalTime))]
  all.ev[,departureTime:=as.numeric(as.character(departureTime))]

  # compare them
  both <- join.on(all.stats[,.(vmt=sum(volume*length)/1609,vht=sum(volume*traveltime)/3600),by='scenario'],
                  all.ev[,.(vmt=sum(length,na.rm=T)/1609,vht=sum(arrivalTime-departureTime,na.rm=T)/3600),by='scenario'],'scenario','scenario',c('vmt','vht'),'ev.')
  both[,':='(diff.vmt=(ev.vmt-vmt)/ev.vmt,diff.vht=(ev.vht-vht)/ev.vht,sp=vmt/vht,ev.sp=ev.vmt/ev.vht)]
  both[,':='(diff.sp=(ev.sp-sp)/ev.sp)]


  # code used once to correct TTs
  #all.stats.new <- list()
  #for(i in 1:nrow(runs)){
    #old.stats <- csv2rdata(runs$local.stats[i])
    #new.stats <- all.stats[scenario==runs$scen[i]]
    #setkey(old.stats,link,hour)
    #setkey(new.stats,link,hour)
    #new.stats[,traveltime:=old.stats$traveltime]
    #new.stats[,traveltime:=apply(cbind(traveltime,length),1,function(x){ min(x[1],x[2]/1.3) })]
    #stat.cor.csv <- pp(res.dir,'runs/',runs$scen[i],'-linkstats-corrected.csv')
    #write.csv(new.stats,stat.cor.csv,row.names=F)
    #new.stats[,scenario:=runs$scen[i]]
    #all.stats.new[[length(all.stats.new)+1]] <- new.stats
  #}
  #all.stats.new <- rbindlist(all.stats.new)

  #all.stats <- list()
  #for(i in 1:nrow(runs)){
    #stat.cor.csv <- pp(res.dir,'runs/',runs$scen[i],'-linkstats-corrected.csv')
    #stats <- csv2rdata(stat.cor.csv)
    #stats[,scenario:=runs$scen[i]]
    #all.stats[[length(all.stats)+1]] <- stats
  #}
  #all.stats <- rbindlist(all.stats)
}
