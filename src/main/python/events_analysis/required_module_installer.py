import subprocess
import sys

# pip and npm still required
def install(package, packageIndexOption = ""):
    install_cmd = [sys.executable, "-m", "pip", "install", package]
    if packageIndexOption != "":
        install_cmd.append(packageIndexOption)
    subprocess.call(install_cmd)

def npm_install(package, package2):
    subprocess.call(["npm", "install", "-g", package, package2], shell=True)

def installAll():
    install("pandas")
#    install("plotly")
    install("numpy")
    install("matplotlib")
    install("collections")
    npm_install("electron@1.8.4", "orca")
    install("psutil","requests")

if __name__ == '__main__':
    installAll()