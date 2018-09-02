
load.scenarios <- function(){
  scenarios <- data.table(read.csv('/Users/critter/odrive/GoogleDriveUCB/beam-collaborators/planning/vgi/Scaling_factors_for_forecast_and_EV_counts_July24_2017.csv'))
  scenarios <- melt(scenarios[,list(Electric.Utility,Vehicle_category,
                                    Low_2025_energy_propCED15_2080,Low_2025_energy_propCED15_5050,Low_2025_energy_propCED15_8020,Low_2025_energy_propCED15_4060,Low_2025_energy_propCED15_6040,
                                    Mid_2025_energy_propCED15_2080,Mid_2025_energy_propCED15_5050,Mid_2025_energy_propCED15_8020,Mid_2025_energy_propCED15_4060,Mid_2025_energy_propCED15_6040,
                                    High_2025_energy_propCED15_2080,High_2025_energy_propCED15_5050,High_2025_energy_propCED15_8020,High_2025_energy_propCED15_4060,High_2025_energy_propCED15_6040
                                    )],id.vars=c('Electric.Utility','Vehicle_category'))
  scenarios[,penetration:=unlist(lapply(str_split(variable,"_"),function(ll){ ll[1] }))]
  scenarios[,veh.type.split:=unlist(lapply(str_split(variable,"_"),function(ll){ ifelse(length(ll)==4,ll[4],ll[5]) }))]
  scenarios[,':='(veh.type=Vehicle_category,Vehicle_category=NULL)]
  scenarios
}  
  gap.analysis <- function(the.df){
    setkey(the.df,hr,final.type,veh.type,constraint)
    gap <- the.df[,list(max.en=cumul.energy[1],min.en=cumul.energy[2],gap=cumul.energy[1]-cumul.energy[2],plugged.in.capacity=plugged.in.capacity[1]),by=c('hr','final.type','veh.type')]
    gap[gap<0,min.en:=max.en]
    gap[gap<0,gap:=0]
    gap[,hr.cal:=(hr-1)%%24+1]
    gap[,max.norm:=max.en-min(max.en),by=c('final.type','veh.type')]
    #gap[hr.cal<=4 & hr>32,max.norm:=max.norm - max(max.norm),by=c('final.type','veh.type')]
    gap <- gap[hr>=30]
    setkey(gap,final.type,veh.type,hr)
    gap[hr>32 & hr.cal%in%4:5]
    max.start <- gap[hr>32 & hr.cal%in%4:5,list(maxstart=diff(max.en)),by=c('final.type','veh.type')]
    gap <- join.on(gap,max.start,c('final.type','veh.type'))
    gap[,max.norm:=max.norm-min(max.norm)+maxstart,by=c('final.type','veh.type')]
    gap[,min.norm:=max.norm-gap]
    min.start <- gap[hr>32 & hr.cal%in%4:5,list(minstart=diff(min.norm)),by=c('final.type','veh.type')]
    gap <- join.on(gap,min.start,c('final.type','veh.type'))
    gap[,min.norm.relative.to.min:=min.norm - min(min.norm)+minstart,by=c('final.type','veh.type')]
    gap
  }
  # the.gap <- copy(gap.weekday)
  # the.day <- the.wday
  scale.the.gap <- function(the.gap,cp.day.norm,the.day){
    day.name <- names(wdays[wdays==the.day])
    the.gap <- join.on(the.gap,cp.day.norm[wday==day.name],'final.type','type','norm.load')
    the.gap[,max.norm:=max.norm*norm.load]
    the.gap[,min.norm:=max.norm-gap*norm.load]
    the.gap[,plugged.in.capacity:=plugged.in.capacity*norm.load]
    min.start <- the.gap[hr>32 & hr.cal%in%4:5,list(minstart=diff(min.norm)),by=c('final.type','veh.type')]
    the.gap <- join.on(the.gap,min.start,c('final.type','veh.type'))
    the.gap[,min.norm.relative.to.min:=min.norm - min(min.norm)+minstart,by=c('final.type','veh.type')]
    the.gap[hr<54] 
  }

repeat_last = function(x, forward = TRUE, maxgap = Inf, na.rm = FALSE) {
    if (!forward) x = rev(x)           # reverse x twice if carrying backward
    ind = which(!is.na(x))             # get positions of nonmissing values
    if (is.na(x[1]) && !na.rm)         # if it begins with NA
        ind = c(1,ind)                 # add first pos
    rep_times = diff(                  # diffing the indices + length yields how often
        c(ind, length(x) + 1) )          # they need to be repeated
    if (maxgap < Inf) {
        exceed = rep_times - 1 > maxgap  # exceeding maxgap
        if (any(exceed)) {               # any exceed?
            ind = sort(c(ind[exceed] + 1, ind))      # add NA in gaps
            rep_times = diff(c(ind, length(x) + 1) ) # diff again
        }
    }
    x = rep(x[ind], times = rep_times) # repeat the values at these indices
    if (!forward) x = rev(x)           # second reversion
    x
}
read.data.table.with.filter <- function(filepath,match.words,header.word=NA){
  if(!is.na(header.word))match.words <- c(match.words,header.word)
  match.string <- pp("'",pp(match.words,collapse="\\|"),"'")
  return(data.table(my.read.csv.sql(filepath,filter=pp("grep ",match.string))))
}
my.read.csv.sql <- function(file, sql = "select * from file", header = TRUE, sep = ",", 
    row.names, eol, skip, filter, nrows, field.types, colClasses, 
    dbname = tempfile(), drv = "SQLite", ...){
      file.format <- list(header = header, sep = sep)
    if (!missing(eol)) 
        file.format <- append(file.format, list(eol = eol))
    if (!missing(row.names)) 
        file.format <- append(file.format, list(row.names = row.names))
    if (!missing(skip)) 
        file.format <- append(file.format, list(skip = skip))
    if (!missing(filter)) 
        file.format <- append(file.format, list(filter = filter))
    if (!missing(nrows)) 
        file.format <- append(file.format, list(nrows = nrows))
    if (!missing(field.types)) 
        file.format <- append(file.format, list(field.types = field.types))
    if (!missing(colClasses)) 
        file.format <- append(file.format, list(colClasses = colClasses))
    pf <- parent.frame()
    if (missing(file) || is.null(file) || is.na(file)) 
        file <- ""
    tf <- NULL
    if (substring(file, 1, 7) == "http://" || substring(file, 
        1, 6) == "ftp://") {
        tf <- tempfile()
        on.exit(unlink(tf), add = TRUE)
        download.file(file, tf, mode = "wb")
        file <- tf
    }
    if(class(file)[1]=="character"){
      p <- proto(pf, file = file(file))
      p <- do.call(proto, list(pf, file = file(file)))
    }else{
      p <- proto(pf, file = file)
      p <- do.call(proto, list(pf, file = file))
    }
    sqldf(sql, envir = p, file.format = file.format, dbname = dbname, 
        drv = drv, ...)
}
xy.to.latlon <- function(str,print=T){
  if(length(grep("\\[",str))>0){
    tmp <- strsplit(strsplit(str,'\\[x=')[[1]][2],'\\]\\[y=')[[1]]
    x <- as.numeric(tmp[1])
    y <- as.numeric(strsplit(tmp[2],'\\]')[[1]][1])
  }else if(length(grep('"',str))>0){
    x <- as.numeric(strsplit(str,'"')[[1]][2])
    y <- as.numeric(strsplit(str,'"')[[1]][4])
  }else if(length(grep(',',str))>0){
    x <- as.numeric(strsplit(str,',')[[1]][1])
    y <- as.numeric(strsplit(str,',')[[1]][2])
  }else if(length(grep(' ',str))>0){
    x <- as.numeric(strsplit(str,' ')[[1]][1])
    y <- as.numeric(strsplit(str,' ')[[1]][2])
  }else{
    return('Parse Error')
  }
  xy <- data.frame(x=x,y=y)
  xy <- SpatialPoints(xy,proj4string=CRS("+init=epsg:26910"))
  xy <- data.frame(coordinates(spTransform(xy,CRS("+init=epsg:4326"))))
  if(print){
    my.cat(pp(xy$y,',',xy$x))
  }else{
    return(pp(xy$y,',',xy$x))
  }
}
