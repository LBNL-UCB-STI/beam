import pandas as pd
import gzip
import sys
import json
from collections import Counter, defaultdict
import re


def parse_hstore_format(data_string):
    """
    Parse fields that contain hstore format data
    Format example: "oneway"=>"no","reversed"=>"False","length"=>"72.674",...
    """
    if pd.isna(tag_string) or not tag_string:
        return {}

    result = {}
    # Handle the standard hstore format
    try:
        # Split by commas not inside quotes
        parts = []
        in_quotes = False
        current = ""

        for char in tag_string:
            if char == '"' and (not current or current[-1] != '\\'):
                in_quotes = not in_quotes

            if char == ',' and not in_quotes:
                parts.append(current)
                current = ""
            else:
                current += char

        # Don't forget the last part
        if current:
            parts.append(current)

        # Process each part
        for part in parts:
            if "=>" in part:
                key_val = part.split("=>")
                if len(key_val) == 2:
                    key = key_val[0].strip().strip('"')
                    val = key_val[1].strip().strip('"')
                    result[key] = val
    except Exception as e:
        print(f"Error parsing tags: {e}")
        if len(tag_string) > 100:
            print(f"Preview: {tag_string[:100]}...")
        else:
            print(f"String: {tag_string}")

    return result


def analyze_csv(file_path, sample_size=None):
    """
    Analyze a CSV file (possibly gzipped) with OSM data

    Args:
        file_path: Path to the CSV or CSV.GZ file
        sample_size: Optional number of rows to sample (for large files)

    Returns:
        Dictionary of statistics and DataFrame
    """
    print(f"Analyzing file: {file_path}")

    # Determine if the file is gzipped
    is_gzipped = file_path.endswith('.gz')

    try:
        # Read the file (with optional sampling)
        if is_gzipped:
            if sample_size:
                # For very large files, we use chunking
                chunks = []
                with gzip.open(file_path, 'rt') as f:
                    # Read and process in chunks
                    for chunk in pd.read_csv(f, chunksize=min(100000, sample_size)):
                        chunks.append(chunk)
                        if sum(len(c) for c in chunks) >= sample_size:
                            break
                df = pd.concat(chunks)
                df = df.head(sample_size)
            else:
                # Read the entire file
                with gzip.open(file_path, 'rt') as f:
                    df = pd.read_csv(f)
        else:
            # Regular CSV file
            if sample_size:
                df = pd.read_csv(file_path, nrows=sample_size)
            else:
                df = pd.read_csv(file_path)

        print(f"Loaded {len(df)} rows with {len(df.columns)} columns")

        # Get basic column statistics
        column_stats = {}
        for column in df.columns:
            non_null_count = df[column].count()
            unique_count = df[column].nunique()

            # Calculate top values
            value_counts = df[column].value_counts().head(10).to_dict()

            column_stats[column] = {
                'count': non_null_count,
                'percent_present': round(non_null_count / len(df) * 100, 2),
                'unique_values': unique_count,
                'top_values': value_counts
            }

        # Look for and parse hstore-formatted fields
        other_tags_stats = {}
        if 'other_tags' in df.columns:
            print("Found 'other_tags' column, parsing nested data...")

            # Create a new column with parsed data
            df['other_tags_parsed'] = df['other_tags'].apply(parse_hstore_format)

            # Extract all unique keys from other_tags
            all_keys = set()
            for tags_dict in df['other_tags_parsed'].dropna():
                if isinstance(tags_dict, dict):
                    all_keys.update(tags_dict.keys())

            # For each key, collect statistics
            for key in all_keys:
                # Count occurrences and values
                counter = Counter()
                valid_entries = 0

                for tags_dict in df['other_tags_parsed'].dropna():
                    if isinstance(tags_dict, dict) and key in tags_dict:
                        counter[tags_dict[key]] += 1
                        valid_entries += 1

                other_tags_stats[key] = {
                    'count': valid_entries,
                    'percent_present': round(valid_entries / len(df) * 100, 2),
                    'unique_values': len(counter),
                    'top_values': dict(counter.most_common(10))
                }

            # Sort by frequency
            other_tags_stats = {k: v for k, v in sorted(
                other_tags_stats.items(),
                key=lambda item: item[1]['count'],
                reverse=True
            )}

        # Create summary statistics
        stats = {
            'total_rows': len(df),
            'columns': list(df.columns),
            'column_stats': column_stats,
            'other_tags_stats': other_tags_stats
        }

        return stats, df

    except Exception as e:
        print(f"Error analyzing CSV file: {e}")
        return None, None


def print_column_stats(stats, limit=None):
    """Print column statistics in a formatted way"""
    columns = list(stats['column_stats'].keys())

    print(f"\n=== Column Statistics ({len(columns)} columns) ===")

    for i, column in enumerate(columns):
        if limit and i >= limit:
            print(f"\n... and {len(columns) - limit} more columns.")
            break

        data = stats['column_stats'][column]
        print(f"\n{i + 1}. {column}: {data['count']} non-null values ({data['percent_present']}% filled)")
        print(f"   Unique values: {data['unique_values']}")

        if data['unique_values'] <= 20:  # Only show all values for categorical columns
            print("   Values:")
            for val, count in data['top_values'].items():
                val_display = str(val)
                if len(val_display) > 50:
                    val_display = val_display[:47] + "..."
                print(f"     - {val_display}: {count}")
        else:
            print("   Top values:")
            for val, count in data['top_values'].items():
                val_display = str(val)
                if len(val_display) > 50:
                    val_display = val_display[:47] + "..."
                print(f"     - {val_display}: {count}")


def print_nested_field_stats(stats, limit=None):
    """Print statistics for keys inside hstore-formatted fields"""
    if not stats.get('other_tags_stats'):
        print("\n=== No hstore-formatted fields found ===")
        return

    nested_keys = list(stats['other_tags_stats'].keys())

    print(f"\n=== Nested Field Analysis ({len(nested_keys)} unique keys) ===")

    for i, key in enumerate(nested_keys):
        if limit and i >= limit:
            print(f"\n... and {len(nested_keys) - limit} more keys.")
            break

        data = stats['other_tags_stats'][key]
        print(f"\n{i + 1}. {key}: {data['count']} occurrences ({data['percent_present']}% of rows)")
        print(f"   Unique values: {data['unique_values']}")

        print("   Top values:")
        for val, count in data['top_values'].items():
            val_display = str(val)
            if len(val_display) > 50:
                val_display = val_display[:47] + "..."
            print(f"     - {val_display}: {count}")


def main(file_path, sample_size=None):
    """Main function to analyze a CSV file"""
    # Parse sample size if provided
    if sample_size and sample_size.isdigit():
        sample_size = int(sample_size)
    else:
        sample_size = None

    # Analyze the file
    stats, df = analyze_csv(file_path, sample_size)

    if not stats:
        print("Analysis failed.")
        return

    print(f"\n=== CSV Analysis Summary ===")
    print(f"Total rows: {stats['total_rows']}")
    print(f"Total columns: {len(stats['columns'])}")

    # Print column statistics
    print_column_stats(stats, limit=20)

    # Print nested field statistics
    print_nested_field_stats(stats, limit=20)

    return stats, df


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python csv_data_analyzer.py <file.csv or file.csv.gz> [sample_size]")
        sys.exit(1)

    file_path = sys.argv[1]
    sample_size = sys.argv[2] if len(sys.argv) > 2 else None

    main(file_path, sample_size)