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
    echo.  -target_cert target_cert_path
    echo.  -target_host target_host_dns
    echo.  -source_folder source_folder_to_copy
    echo.
    echo Example:
    echo   %__BAT_NAME% -h dns.csv -target_cert [PATH_TO_CERT_FILE] -target_host [TARGET_HOST_DNS] -source_folder [SOURCE_FOLDER_TO_COPY]
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
    if not defined target_cert_path  echo target_cert_path: not provided
    if not defined target_host_dns  echo target_host_dns: not provided
    if not defined source_folder_to_copy echo source_folder_to_copy: not provided
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

    set "target_cert_path="
    set "target_host_dns="
    set "source_folder_to_copy="

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

    if /i "%~1"=="-target_cert"         set "target_cert_path=%~2"         & shift & shift & goto :parse
    if /i "%~1"=="-target_host"         set "target_host_dns=%~2"         & shift & shift & goto :parse
    if /i "%~1"=="-source_folder"         set "source_folder_to_copy=%~2"         & shift & shift & goto :parse

    shift
    goto :parse

:validate
    if not defined host_csv        call :missing_argument & goto :end
    if not defined target_cert_path      call :missing_argument & goto :end
    if not defined target_host_dns     call :missing_argument & goto :end
    if not defined source_folder_to_copy     call :missing_argument & goto :end
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
         if defined target_cert_path        echo target_cert_path:   "%target_cert_path%"
         if defined target_host_dns         echo target_host_dns:   "%target_host_dns%"
         if defined source_folder         echo source_folder_to_copy:   "%source_folder_to_copy%"
    )

    for /f "tokens=1,2 delims=, " %%a in (%host_csv%) do (
        echo Results from host: %%a
        scp -i "%%b" -r "%target_cert_path%" ubuntu@%%a:~/.ssh/result_host_cert.pem
        scp -i "%%b" -r copyAllFilesFromRunningExperiment.sh ubuntu@%%a:~/copyAllFilesFromRunningExperiment.sh
        ssh -i "%%b" ubuntu@%%a chmod +x copyAllFilesFromRunningExperiment.sh
        ssh -i "%%b" ubuntu@%%a "~/copyAllFilesFromRunningExperiment.sh %source_folder_to_copy% %target_host_dns% &"

        REM ssh -i "%%b" ubuntu@%%a chmod 600 ~/.ssh/result_host_cert.pem
        REM ssh -i "%%b" ubuntu@%%a scp -i ~/.ssh/result_host_cert.pem -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -r "%source_folder_to_copy%" ubuntu@%target_host_dns%:~/sigoptResults/
        REM ssh -i "%target_cert_path%" ubuntu@%target_host_dns% ls sigoptResults
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

    set "target_cert_path="
    set "target_host_dns="
    set "source_folder_to_copy="
    goto :eof