
load.libraries('stringr')

dir <- '/Users/critter/Dropbox/ucb/vto/beam-colin/benchmark/parallelism-window/runs'


stats <- list()
run.dir <- list.files(dir)[1]
for(run.dir in list.files(dir)){

  wind <- as.numeric(unlist(str_split(tail(unlist(lapply(str_split(run.dir,'__'),function(ll){ str_split(head(ll,1),"-")[[1]] })),1),'s')[[1]][1]))

  stat <- data.table(read.csv(pp(dir,'/',run.dir,'/summaryStats.csv')))

  stat[,parallelismWindow:=wind]
  stats[[length(stats)+1]] <- stat
}
stats <- rbindlist(stats,fill=T,use.names=T)
stats.m <- data.table(melt(stats,id.vars=c('Iteration', 'parallelismWindow')))

#ggplot(stats.m,aes(x= Iteration,y=value,colour=factor(parallelismWindow)))+facet_wrap(~variable,scales='free_y')+geom_point()
#ggplot(stats.m,aes(x= parallelismWindow,y=value,colour=factor(Iteration)))+facet_wrap(~variable,scales='free_y')+geom_point()


for(metric in grep("Subsidy",u(stats.m$variable),value=T,invert=T)){
  p <- ggplot(stats.m[variable==metric],aes(x= factor(parallelismWindow),y=value))+facet_wrap(~Iteration)+geom_boxplot()+labs(title=metric)
  ggsave(pp(dir,'/../metric-',metric,'.pdf'),p,width=10,height=6,units='in')
}

iter <- 4
metric <- 'vehicleMilesTraveled_Car'
for(iter in u(stats.m$Iteration)){
  for(metric in grep("Subsidy",u(stats.m$variable),value=T,invert=T)){
    p <- ggplot(stats.m[variable==metric & Iteration==iter],aes(x= factor(parallelismWindow),y=value))+geom_boxplot()+labs(title=pp(metric,'--',iter),x='Parallelism Window (sec)',y='Vehicle Miles Travelled')
    ggsave(pp(dir,'/../iter-',iter,'-metric-',metric,'.pdf'),p,width=10,height=6,units='in')
  }
}


runtimes <- data.table(read.csv('/Users/critter/Dropbox/ucb/vto/beam-colin/benchmark/parallel-runtimes.csv'))
ggplot(runtimes,aes(x=window,y=min))+geom_line()+geom_point()+labs(title='Runtime for 25k Scenario, 5 Iterations on Personal Computer',x='Parallelism Window (sec)',y='Model Run Time (min)')+scale_y_continuous(breaks=c(0,70))
