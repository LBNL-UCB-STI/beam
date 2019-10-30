library('yaml')
library('stringr')

load.experiment <- function(experiment.file,add.timestamp=T){
  if(file.exists(experiment.file)){
    exper <- list()
    exper$yaml <- yaml.load(readChar(experiment.file,file.info(experiment.file)$size))
    exper$num.factors <- length(exper$yaml$Factors)
    if(exper$yaml$Mode=='combinatorial'){
      exper$runs <- expand.grid(lapply(exper$yaml$Factors,function(fac){ fac$Levels }),stringsAsFactors=F)
      names(exper$runs) <- unlist(lapply(exper$yaml$Factors,function(fac){ fac$Name }))
      exper$runs <- data.table(exper$runs)
    }else{
      stop('only combinatorial experiments enabled for now, please set "Mode: combinatorial" in your yaml file')
    }
    # Setup the input directory
    exper$input.dir <- pp('experiments/',exper$yaml$Name)
    if(add.timestamp)exper$input.dir <- pp(exper$input.dir,'_',format(Sys.time(), "%Y-%m-%d_%H-%M-%S"))
    make.dir('experiments')
    make.dir(exper$input.dir)
    exper
  }else{
    stop(pp("Experiment file not found: ",experiment.file))
  }
}

proj.dir <- '~/Dropbox/ucb/vto/beam-all/beam-laptop/beam/'
exp.file <- pp(proj.dir,'src/main/R/make-smart-experiment.yaml')
afi.template <- pp(proj.dir,'production/sfbay/smart/afi-template.conf')
afi.raw <- readLines(afi.template)

runs <- load.experiment(exp.file)$runs
exper.name <- load.experiment(exp.file)$yaml$Name
runs <- runs[infrastructure!='none' | (infrastructure=='none' & power==50)]
runs[,num:=1:nrow(runs)]

deploy.command.raw <- pp('./gradlew deploy -PbeamConfigs=production/sfbay/smart/',exper.name,'-###infrastructure###-###range###mi-###power###kw-###scenario###-BEV###bev###.conf -Pregion=us-west-2 -Pbatch=false -PrunName=18-###runNumber###-',exper.name,'-###infrastructure###-###range###mi-###power###kw-###scenario###-BEV###bev### -PdeployMode=config -PinstanceType=m5.24xlarge -PbeamBranch=production-sfbay-develop-debug-afi-no-chargers -PbeamCommit=<commit>')

deploy.cmds <- c('#!/bin/bash')
for(i in 1:nrow(runs)){
  afi.new <- gsub(pattern="###scenario###",replace=runs$scenario[i],x=afi.raw)
  afi.new <- gsub(pattern="###power###",replace=runs$power[i],x=afi.new)
  afi.new <- gsub(pattern="###range###",replace=runs$range[i],x=afi.new)
  afi.new <- gsub(pattern="###bev###",replace=runs$bev[i],x=afi.new)
  afi.new <- gsub(pattern="###infrastructure###",replace=runs$infrastructure[i],x=afi.new)
  deploy.command.new <- gsub(pattern="###scenario###",replace=runs$scenario[i],x=deploy.command.raw)
  deploy.command.new <- gsub(pattern="###power###",replace=runs$power[i],x=deploy.command.new)
  deploy.command.new <- gsub(pattern="###range###",replace=runs$range[i],x=deploy.command.new)
  deploy.command.new <- gsub(pattern="###bev###",replace=runs$bev[i],x=deploy.command.new)
  deploy.command.new <- gsub(pattern="###runNumber###",replace=i,x=deploy.command.new)
  deploy.command.new <- gsub(pattern="###infrastructure###",replace=runs$infrastructure[i],x=deploy.command.new)
  urbansim.input <- ifelse(str_split(runs$scenario[i],'-')[[1]][1]=='a','/../urbansim/2025/baseline/','/../urbansim/2040/b-lt/')
  veh.type.id <- ifelse(str_split(runs$scenario[i],'-')[[1]][2]=='lowtech','ev-L1-10000-to-25000-LowTech-2045','ev-L1-10000-to-25000-HighTech-2030')
  afi.new <- gsub(pattern="###scenario-specific-urbansim-input###",replace=urbansim.input,x=afi.new)
  afi.new <- gsub(pattern="###scenario-specific-vehicle-type-id###",replace=veh.type.id,x=afi.new)
  new.filename <- pp(proj.dir,'production/sfbay/smart/',exper.name,'-',runs[i,.(pp(infrastructure,'-',range,'mi-',power,'kw-',scenario,'-BEV',bev,'.conf'))]$V1)
  writeLines(afi.new,con=new.filename)
  deploy.cmds <- c(deploy.cmds,deploy.command.new)
}
writeLines(deploy.cmds,con=pp(proj.dir,'run-',exper.name,'.sh'))
write.csv(runs,file=pp(proj.dir,'runs-',exper.name,'.csv'),row.names=F)
