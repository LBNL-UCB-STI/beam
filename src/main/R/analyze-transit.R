

#!/usr/local/bin/Rscript
##############################################################################################################################################
# Script to process results of a BEAM experiment and create some default plots comparing the runs against each other by the 
# factors. This is intended to be run from the project root directory.
# 
# Argument: the path to the experiment directory containing the .yaml file defining the experiment *and* the runs directory containing the 
# results.
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
  args<-'/Users/critter/Dropbox/ucb/vto/beam-colin/analysis/transit/'
  args <- parse_args(OptionParser(option_list = option_list,usage = "exp2plots.R [experiment-directory]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "exp2plots.R [experiment-directory]"),positional_arguments=T)
}
plots.dir <- pp(args$args,'plots/')
######################################################################################################
###
factor.to.scale.personal.back <- 25
factor.to.scale.transit.back <- 1/.22 # inverse of param: beam.agentsim.tuning.transitCapacity

# App: https://s3.us-east-2.amazonaws.com/beam-outputs/index.html#output/sfbay/sfbay-smart-base__2019-08-19_14-49-43/

runs <- c('No Transit'='https://beam-outputs.s3.amazonaws.com/output/sfbay/sfbay-smart-base-no-transit__2019-08-21_19-51-37',
'Half Transit'='https://beam-outputs.s3.amazonaws.com/output/sfbay/sfbay-smart-base__2019-08-21_19-51-38',
'Base'='http://ec2-34-223-53-29.us-west-2.compute.amazonaws.com:8000/output/sfbay/sfbay-smart-base__2019-08-23_03-38-14/',
'BART +40%'='https://beam-outputs.s3.amazonaws.com/output/sfbay/sfbay-smart-bart-capacity__2019-08-21_00-38-37',
'Free'='https://beam-outputs.s3.amazonaws.com/output/sfbay/sfbay-smart-transit-free-plus__2019-08-21_00-38-37',
'Free+1'='https://beam-outputs.s3.amazonaws.com/output/sfbay/sfbay-smart-transit-free__2019-08-21_00-38-37')

st <- list()
mc <- list()
for(run.i in 1:length(runs)){
  stats.file <- pp(runs[run.i],'/summaryStats.csv')
  stats <- data.table(read.csv(stats.file,row.names=NULL))
  stats[,scen:=names(runs)[run.i]]
  stats[,':='(iter=Iteration,Iteration=NULL)]
  st[[length(st)+1]] <- stats
  mc.file <- pp(runs[run.i],'/realizedModeChoice.csv')
  mcs <- data.table(read.csv(mc.file,row.names=NULL))
  mcs[,scen:=names(runs)[run.i]]
  mcs[,':='(iter=iterations,iterations=NULL)]
  mc[[length(mc)+1]] <- mcs
}
st <- melt(rbindlist(st,fill=T),id.vars=c('scen','iter'))
st[,scen:=factor(scen,names(runs))]
mc <- melt(rbindlist(mc,fill=T),id.vars=c('scen','iter'))
mc[,scen:=factor(scen,names(runs))]
last.iter <- min(st[,.(last=max(iter)),by='scen']$last)

fuel <- st[substr(variable,0,12)=='fuelConsumed']
fuel[,fuel.type:=unlist(lapply(str_split(variable,'_'),function(ll){ll[2]}))]
fuel <- fuel[fuel.type%in%c('Gasoline','Electricity')]
fuel[fuel.type=='Electricity',value:=value*10]
vmt <-  st[substr(variable,0,12)=='vehicleMiles']
vmt[,ldv:=grepl('LowTech',variable)]
vmt[,value:=value*factor.to.scale.personal.back]
pht <-  st[substr(variable,0,12)=='personTravel']
pht[,mode:=unlist(lapply(str_split(variable,'Time_'),function(ll){ll[2]}))]
pht[,pretty.mode:=pretty.modes(mode)]
pht[,hours:=value/60*factor.to.scale.personal.back]
pht <- pht[mode!='mixed_mode']
mc.sum <- mc[,.(tot=sum(value,na.rm=T)),by=c('scen','iter')]
mc <- join.on(mc,mc.sum,c('scen','iter'),c('scen','iter'))
mc[,frac:=value/tot]
mc[,pretty.mode:=pretty.modes(variable)]

pdf.scale <- .6
#p <- ggplot(pht,aes(x=iter,y=hours,fill=mode))+geom_bar(stat='identity',position='stack')+facet_wrap(~scen)
p <- ggplot(pht[iter==last.iter & mode!='mixed_mode'],aes(x=scen,y=hours,fill=pretty.mode))+geom_bar(stat='identity',position='stack')+
  labs(x="Scenario",y="Hours",title='Person Hours Traveled',fill="Trip Mode")+theme(axis.text.x = element_text(angle = 25, hjust = 1))+
  scale_fill_manual(values=as.character(mode.colors$color.hex[match(sort(u(pht$pretty.mode)),mode.colors$key)]))
ggsave(pp(plots.dir,'person-hours-traveled.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')
p <- ggplot(vmt[iter==0 & (ldv),.(tot=sum(value)),by=c('scen')],aes(x=scen,y=tot))+geom_bar(stat='identity',position='stack')+
  labs(x="Scenario",y="Miles",title='Light Duty Vehicle Miles Traveled',fill="Trip Mode")+theme(axis.text.x = element_text(angle = 25, hjust = 1))+
  scale_fill_manual(values=as.character(mode.colors$color.hex[match(sort(u(vmt$pretty.mode)),mode.colors$key)]))
ggsave(pp(plots.dir,'vmt.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')
p <- ggplot(mc[iter==last.iter],aes(x=scen,y=frac*100,fill=pretty.mode))+geom_bar(stat='identity',position='stack')+
  labs(x="Scenario",y="% of Total",title='Mode Share',fill="Mode")+theme(axis.text.x = element_text(angle = 25, hjust = 1))+
  scale_fill_manual(values=as.character(mode.colors$color.hex[match(sort(u(mc$pretty.mode)),mode.colors$key)]))
ggsave(pp(plots.dir,'mode-shares.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')
p <- ggplot(fuel[iter==last.iter,.(tot=sum(value)),by=c('scen','fuel.type')],aes(x=scen,y=tot/1e6,fill=fuel.type))+geom_bar(stat='identity',position='stack')+
  labs(x="Scenario",y="Fuel Energy (PJ)",title='Energy By Fuel Type',fill="Fuel Type")+theme(axis.text.x = element_text(angle = 25, hjust = 1))+
  scale_fill_manual(values=as.character(c(my.colors['orange'],my.colors['grey'],my.colors['blue'],my.colors['red'])))
ggsave(pp(plots.dir,'energy.pdf'),p,width=10*pdf.scale,height=6*pdf.scale,units='in')

