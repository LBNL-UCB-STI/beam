import csv
import sys
import os
import matplotlib.pyplot as plt

def inter(s):
    return s.format(**sys._getframe(1).f_locals)

def generateGraph(path, quad_output_file, repositioning_file):

    max_draw_fig_count = 100
    output_path = path+'/tmp/'
    os.mkdir(output_path)
    plt.figure(figsize=(20,12))
    plt.ylim(800,3000)
    plt.xlim(166000,170500)

    quad_data = {}
    quad_csv = open(path +"/"+ quad_output_file)
    quad_csv_reader = csv.reader(quad_csv, delimiter=',')
    firstline = True
    for row in quad_csv_reader:
        if firstline:    #skip first line
            firstline = False
            continue
        existing_values = quad_data.get(row[0], [])
        existing_values.append((float(row[1]),float(row[2]),row[3]))
        quad_data[row[0]] = existing_values

    coord_output = {}
    movement_csv = open(path +"/"+ repositioning_file)
    movement_csv_reader = csv.reader(movement_csv, delimiter=',')
    firstline = True

    for row in movement_csv_reader:
        if firstline:    #skip first line
            firstline = False
            continue
        existing_values = coord_output.get(row[0], [])
        existing_values.append((float(row[1]),float(row[2]),float(row[3]),float(row[4])))
        coord_output[row[0]] = existing_values

    fig_drawn = 0
    for count,item in enumerate(coord_output.items()):
        time = item[0]
        points = item[1]
        fig_drawn = count
        if fig_drawn >= max_draw_fig_count:
            break
        x1 = []
        y1 = []
        x2 = []
        y2 = []
        x3 = []
        y3 = []
        for point in quad_data.pop(time,None):
            x1.append(point[0])
            y1.append(point[1])
            #plt.text(float(row[1]), float(row[2]), row[3])

        plt.plot(x1,y1, 'ro')

        for point in points:
            x2.append(point[0])
            y2.append(point[1])
            x3.append(point[2])
            y3.append(point[3])
            plt.arrow(point[0],point[1], point[2]-point[0], point[3]-point[1], fc='yellow', ec='black',head_width=20, head_length=50, length_includes_head = True)

        plt.plot(x2,y2, 'go')
        plt.plot(x3,y3, 'bo')
        plt.title("Time "+time)
        plt.savefig(output_path+inter('file_{time}.png'))
        plt.cla()

    for count,item in enumerate(quad_data.items(), fig_drawn):
        if count >= max_draw_fig_count:
            break
        time = item[0]
        points = item[1]
        x1 = []
        y1 = []
        for point in points:
            x1.append(point[0])
            y1.append(point[1])

        plt.plot(x1,y1, 'ro')

        plt.title("Time "+time)
        plt.savefig(output_path+inter('file_{time}.png'))
        plt.cla()


generateGraph(sys.argv[1],sys.argv[2],sys.argv[3])