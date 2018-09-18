@::!/dos/rocks
@echo off
goto :init

:header
    echo %__NAME% v%__VERSION%
    echo This is a utility script to copy files from ec2.
    echo.
    goto :eof

:usage
    echo USAGE:
    echo   %__BAT_NAME% [flags] "required argument" "optional argument"
    echo.
    echo.  /?, --help           shows this help
    echo.  /v, --version        shows the version
    echo.  /e, --verbose        shows detailed output
    echo.
    echo.  -h host_csv          host csv, with dns and identity file
    echo.  -p search_path       search file path
    echo.  -s search_word       search word
    echo.
    echo Example:
    echo   %__BAT_NAME% -h dns.csv -s iteration
    goto :eof

:version
    if "%~1"=="full" call :header & goto :eof
    echo %__VERSION%
    goto :eof

:missing_argument
    call :header
    call :usage
    echo.
    if not defined host_csv  echo host_csv: not provided
    if not defined search_path  echo search_path: not provided
    if not defined search_word  echo search_word: not provided
    echo.
    goto :eof

:init
    set "__NAME=%~n0"
    set "__VERSION=0.1"
    set "__YEAR=2018"

    set "__BAT_FILE=%~0"
    set "__BAT_PATH=%~dp0"
    set "__BAT_NAME=%~nx0"

    set "OptHelp="
    set "OptVersion="
    set "OptVerbose="

    set "host_csv="
    set "search_path=/var/log/cloud-init-output.log"
    set "search_word="

:parse
    if "%~1"=="" goto :validate

    if /i "%~1"=="/?"         call :header & call :usage "%~2" & goto :end
    if /i "%~1"=="-?"         call :header & call :usage "%~2" & goto :end
    if /i "%~1"=="--help"     call :header & call :usage "%~2" & goto :end

    if /i "%~1"=="/v"         call :version         & goto :end
    if /i "%~1"=="-v"         call :version         & goto :end
    if /i "%~1"=="--version"  call :version full    & goto :end

    if /i "%~1"=="/e"         set "OptVerbose=yes"  & shift & goto :parse
    if /i "%~1"=="-e"         set "OptVerbose=yes"  & shift & goto :parse
    if /i "%~1"=="--verbose"  set "OptVerbose=yes"  & shift & goto :parse

    if /i "%~1"=="-h"         set "host_csv=%~2"           & shift & shift & goto :parse
    if /i "%~1"=="-p"         set "search_path=%~2"         & shift & shift & goto :parse
    if /i "%~1"=="-s"         set "search_word=%~2"         & shift & shift & goto :parse

    shift
    goto :parse

:validate
    if not defined host_csv        call :missing_argument & goto :end
    if not defined search_path      call :missing_argument & goto :end
    where scp >nul 2>nul
    if %errorlevel%==1 (
        @echo scp not found in path. Please install OpenSSH [https://github.com/PowerShell/Win32-OpenSSH/wiki/Install-Win32-OpenSSH]
        goto :end
    )

:main
    if defined OptVerbose (
        echo **** DEBUG IS ON ****

         if defined host_csv               echo host_csv:          "%host_csv%"
         if defined search_path             echo search_path:        "%search_path%"
         if defined search_word             echo search_word:        "%search_word%"
    )

    for /f "tokens=1,2 delims=, " %%a in (%host_csv%) do (
        echo Results from host: %%a
        ssh -i "%%b" ubuntu@%%a grep %search_word% %search_path%
    )

:end
    call :cleanup
    exit /B

:cleanup
    REM The cleanup function is only really necessary if you
    REM are _not_ using SETLOCAL.
    set "__NAME="
    set "__VERSION="
    set "__YEAR="

    set "__BAT_FILE="
    set "__BAT_PATH="
    set "__BAT_NAME="

    set "OptHelp="
    set "OptVersion="
    set "OptVerbose="

    set "host_csv="
    set "search_path="
    set "search_word="

    goto :eof