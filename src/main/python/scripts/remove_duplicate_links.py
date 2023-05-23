import sys
import shapefile

if len(sys.argv) != 3:
    print("python remove_duplicate_links.py <input shapefile> <output location>")
    exit()

reader = shapefile.Reader(sys.argv[1])

writer = shapefile.Writer(sys.argv[2])

writer.field('ID', 'C', 20, 0)
writer.field('MODES', 'C', 64, 0)
writer.field('LANES', 'N', 35, 7)
writer.field('DATA1', 'N', 35, 7) # hourly capacity per lane
writer.field('DATA2', 'C', 35, 7) # add mph

s = set()
for n in reader.iterShapeRecords():
    record = n.record
    shape = n.shape
    if record['ID'] not in s:
        s.add(f"{record['JNODE']}-{record['INODE']}")
        writer.record(
            record['ID'],
            record['MODES'],
            record['LANES'],
            record['DATA1'],
            str(record['DATA2']) + " mph",
        )
        writer.shape(shape)

writer.close()
