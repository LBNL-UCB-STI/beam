#!/usr/bin/env python3
"""
Generate BEAM toll-prices.csv by intersecting geographic screenlines with a MATSim/R5 network.

This script makes toll pricing completely reproducible across different versions of the network,
independent of internal R5 edge indexing or network simplification changes.

Usage:
  python3 scripts/generate_tolls_from_screenlines.py \
      --network production/sfbay/r5/sfbay-cbg5500-weakConn-network/physsim-network.xml \
      --screenlines production/sfbay/toll-screenlines.csv \
      --crs EPSG:26910 \
      --output production/sfbay/toll-prices.csv
"""

import argparse
import csv
import gzip
import math
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import pyproj
from shapely.geometry import LineString
from shapely.strtree import STRtree
from shapely import wkt


def parse_args():
    parser = argparse.ArgumentParser(
        description="Intersect screenlines with MATSim network to generate BEAM toll-prices.csv"
    )
    parser.add_argument(
        "--network",
        required=True,
        help="Path to physsim-network.xml (or physsim-network.xml.gz)",
    )
    parser.add_argument(
        "--screenlines",
        required=True,
        help="Path to toll-screenlines.csv defining geographic cutlines",
    )
    parser.add_argument(
        "--crs",
        required=True,
        help="Local coordinate reference system of network nodes (e.g. EPSG:26910, EPSG:32048)",
    )
    parser.add_argument(
        "--output",
        required=True,
        help="Path to output toll-prices.csv",
    )
    return parser.parse_args()


def load_screenlines(csv_path, local_crs):
    """Load screenlines from CSV and project WGS84 geometries into local CRS."""
    trans = pyproj.Transformer.from_crs("EPSG:4326", local_crs, always_xy=True)
    screenlines = []

    with open(csv_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            geom_wgs = wkt.loads(row["wkt"].strip())
            coords_local = [trans.transform(lon, lat) for lon, lat in geom_wgs.coords]
            geom_local = LineString(coords_local)

            # Optional minFreespeed filter (in m/s, e.g. 15.0 for highway speeds)
            min_speed = float(row.get("minFreespeed", 0.0)) if row.get("minFreespeed") else 0.0

            # Direction can be single or comma-separated: "WB", "EB,NB", "BOTH", etc.
            dirs = [d.strip().upper() for d in row.get("direction", "BOTH").split(",")]

            screenlines.append({
                "facility_id": row.get("facilityId", "").strip(),
                "name": row.get("name", "").strip(),
                "toll": float(row["toll"]),
                "time_range": row.get("timeRange", "[:]").strip(),
                "directions": dirs,
                "min_freespeed": min_speed,
                "geom_local": geom_local,
            })

    return screenlines


def matches_direction(dx, dy, target_dirs):
    """
    Check if directed link vector (dx, dy) aligns with any of target_dirs.
    dx, dy are in local CRS (meters): positive dx is East, positive dy is North.
    """
    if any(d in ("BOTH", "ANY", "*", "ALL") for d in target_dirs):
        return True

    length = math.hypot(dx, dy)
    if length < 1e-6:
        return False

    for target in target_dirs:
        if target == "WB" and dx < 0 and abs(dx) >= 0.25 * abs(dy):
            return True
        elif target == "EB" and dx > 0 and abs(dx) >= 0.25 * abs(dy):
            return True
        elif target == "NB" and dy > 0 and abs(dy) >= 0.25 * abs(dx):
            return True
        elif target == "SB" and dy < 0 and abs(dy) >= 0.25 * abs(dx):
            return True

    return False


def intersect_network(network_path, screenlines):
    """
    Stream physsim-network.xml, extract nodes and test car links against screenlines.
    Returns list of (link_id, toll, time_range, facility_name).
    """
    print(f"Reading network from: {network_path}")
    nodes = {}
    matched_entries = []

    sl_geoms = [sl["geom_local"] for sl in screenlines]
    sl_tree = STRtree(sl_geoms)

    node_count = 0
    link_count = 0
    car_link_count = 0

    open_fn = gzip.open if str(network_path).endswith(".gz") else open

    with open_fn(network_path, "rb") as f:
        for event, elem in ET.iterparse(f, events=("end",)):
            tag = elem.tag

            if tag == "node":
                nid = elem.attrib["id"]
                x = float(elem.attrib["x"])
                y = float(elem.attrib["y"])
                nodes[nid] = (x, y)
                node_count += 1
                elem.clear()

            elif tag == "link":
                link_count += 1
                modes = elem.attrib.get("modes", "")

                if "car" in modes:
                    car_link_count += 1
                    lid = int(elem.attrib["id"])
                    fn = elem.attrib["from"]
                    tn = elem.attrib["to"]
                    freespeed = float(elem.attrib.get("freespeed", 0.0))

                    if fn in nodes and tn in nodes:
                        fx, fy = nodes[fn]
                        tx, ty = nodes[tn]
                        dx = tx - fx
                        dy = ty - fy

                        link_geom = LineString([(fx, fy), (tx, ty)])

                        candidates = sl_tree.query(link_geom)
                        for candidate_idx in candidates:
                            sl = screenlines[candidate_idx]
                            if freespeed >= sl["min_freespeed"] and link_geom.intersects(sl["geom_local"]):
                                if matches_direction(dx, dy, sl["directions"]):
                                    matched_entries.append((
                                        lid,
                                        sl["toll"],
                                        sl["time_range"],
                                        sl["name"]
                                    ))

                elem.clear()

            elif tag == "nodes":
                print(f"  Parsed {node_count:,} nodes.")

    print(f"  Parsed {link_count:,} total links ({car_link_count:,} car links).")
    return matched_entries


def write_toll_prices(matched_entries, output_path):
    """Write standard BEAM toll-prices.csv."""
    # Deduplicate and sort by (link_id, time_range)
    unique_entries = list(set(matched_entries))
    sorted_entries = sorted(unique_entries, key=lambda x: (x[0], x[2]))

    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with open(output_path, "w", encoding="utf-8") as f:
        f.write("linkId,toll,timeRange\n")
        for lid, toll, time_range, name in sorted_entries:
            f.write(f"{lid},{toll:.2f},{time_range}\n")

    print(f"\nWrote {len(sorted_entries)} toll rules to: {output_path}")


def main():
    args = parse_args()
    screenlines = load_screenlines(args.screenlines, args.crs)
    print(f"Loaded {len(screenlines)} screenline definitions from: {args.screenlines}")
    for sl in screenlines:
        print(f"  - {sl['name']} | ${sl['toll']:.2f} | dirs: {sl['directions']} | minSpeed: {sl['min_freespeed']} m/s | {sl['time_range']}")

    matched = intersect_network(args.network, screenlines)
    print(f"\nFound {len(matched)} matching directed link intersections:")
    for lid, toll, time_range, name in sorted(list(set(matched)), key=lambda x: x[0]):
        print(f"  * Link {lid} -> {name} (${toll:.2f}, {time_range})")

    write_toll_prices(matched, args.output)


if __name__ == "__main__":
    main()
