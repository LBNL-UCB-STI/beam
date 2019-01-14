#!/usr/local/bin/Rscript
##############################################################################################################################################
# Script to process results of a single BEAM run and create some plots analyzing links, network, etc.
# 
# Argument: the path to the run output directory.
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
  args<-'/Users/critter/Documents/beam/beam-output/sf-light-25k__2018-12-07_18-30-37/'
  args <- parse_args(OptionParser(option_list = option_list,usage = "run2linkStats.R [run-outputs-dir]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "run2linkStats.R [run-outputs-dir]"),positional_arguments=T)
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

links <- list()
iter <- tail(list.dirs(iter.dir,full.names=F),-1)[1]
for(iter in tail(list.dirs(iter.dir,full.names=F),-1)){
  my.cat(iter)
  iter.i <- as.numeric(tail(str_split(iter,'\\.')[[1]],1))

  links.csv <- pp(iter.dir,iter,'/',iter.i,'.linkstats.csv')
  if(file.exists(links.csv) | file.exists(pp(links.csv,'.gz'))){
    link <- csv2rdata(links.csv)
    link[,iter:=iter.i]
    links[[length(links)+1]] <- link
  }
}
link <- rbindlist(links)
rm('links')
 
setkey(link,stat)
link[,hr:=factor(as.numeric(as.character(hour)))]

############################
## Default Plots 
############################

p <- ggplot(link[J('AVG')][iter==0 & volume>0 & hour!='0.0 - 24.0'],aes(x= log10(length+0.1),y=volume,colour= log10(traveltime/60)))+geom_point()+facet_wrap(~hr)
pdf.scale <- .6
ggsave(pp(plots.dir,'volume-by-length-by-hour-iter-0.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

p <- ggplot(link[J('AVG')][volume>0 & hour!='0.0 - 24.0'],aes(x= log10(length+0.1),y=volume,colour= log10(traveltime/60)))+geom_point()+facet_wrap(~iter)
pdf.scale <- .6
ggsave(pp(plots.dir,'volume-by-length-by-iter.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

p <- ggplot(link[J('AVG')][volume>0 & hour!='0.0 - 24.0'],aes(x=volume))+geom_histogram()+facet_wrap(~iter)
pdf.scale <- .6
ggsave(pp(plots.dir,'volume-by-iter.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')

p <- ggplot(link[J('AVG')][volume>0 & hour!='0.0 - 24.0'],aes(x=traveltime/60))+geom_histogram()+facet_wrap(~iter)
pdf.scale <- .6
ggsave(pp(plots.dir,'traveltime-by-iter.pdf'),p,width=10*pdf.scale,height=8*pdf.scale,units='in')
