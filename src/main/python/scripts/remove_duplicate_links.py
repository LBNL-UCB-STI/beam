import argparse
import shapefile

parser = argparse.ArgumentParser()

parser.add_argument("-i", "--inputshapefile", help="Path to input shapefile", required=True)
parser.add_argument("-o", "--outputshapefile", help="Path to output shapefile", required=True)
parser.add_argument("-c", "--capacity", help="Default capacity value (cars/hr/lane) for zero values in the input shapefile", type=float, required=False, default=1500.0)

args=parser.parse_args()

reader = shapefile.Reader(args.inputshapefile)
writer = shapefile.Writer(args.outputshapefile)

# START
# accumulate adjacent links max_speed
inbound_links = dict()
outbound_links = dict()

def adjacent_links(links_dict, node_id, link_speed):
    if node_id not in links_dict:
        links_dict[node_id] = set([link_speed])
    else:
        s = links_dict[node_id]
        s.add(link_speed)

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

shapeRecords = reader.shapeRecords()
# to keep non-zero capacity links at the top for processing
shapeRecords.sort(key=lambda x: x.record['DATA1'], reverse=True)
s = set()

for n in shapeRecords:
    record = n.record
    shape = n.shape
    if record['ID'] not in s:
        s.add(f"{record['JNODE']}-{record['INODE']}")

        if record['DATA1'] == 0:
            record['DATA1'] = args.capacity

        if record['DATA2'] == 0:
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
                record['DATA2'] = sum(links) / len(links)

        writer.record(
            record['ID'],
            record['MODES'],
            record['LANES'],
            record['DATA1'],
            str(record['DATA2']) + " mph",
        )
        writer.shape(shape)

writer.close()
