import os
import sys
import re
from pathlib import Path
from collections import Counter


def filter_log_file(input_file, output_file):
    """
    Filter out repetitive IntHashGrid error messages and freight carrier messages from log file.

    Args:
        input_file (str): Path to input log file
        output_file (str): Path to output filtered log file
    """
    try:
        # Patterns to match the different error messages
        spatial_pattern = re.compile(
            r'.*ERROR com\.conveyal\.r5\.streets\.IntHashGrid - Visiting too many spatial index cells\.')
        freight_pattern = re.compile(
            r'.*ERROR b\.a\.a\.f\.input\.GenericFreightReader - Following freight carrier row discarded because tour ([\w-]+) was filtered out: \{.*\}')

        # Counters for different types of filtered lines
        spatial_count = 0
        freight_count = 0
        total_count = 0

        # Counter for specific tours that were filtered out
        filtered_tours = Counter()

        with open(input_file, 'r', encoding='utf-8') as infile, \
                open(output_file, 'w', encoding='utf-8') as outfile:

            for line in infile:
                total_count += 1

                # Check for spatial index error
                if spatial_pattern.match(line):
                    spatial_count += 1
                    continue

                # Check for freight carrier error
                freight_match = freight_pattern.match(line)
                if freight_match:
                    freight_count += 1
                    tour_id = freight_match.group(1)
                    filtered_tours[tour_id] += 1
                    continue

                # Write non-matching lines to output file
                outfile.write(line)

        # Print summary
        print(f"\nProcessing complete:")
        print(f"Total lines processed: {total_count}")
        print(f"\nFiltered messages:")
        print(f"- Spatial index errors: {spatial_count}")
        print(f"- Freight carrier messages: {freight_count}")
        print(f"Total lines filtered: {spatial_count + freight_count}")
        print(f"Lines remaining: {total_count - (spatial_count + freight_count)}")

        # Print freight tour details if any were found
        if filtered_tours:
            print(f"\nFiltered tours breakdown:")
            print("Tour ID\t\tCount")
            print("-" * 30)
            for tour_id, count in sorted(filtered_tours.items()):
                print(f"{tour_id}\t\t{count}")

            # Additional statistics about tours
            print(f"\nTotal unique tours filtered: {len(filtered_tours)}")

        print(f"\nFiltered log saved to: {output_file}")

    except FileNotFoundError:
        print(f"Error: Could not find input file '{input_file}'")
        sys.exit(1)
    except PermissionError:
        print(f"Error: Permission denied when accessing files")
        sys.exit(1)
    except Exception as e:
        print(f"An unexpected error occurred: {str(e)}")
        sys.exit(1)


if __name__ == "__main__":
    # Get input file path from command line argument or use default
    if len(sys.argv) > 1:
        input_file = sys.argv[1]
    else:
        input_file = os.path.expanduser("~/Downloads/beamLog (3).out")

    # Create output filename by adding '_filtered' before the extension
    input_path = Path(input_file)
    output_file = input_path.with_stem(input_path.stem + '_filtered')

    filter_log_file(input_file, str(output_file))
