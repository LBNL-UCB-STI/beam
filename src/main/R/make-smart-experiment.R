library('yaml')

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

proj.dir <- '~/Dropbox/ucb/vto/beam-all/beam/'
exp.file <- pp(proj.dir,'src/main/R/make-smart-experiment.yaml')
afi.template <- pp(proj.dir,'production/sfbay/smart/afi-template.conf')
afi.raw <- readLines(afi.template)

runs <- load.experiment(exp.file)$runs
exper.name <- load.experiment(exp.file)$yaml$Name

for(i in 1:nrow(runs)){
  afi.new <- gsub(pattern="###scenario###",replace=runs$scenario[i],x=afi.raw)
  afi.new <- gsub(pattern="###power###",replace=runs$power[i],x=afi.new)
  afi.new <- gsub(pattern="###range###",replace=runs$range[i],x=afi.new)
  afi.new <- gsub(pattern="###infrastructure###",replace=runs$infrastructure[i],x=afi.new)
  afi.new <- gsub(pattern="###infrastructure###",replace=runs$infrastructure[i],x=afi.new)
  urbansim.input <- ifelse(str_split(runs$scenario[i],'-')[[1]][1]=='a','/../urbansim/2025/baseline/','/../urbansim/2040/b-lt/')
  veh.type.id <- ifelse(str_split(runs$scenario[i],'-')[[1]][2]=='lowtech','conv-L1-10000-to-25000-LowTech-2045','conv-L1-10000-to-25000-HighTech-2030')
  afi.new <- gsub(pattern="###scenario-specific-urbansim-input###",replace=urbansim.input,x=afi.new)
  afi.new <- gsub(pattern="###scenario-specific-vehicle-type-id###",replace=veh.type.id,x=afi.new)
  new.filename <- pp(proj.dir,'production/sfbay/smart/',exper.name,'-',runs[i,.(pp(infrastructure,'-',range,'mi-',power,'kw-',scenario,'.conf'))]$V1)
  writeLines(afi.new,con=new.filename)
}
