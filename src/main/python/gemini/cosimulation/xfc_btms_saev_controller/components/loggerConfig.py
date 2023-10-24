# this file shall do the basic logging configuration.
import logging
import sys

def loggerConfig():
    # create Handlers
    fileHandler = logging.FileHandler("debug.log")
    streamHandler = logging.StreamHandler(sys.stdout)
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s : %(name)s  : %(funcName)s : %(levelname)s : %(message)s',
        handlers=[
            fileHandler,
            streamHandler
        ],
    )
    logging.info("Logger configured")

    return streamHandler
