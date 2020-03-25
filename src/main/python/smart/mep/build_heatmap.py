import io
import random
import math
import time
import datetime
import mercantile

import numpy as np
import matplotlib.pyplot as plt
import matplotlib.colors as colors
import pandas as pd

from cairo import ImageSurface, FORMAT_ARGB32, Context
from urllib.request import urlopen, Request
from mpl_toolkits.axes_grid1 import make_axes_locatable


def readData(sourceFile, dataColumnName, cellSize, minX, minY, maxX, maxY):
    """
    Read heat map data from csv file.

    Function read three columns of data from provided csv file.
    Two columns for geografical coordinates (lon and lat) and one for data.
    Readed data are grouping by coordinates into cells of array.
    All data that does not fit into given min\max are ignored.
    Every cell of array will contain sum of data which was grouped into that cell
    or None if no data fall into cell.

    Parameters
    ----------
    sourceFile : string
        path to csv file

    dataColumnName : string
        column name which contains data

    cellSize : float
        size of cells for data grouping

    minX, maxX, minY, maxY : float
        coordinates of area of interest

    Returns
    -------
    numpy.array
        data which was read from csv file
    """

    coordX = "lon"  # longitude; x
    coordY = "lat"  # Latitude; y

    data = pd.read_csv(sourceFile, sep=",")

    tableSizeX = int(abs(maxX - minX) / cellSize)
    tableSizeY = int(abs(maxY - minY) / cellSize)

    def normX(x):
        nx = abs(x - minX)
        return int(nx / cellSize)

    def normY(y):
        ny = abs(y - minY)
        # because array indexes grows downwards but map coordinates grows upwards
        return int(tableSizeY - ny / cellSize)

    def writeToTable(table, value, x, y):
        if x < minX or x > maxX or y < minY or y > maxY:
            return

        nx = normX(x)
        ny = normY(y)
        if math.isnan(table[ny][nx]):
            table[ny][nx] = value
        else:
            table[ny][nx] += value

    table = np.zeros((tableSizeY, tableSizeX))
    table[:] = None

    for index, row in data.iterrows():
        writeToTable(table, row[dataColumnName], row[coordX], row[coordY])

    return table


def downloadOSMMap(west, south, east, north, zoom, mapType=None):
    """
    Download OSM map of selected area.

    Original was found on https://smyt.ru/blog/statc-osm-map-with-python/
    Map may be download from few different OSM tile servers with different zoom.
    Download speed depends on size of selected area.

    Parameters
    ----------
    weast,south,east,north : float
        coordinates of borders of map

    zoom : int
        map zoom, changes map detail

    mapType : {'white', 'dark', 'toner', None}, optional
        type of OSM tile server, default is None
        None - regular OSM map
        white, dark - white or dark cartodb OSM map
        toner - stamen toner OSM map

    Returns
    -------
    numpy.array
        background map of selected area
    """

    tiles = list(mercantile.tiles(west, south, east, north, zoom))

    min_x = min([t.x for t in tiles])
    min_y = min([t.y for t in tiles])
    max_x = max([t.x for t in tiles])
    max_y = max([t.y for t in tiles])

    tile_size = (256, 256)
    map_image = ImageSurface(
        FORMAT_ARGB32,
        tile_size[0] * (max_x - min_x + 1),
        tile_size[1] * (max_y - min_y + 1),
        )

    ctx = Context(map_image)

    def getTileUrl(mapType, zoom, x, y):
        if mapType == None:
            server = random.choice(["a", "b", "c"])
            return "http://{server}.tile.openstreetmap.org/{zoom}/{x}/{y}.png".format(
                server=server, zoom=zoom, x=x, y=y
            )

        if mapType == "white":
            return "http://cartodb-basemaps-1.global.ssl.fastly.net/light_all/{zoom}/{x}/{y}.png".format(
                zoom=zoom, x=x, y=y
            )

        if mapType == "dark":
            return "http://cartodb-basemaps-2.global.ssl.fastly.net/dark_all/{zoom}/{x}/{y}.png".format(
                zoom=zoom, x=x, y=y
            )

        if mapType == "toner":
            return "http://a.tile.stamen.com/toner/{zoom}/{x}/{y}.png".format(
                zoom=zoom, x=x, y=y
            )

    for t in tiles:
        headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 6.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/41.0.2228.0 Safari/537.3"
        }

        url = getTileUrl(mapType, t.z, t.x, t.y)
        req = Request(url, headers=headers)
        response = urlopen(req)

        img = ImageSurface.create_from_png(io.BytesIO(response.read()))

        ctx.set_source_surface(
            img, (t.x - min_x) * tile_size[0], (t.y - min_y) * tile_size[0]
        )
        ctx.paint()

    bounds = {
        "left": min([mercantile.xy_bounds(t).left for t in tiles]),
        "right": max([mercantile.xy_bounds(t).right for t in tiles]),
        "bottom": min([mercantile.xy_bounds(t).bottom for t in tiles]),
        "top": max([mercantile.xy_bounds(t).top for t in tiles]),
    }

    kx = map_image.get_width() / (bounds["right"] - bounds["left"])
    ky = map_image.get_height() / (bounds["top"] - bounds["bottom"])

    left_top = mercantile.xy(west, north)
    right_bottom = mercantile.xy(east, south)
    offset_left = (left_top[0] - bounds["left"]) * kx
    offset_top = (bounds["top"] - left_top[1]) * ky
    offset_right = (bounds["right"] - right_bottom[0]) * kx
    offset_bottom = (right_bottom[1] - bounds["bottom"]) * ky

    result_width = map_image.get_width() - int(offset_left + offset_right)
    result_height = map_image.get_height() - int(offset_top + offset_bottom)

    map_image_clipped = ImageSurface(FORMAT_ARGB32, result_width, result_height)

    ctx = Context(map_image_clipped)
    ctx.set_source_surface(map_image, -offset_left, -offset_top)
    ctx.paint()

    def surface_to_npim(surface):
        """ Transforms a Cairo surface into a numpy array. """
        im = +np.frombuffer(surface.get_data(), np.uint8)
        H, W = surface.get_height(), surface.get_width()
        im.shape = (H, W, 4)  # for RGBA
        return im[:, :, :3]

    return surface_to_npim(map_image_clipped)


def drawHeatMap(background, cellSize, colormap, sourceFile, dataColumnName, minX, maxX, minY, maxY, scaleMin, scaleMax):
    """
    Save to a file a heat map with given background and parameters for selected area.

    Parameters
    ----------
    background : numpy.array
        heat map background

    cellSize : float
        cell size to group data in cells

    colormap
        name of existing colormap.
        handmade colormap.

    sourceFile : string
        CSV data source file

    dataColumnName : string
        column name which contains data

    minX, maxX, minY, maxY : float
        coordinates of area of interest
    """

    pngImageDPI = 800

    data = readData(
        sourceFile, dataColumnName, cellSize, minX=minX, maxX=maxX, minY=minY, maxY=maxY
    )

    fig, ax = plt.subplots(dpi=pngImageDPI) # figsize=(12, 8),

    # drawing heat map
    im = ax.imshow(
        data,
        cmap=colormap,
        zorder=1,
        alpha=0.85,
        #interpolation="hermite",
        norm=colors.DivergingNorm(vmin = scaleMin, vmax = scaleMax, vcenter = 0.0), #
    )

    # specifying colorbar on the left
    divider = make_axes_locatable(ax)
    cax1 = divider.append_axes("right", size="5%", pad=0.1)
    fig.colorbar(im, cax=cax1)

    # drawing background
    ax.imshow(background, aspect=ax.get_aspect(), extent=ax.get_xlim() + ax.get_ylim(), zorder=0)

    # removing x and y scale text
    ax.set_xticks([])
    ax.set_yticks([])

    ax.set_title(sourceFile + "\n\n")
    fig.tight_layout()
    plt.savefig(sourceFile + ".scale:" + str(scaleMax) + ":" + str(scaleMin) + ".png", dpi=pngImageDPI)

    plt.close(fig)


print("was started at", datetime.datetime.now())
print()

start = time.time()

#
# selected bay area
#

topLeftPoint = (37.980285, -122.668632)  # (y,x)
bottomRightPoint = (37.279124,-121.748156)  # (y,x)

minX = topLeftPoint[1]
maxY = topLeftPoint[0]
maxX = bottomRightPoint[1]
minY = bottomRightPoint[0]

#
# downloading background map
#

backgroundMapZoom = 10
backgroundMapType="white" # None | white | black | toner

sfbaymap = downloadOSMMap(minX, minY, maxX, maxY, backgroundMapZoom, backgroundMapType)

#
# drawing heat map(s)
#

import os

dataFiles = []
for root, dirs, files in os.walk('.'):
    for file in files:
        if file.endswith('.csv'):
            dataFiles.append(file)
            print(file)

print('there are ', len(dataFiles))
print('')

# it is possible to generate colormap by hands
# available color maps https://matplotlib.org/3.1.1/tutorials/colors/colormaps.html
colorMap = "RdBu"

# size of square heat map cell
# for last used data the number of 93 fits best because of coordinate values distribution
cellSize = abs(minX - maxX) / 93

scaleMin = -120
scaleMax = 120

for sourceFile in dataFiles:
    drawHeatMap(sfbaymap, cellSize, colorMap, sourceFile, "mep", minX, maxX, minY, maxY, scaleMin, scaleMax)
    print(sourceFile, "finished")

end = time.time()
print()
print("was running for", math.floor(end - start), "sec")