#!/usr/local/bin/Rscript
##############################################################################################################################################
# Script to convert XML files to an R data table and then save as Rdata 
##############################################################################################################################################

##############################################################################################################################################
# LOAD LIBRARIES NEED BY THIS SCRIPT
library(colinmisc)
load.libraries(c('optparse','XML','stringr'),quietly=T)

##############################################################################################################################################
# COMMAND LINE OPTIONS 
option_list <- list(
)
if(interactive()){
  #setwd('~/downs/')
  args<-'test/output/sf-bay_2017-09-19_13-46-01/ITERS/it.0/0.events.xml'
  args <- parse_args(OptionParser(option_list = option_list,usage = "xml2Rdata.R [file-to-convert]"),positional_arguments=T,args=args)
}else{
  args <- parse_args(OptionParser(option_list = option_list,usage = "xml2Rdata.R [file-to-convert]"),positional_arguments=T)
}

######################################################################################################
file.path <- args$args[1]
for(file.path in args$args){
  path <- str_split(file.path,"/")[[1]]
  if(length(path)>1){
    the.file <- tail(path,1)
    the.dir <- pp(pp(head(path,-1),collapse='/'),'/')
  }else{
    the.file <- path
    the.dir <- './'
  }
  the.file.rdata <- pp(head(str_split(the.file,'xml')[[1]],-1),'Rdata')

  dat <- xmlParse(file.path)
  root.name <- xmlName(xmlRoot(dat))

  attrs <- c()
  for(i in c(1:min(xmlSize(xmlRoot(dat)),1000000))){
    attrs <- u(c(attrs,names(xmlAttrs(xmlRoot(dat)[[i]]))))
  }
  df <- data.frame(xml.node=names(xmlRoot(dat)))
  for(attr in attrs){
    numval <- suppressWarnings(as.numeric(attrs[attr]))
    if(is.na(numval)){
      streval(pp('df$',attr,' <- xpathSApply(dat,"/',root.name,'/',xmlName(xmlRoot(dat)[[1]]),'", xmlGetAttr,"',attr,'",default=NA)'))
    }else{
      streval(pp('df$',attr,' <- as.numeric(xpathSApply(dat,"/',root.name,'/',xmlName(xmlRoot(dat)[[1]]),'", xmlGetAttr,"',attr,'",default=NA))'))
    }
  }
  save(df,file=pp(the.dir,the.file.rdata))
}
