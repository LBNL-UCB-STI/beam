import osmium
import pandas as pd
from collections import Counter, defaultdict
import json
import sys
from tqdm import tqdm


class OSMTagHandler(osmium.SimpleHandler):
    def __init__(self):
        osmium.SimpleHandler.__init__(self)
        self.tag_counters = defaultdict(Counter)
        self.other_tags_counters = defaultdict(Counter)
        self.records = []
        self.unique_tags = set()
        self.total_count = 0

    def process_other_tags(self, other_tags_str):
        """Parse hstore-formatted other_tags string into a dictionary"""
        if not other_tags_str:
            return {}

        parsed_tags = {}
        try:
            # Handle the format: "key"=>"value","key2"=>"value2",...
            current = ""
            in_quotes = False
            key = None
            parts = []

            # First split into key=>value parts
            for char in other_tags_str:
                if char == '"' and (not current or current[-1] != '\\'):
                    in_quotes = not in_quotes

                current += char

                if char == ',' and not in_quotes:
                    parts.append(current[:-1])  # Remove the trailing comma
                    current = ""

            if current:  # Add the last part if there is one
                parts.append(current)

            # Now process each part to extract key and value
            for part in parts:
                if "=>" in part:
                    key_val = part.split("=>")
                    if len(key_val) == 2:
                        k = key_val[0].strip().strip('"')
                        v = key_val[1].strip().strip('"')
                        parsed_tags[k] = v

                        # Update counter for this key-value pair
                        self.other_tags_counters[k][v] += 1
        except Exception as e:
            print(f"Error parsing other_tags: {e}, value: {other_tags_str[:100]}")

        return parsed_tags

    def way(self, w):
        """Process a way and its tags"""
        self.total_count += 1

        # Extract all tags into a dictionary
        tags_dict = {}
        other_tags_dict = {}

        for tag in w.tags:
            tag_key = tag.k
            tag_value = tag.v

            # Add to our unique tags set
            self.unique_tags.add(tag_key)

            # Store the tag and update its counter
            tags_dict[tag_key] = tag_value
            self.tag_counters[tag_key][tag_value] += 1

            # Check if this is an other_tags field that needs parsing
            if tag_key == 'other_tags':
                other_tags_dict = self.process_other_tags(tag_value)

        # Store the record with all its tags
        record = {
            'id': w.id,
            **tags_dict,
            'other_tags_parsed': other_tags_dict
        }

        self.records.append(record)

    # Also handle nodes and relations if needed
    def node(self, n):
        for tag in n.tags:
            self.unique_tags.add(tag.k)
            self.tag_counters[tag.k][tag.v] += 1

    def relation(self, r):
        for tag in r.tags:
            self.unique_tags.add(tag.k)
            self.tag_counters[tag.k][tag.v] += 1


def analyze_osm_pbf(file_path, limit=None):
    """
    Analyze an OSM PBF file and return statistics about all tags

    Args:
        file_path: Path to the OSM PBF file
        limit: Optional limit on the number of elements to process

    Returns:
        DataFrame of records and tag statistics
    """
    print(f"Analyzing OSM PBF file: {file_path}")
    handler = OSMTagHandler()

    # Process the file
    handler.apply_file(file_path)

    print(f"Processed {handler.total_count} ways, found {len(handler.unique_tags)} unique tag keys")

    # Create summary statistics
    tag_stats = {}
    for tag_key, counter in handler.tag_counters.items():
        total = sum(counter.values())
        tag_stats[tag_key] = {
            'count': total,
            'unique_values': len(counter),
            'top_values': dict(counter.most_common(10)),
            'percent_present': round(total / handler.total_count * 100, 2) if handler.total_count > 0 else 0
        }

    # Sort tag stats by frequency
    tag_stats = {k: v for k, v in sorted(
        tag_stats.items(),
        key=lambda item: item[1]['count'],
        reverse=True
    )}

    # Create similar statistics for other_tags fields
    other_tags_stats = {}
    for tag_key, counter in handler.other_tags_counters.items():
        total = sum(counter.values())
        other_tags_stats[tag_key] = {
            'count': total,
            'unique_values': len(counter),
            'top_values': dict(counter.most_common(10)),
            'percent_present': round(total / handler.total_count * 100, 2) if handler.total_count > 0 else 0
        }

    # Sort other_tags stats by frequency
    other_tags_stats = {k: v for k, v in sorted(
        other_tags_stats.items(),
        key=lambda item: item[1]['count'],
        reverse=True
    )}

    # Create a DataFrame from the records
    records_df = pd.DataFrame(handler.records) if handler.records else pd.DataFrame()

    # Return the summary statistics and records
    return {
        'total_count': handler.total_count,
        'unique_tags': list(handler.unique_tags),
        'tag_stats': tag_stats,
        'other_tags_stats': other_tags_stats
    }, records_df


def print_tag_stats(stats, category_name="Tags", limit=None):
    """Print tag statistics in a formatted way"""
    print(f"\n=== {category_name} Statistics ===")
    print(f"Total unique {category_name.lower()}: {len(stats)}")

    for i, (tag, data) in enumerate(stats.items()):
        if limit and i >= limit:
            print(f"\n... and {len(stats) - limit} more {category_name.lower()}.")
            break

        print(f"\n{i + 1}. {tag}: {data['count']} instances ({data['percent_present']}% of elements)")
        print(f"   Unique values: {data['unique_values']}")
        print("   Top values:")

        # Print top values with their counts
        for val, count in data['top_values'].items():
            # Truncate very long values
            display_val = val[:50] + "..." if len(val) > 50 else val
            print(f"     - {display_val}: {count}")


def main(file_path=None):
    """Main function to analyze an OSM PBF file"""
    if not file_path:
        print("\nNo file provided. To analyze a file, run: python osm_analyzer.py <file.osm.pbf>")
        return

    # Analyze the PBF file
    stats, records_df = analyze_osm_pbf(file_path)

    print(f"\n=== OSM PBF Analysis Summary ===")
    print(f"Total elements processed: {stats['total_count']}")
    print(f"Total unique tags found: {len(stats['unique_tags'])}")

    # Print regular tag statistics
    print_tag_stats(stats['tag_stats'], "Regular Tags", limit=20)

    # Print other_tags statistics
    print_tag_stats(stats['other_tags_stats'], "other_tags Keys", limit=20)

    # Show column names in the data
    if not records_df.empty:
        print("\n=== DataFrame Columns ===")
        columns = list(records_df.columns)
        for i, col in enumerate(columns):
            print(f"{i + 1}. {col}")

    return stats, records_df


if __name__ == "__main__":
    if len(sys.argv) < 2:
        main()
    else:
        main(sys.argv[1])