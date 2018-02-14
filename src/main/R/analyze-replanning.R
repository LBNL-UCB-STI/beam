
options(gsubfn.engine = "R")
load.libraries(c('sqldf','R.utils'))
setwd('/Users/critter/Dropbox/ucb/vto/beam-all/beam') # for development and debugging
source('./src/main/R/beam-utilities.R')

run.names <- c('25k-noisy'='sf-light-25k_2018-02-13_08-48-21')
run.names <- c('25k-styles-only'='sf-light-25k_2018-02-13_15-04-19')
run.names <- c('10k-styles-and-tour-based-choice'='sf-light-10k_2018-02-13_22-53-55')

update.exp.plans <- T
update.events <- F
temp.dir <- tempdir()
all.tmp <- list()
all.events <- list()
if(update.exp.plans & exists('exp.plans'))rm('exp.plans')
if(update.events & exists('ev'))rm('ev')
run.name <- run.names[1]
for(run.name in run.names){
  run.code <- names(run.names)[which(run.name==run.names)]
  run.dir <- pp('/Users/critter/Documents/beam/beam-output/',run.name)

  iter.dir <- list.dirs(pp(run.dir,'/ITERS'),recursive=F)[1]
  for(iter.dir in list.dirs(pp(run.dir,'/ITERS'),recursive=F)){
    split.parts <- str_split(list.files(iter.dir)[1],'\\.')[[1]]
    iter.num <- split.parts[1]
    the.file <- pp(iter.dir,'/',iter.num,'.population.csv.gz')
    the.file.Rdata <- pp(iter.dir,'/',iter.num,'.population.Rdata')
    if(update.exp.plans & file.exists(the.file)){
      if(exists('dt'))rm(dt)
      do.or.load(the.file.Rdata,function(){
        dt <- data.table(read.csv(gzfile(the.file),header=F,skip=1))
        dt[,run:=run.code]
        dt[,iter:=as.numeric(iter.num)]
        return(list(dt=dt))
      })
      all.tmp[[length(all.tmp)+1]] <- dt 
    }
    the.file <- pp(iter.dir,'/',iter.num,'.events.csv')
    if(update.events & file.exists(the.file)){
      dt <- csv2rdata(the.file)
      dt[,run:=run.code]
      dt[,iter:=as.numeric(iter.num)]
      all.events[[length(all.events)+1]] <- dt
    }
  }
}
if(update.exp.plans) exp.plans <- rbindlist(all.tmp)
if(update.events) ev <- rbindlist(all.events)
rm(all.tmp,all.events,dt)

exp.plans
names(exp.plans) <- c('id','type','x','y','end.time','modalityStyle',sapply(1:6,function(x){ c(pp('class',x),pp('logSum',x)) }),'run','iter')
exp.plans <- exp.plans[id != 'id']

styles <- exp.plans[modalityStyle!='',.(n=length(id)),by=c('iter','modalityStyle')]

setkey(styles,iter,modalityStyle)
ggplot(styles,aes(x=iter,y=n,fill=modalityStyle))+geom_bar(position='stack',stat='identity')
