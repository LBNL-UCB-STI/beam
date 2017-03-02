my.approx <- function (x, y = NULL, xout, method = "linear", n = 50, yleft, yright, rule = 1, f = 0, ties = mean) 
{
    method <- pmatch(method, c("linear", "constant"))
    if (is.na(method)) 
        stop("invalid interpolation method")
    stopifnot(is.numeric(rule), (lenR <- length(rule)) >= 1L, 
        lenR <= 2L)
    if (lenR == 1) 
        rule <- rule[c(1, 1)]
    x <- stats:::regularize.values(x, y, ties)
    y <- x$y
    x <- x$x
    nx <- as.integer(length(x))
    if (is.na(nx)) 
        stop("invalid length(x)")
    if (nx <= 1) {
        if (method == 1) 
            stop(pp("need at least two non-NA values to interpolate, x=",pp(x,collapse=','),' y=',pp(y,collapse=',')))
        if (nx == 0) 
            stop(pp("zero non-NA points x=",pp(x,collapse=','),' y=',pp(y,collapse=',')))
    }
    if (missing(yleft)) 
        yleft <- if (rule[1L] == 1) 
            NA
        else y[1L]
    if (missing(yright)) 
        yright <- if (rule[2L] == 1) 
            NA
        else y[length(y)]
    stopifnot(length(yleft) == 1L, length(yright) == 1L, length(f) == 
        1L)
    if (missing(xout)) {
        if (n <= 0) 
            stop("'approx' requires n >= 1")
        xout <- seq.int(x[1L], x[nx], length.out = n)
    }
    x <- as.double(x)
    y <- as.double(y)
    .Call(stats:::C_ApproxTest, x, y, method, f)
    yout <- .Call(stats:::C_Approx, x, y, xout, method, yleft, yright, 
        f)
    list(x = xout, y = yout)
}
