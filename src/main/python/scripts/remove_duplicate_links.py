import sys
import shapefile

if len(sys.argv) != 3:
    print("python remove_duplicate_links.py <input shapefile> <output location>")
    exit()

reader = shapefile.Reader(sys.argv[1])
writer = shapefile.Writer(sys.argv[2])

# START
# accumulate adjacent links max_speed
inbound_links = dict()
outbound_links = dict()

def adjacent_links(d, key, value):
    if key not in d:
        d[key] = set([value])
    else:
        s = d[key]
        s.add(value)

for record in reader.iterRecords():
    [from_node, to_node] = record['ID'].split('-')
    max_speed = record['DATA2']
    if max_speed > 0:
        adjacent_links(outbound_links, from_node, max_speed)
        adjacent_links(inbound_links, to_node, max_speed)

print(f"inbound_links size: {len(inbound_links)}")
print(f"outbound_links size: {len(outbound_links)}")
# END

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
        if record['DATA2'] == 0:
            #print(f"Calculating average... [{record['ID']}]")
            from_node = str(record['INODE'])
            has_from_node = from_node in inbound_links
            ilinks = set()
            if has_from_node:
                ilinks = inbound_links[from_node]

            to_node = str(record['JNODE'])
            has_to_node = to_node in outbound_links
            olinks = set()
            if has_to_node:
                olinks = outbound_links[to_node]

            if has_from_node or has_to_node:
                links = ilinks.union(olinks)
                #print(f"BEFORE: {record['DATA2']}")
                #print(f"link speed: {links}")
                record['DATA2'] = sum(links) / len(links)
                #print(f" AFTER: {record['DATA2']}")

        writer.record(
            record['ID'],
            record['MODES'],
            record['LANES'],
            record['DATA1'],
            str(record['DATA2']) + " mph",
        )
        writer.shape(shape)

writer.close()
