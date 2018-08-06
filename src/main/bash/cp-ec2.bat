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
    echo.  -h host_name         host name where source files are available
    echo.  -s source_path       source file path
    echo.  -x file_extension    files with extension in source path
    echo.  -d destination_path  destination where to place file after copy, default to current directory
    echo.  -i identity_file     specify the identity file, default value ~/beam-box.pem
    goto :eof

:version
    if "%~1"=="full" call :header & goto :eof
    echo %__VERSION%
    goto :eof

:missing_argument
    call :header
    call :usage
    echo.
    if not defined host_name  echo host_name: not provided
    if not defined source_path  echo source_path: not provided
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

    set "identity_file=~/beam-box.pem"
    set "host_name="
    set "source_path="
    set "file_extension="
    set "destination_path=./"
    set "retain_path="

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

    if /i "%~1"=="-i"         set "identity_file=%~2"       & shift & shift & goto :parse
    if /i "%~1"=="-h"         set "host_name=%~2"           & shift & shift & goto :parse
    if /i "%~1"=="-s"         set "source_path=%~2"         & shift & shift & goto :parse
    if /i "%~1"=="-x"         set "file_extension=%~2"      & shift & shift & goto :parse
    if /i "%~1"=="-d"         set "destination_path=%~2"    & shift & shift & goto :parse
    if /i "%~1"=="-p"         set "retain_path=yes"         & shift & shift & goto :parse

    shift
    goto :parse

:validate
    if not defined host_name        call :missing_argument & goto :end
    if not defined source_path      call :missing_argument & goto :end
    where scp >nul 2>nul
    if %errorlevel%==1 (
        @echo scp not found in path. Please install OpenSSH [https://github.com/PowerShell/Win32-OpenSSH/wiki/Install-Win32-OpenSSH]
        goto :end
    )

:main
    if defined OptVerbose (
        echo **** DEBUG IS ON ****

         if defined identity_file           echo identity_file:      "%identity_file%"
         if defined host_name               echo host_name:          "%host_name%"
         if defined source_path             echo source_path:        "%source_path%"
         if defined file_extension          echo file_extension:     "%file_extension%"
         if defined destination_path        echo destination_path:   "%destination_path%"
    )

    if defined file_extension (
        set "tmp_dir=/home/ubuntu/tmp-cp-ec2/"
        ssh -i "%identity_file%" ubuntu@%host_name% mkdir /home/ubuntu/tmp-cp-ec2/

        if defined retain_path (
            ssh -i "%identity_file%" ubuntu@%host_name% "find %source_path% -name %file_extension% | cpio -pdm /home/ubuntu/tmp-cp-ec2/"
        ) else (
            ssh -i "%identity_file%" ubuntu@%host_name% "cp `find %source_path% -name %file_extension%` /home/ubuntu/tmp-cp-ec2/"
        )

        set "source=/home/ubuntu/tmp-cp-ec2/*"
    ) else (
        set "source=%source_path%"
    )

    scp -r -i "%identity_file%" ubuntu@%host_name%:"%source%" "%destination_path%"


    if defined file_extension ssh -i "%identity_file%" ubuntu@%host_name% rm -rf tmp-cp-ec2

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

    set "identity_file="
    set "host_name="
    set "source_path="
    set "destination_path="
    set "source="
    set "retain_path="

    goto :eof